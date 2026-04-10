package com.aicodeassistant.service;

import com.aicodeassistant.config.database.SqliteConfig;
import com.aicodeassistant.model.PermissionMode;
import com.aicodeassistant.model.PermissionRule;
import com.aicodeassistant.model.ProjectConfig;
import com.aicodeassistant.model.UserConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.*;
import java.util.Map;

/**
 * 配置管理服务 — 全局配置和项目配置的 CRUD。
 * <p>
 * 全局配置存储在 global.db，项目配置存储在 data.db。
 * 配置以 JSON 格式存储在 key-value 表中。
 *
 * @see <a href="SPEC §6.1.3">配置管理 API</a>
 */
@Service
public class ConfigService {

    private static final Logger log = LoggerFactory.getLogger(ConfigService.class);

    private final JdbcTemplate globalJdbcTemplate;
    private final JdbcTemplate projectJdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SqliteConfig sqliteConfig;

    private volatile UserConfig cachedUserConfig;
    private volatile ProjectConfig cachedProjectConfig;

    public ConfigService(@Qualifier("globalJdbcTemplate") JdbcTemplate globalJdbcTemplate,
                         @Qualifier("projectJdbcTemplate") JdbcTemplate projectJdbcTemplate,
                         ObjectMapper objectMapper,
                         SqliteConfig sqliteConfig) {
        this.globalJdbcTemplate = globalJdbcTemplate;
        this.projectJdbcTemplate = projectJdbcTemplate;
        this.objectMapper = objectMapper;
        this.sqliteConfig = sqliteConfig;
    }

    /**
     * 获取用户全局配置。
     */
    public UserConfig getUserConfig() {
        if (cachedUserConfig != null) {
            return cachedUserConfig;
        }
        String json = loadConfigJson(globalJdbcTemplate, "global_config", "user_config");
        if (json == null) {
            return defaultUserConfig();
        }
        try {
            cachedUserConfig = objectMapper.readValue(json, UserConfig.class);
            return cachedUserConfig;
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse user config, returning default: {}", e.getMessage());
            return defaultUserConfig();
        }
    }

    /**
     * 更新用户全局配置 — 支持部分更新。
     */
    public UserConfig updateUserConfig(Map<String, Object> updates) {
        UserConfig current = getUserConfig();
        // 合并更新: 将 updates 合并到当前配置
        try {
            String currentJson = objectMapper.writeValueAsString(current);
            Map<String, Object> currentMap = objectMapper.readValue(currentJson,
                    new TypeReference<>() {});
            currentMap.putAll(updates);
            String mergedJson = objectMapper.writeValueAsString(currentMap);
            UserConfig updated = objectMapper.readValue(mergedJson, UserConfig.class);
            saveConfigJson(globalJdbcTemplate, "global_config", "user_config", mergedJson);
            log.info("User config updated: {} fields", updates.size());
            cachedUserConfig = updated;
            return updated;
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to update user config", e);
        }
    }

    /**
     * 获取项目级配置。
     */
    public ProjectConfig getProjectConfig() {
        if (cachedProjectConfig != null) {
            return cachedProjectConfig;
        }
        String json = loadConfigJson(projectJdbcTemplate, "project_config", "project_config");
        if (json == null) {
            return defaultProjectConfig();
        }
        try {
            cachedProjectConfig = objectMapper.readValue(json, ProjectConfig.class);
            return cachedProjectConfig;
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse project config, returning default: {}", e.getMessage());
            return defaultProjectConfig();
        }
    }

    /**
     * 更新项目级配置 — 支持部分更新。
     */
    public ProjectConfig updateProjectConfig(Map<String, Object> updates) {
        ProjectConfig current = getProjectConfig();
        try {
            String currentJson = objectMapper.writeValueAsString(current);
            Map<String, Object> currentMap = objectMapper.readValue(currentJson,
                    new TypeReference<>() {});
            currentMap.putAll(updates);
            String mergedJson = objectMapper.writeValueAsString(currentMap);
            ProjectConfig updated = objectMapper.readValue(mergedJson, ProjectConfig.class);
            saveConfigJson(projectJdbcTemplate, "project_config", "project_config", mergedJson);
            log.info("Project config updated: {} fields", updates.size());
            cachedProjectConfig = updated;
            return updated;
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to update project config", e);
        }
    }

    // ───── 公共方法 ─────

    /**
     * 重新加载配置 — 清空内部缓存，下次 get 时重新从数据库加载。
     */
    public void reload() {
        log.info("Reloading configuration");
        this.cachedUserConfig = null;
        this.cachedProjectConfig = null;
    }

    // ───── 内部方法 ─────

    /**
     * 从指定数据库的指定表读取配置 JSON。
     *
     * @param jdbc      JdbcTemplate（global.db 或 data.db）
     * @param tableName 表名（global_config 或 project_config）
     * @param key       配置键
     */
    private String loadConfigJson(JdbcTemplate jdbc, String tableName, String key) {
        try {
            List<String> results = jdbc.queryForList(
                    "SELECT value FROM " + tableName + " WHERE key = ?", String.class, key);
            return results.isEmpty() ? null : results.getFirst();
        } catch (Exception e) {
            log.warn("Failed to load config from {}.{}: {}", tableName, key, e.getMessage());
            return null;
        }
    }

    /**
     * 保存配置 JSON 到指定数据库的指定表。
     * 使用 UPDATE + INSERT 模式（兼容无数据时的首次写入）。
     */
    private void saveConfigJson(JdbcTemplate jdbc, String tableName, String key, String json) {
        int updated = jdbc.update(
                "UPDATE " + tableName + " SET value = ?, updated_at = datetime('now') WHERE key = ?",
                json, key);
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO " + tableName + " (key, value, updated_at) VALUES (?, ?, datetime('now'))",
                    key, json);
        }
    }

    private UserConfig defaultUserConfig() {
        return new UserConfig(
                "localhost", null, null,
                "gpt-4o", Map.of(),
                "dark", "en",
                PermissionMode.DEFAULT, List.of(), List.of(),
                Map.of(),
                false, true, 80
        );
    }

    private ProjectConfig defaultProjectConfig() {
        return new ProjectConfig(
                null, null, 0.0,
                List.of(), Map.of(), Map.of()
        );
    }

    /**
     * 获取合并后的有效配置 — 5 层优先级合并。
     *
     * 优先级（低→高）:
     * 1. 默认值 (代码内置)
     * 2. 企业级 (managed-settings.json, P2 延后)
     * 3. 用户级 (~/.config/.../settings.json → global SQLite)
     * 4. 项目级 (.ai-assistant/settings.json → project SQLite)
     * 5. 本地级 (.ai-assistant/local-settings.json, 不提交到 VCS)
     */
    public Map<String, Object> getEffectiveSettings() {
        Map<String, Object> result = new LinkedHashMap<>();

        // Layer 1: 默认值
        result.putAll(getDefaultSettings());

        // Layer 2: 企业级 — P2 预留
        // Map<String, Object> enterprise = loadEnterpriseManagedSettings();
        // if (enterprise != null) result.putAll(enterprise);

        // Layer 3: 用户级
        try {
            UserConfig userConfig = getUserConfig();
            String json = objectMapper.writeValueAsString(userConfig);
            Map<String, Object> userMap = objectMapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {});
            result.putAll(userMap);
        } catch (Exception e) {
            log.warn("Failed to merge user config: {}", e.getMessage());
        }

        // Layer 4: 项目级
        try {
            ProjectConfig projectConfig = getProjectConfig();
            String json = objectMapper.writeValueAsString(projectConfig);
            Map<String, Object> projMap = objectMapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {});
            result.putAll(projMap);
        } catch (Exception e) {
            log.warn("Failed to merge project config: {}", e.getMessage());
        }

        // Layer 5: 本地级 (不提交到 VCS)
        Map<String, Object> localSettings = loadLocalSettings();
        if (localSettings != null) result.putAll(localSettings);

        return Collections.unmodifiableMap(result);
    }

    private Map<String, Object> getDefaultSettings() {
        return new LinkedHashMap<>(Map.of(
            "theme", "auto",
            "verbose", false,
            "outputStyle", "normal",
            "maxTurns", 100,
            "autoCompact", true
        ));
    }

    /**
     * 加载本地级配置 — .ai-assistant/local-settings.json（不提交 VCS）。
     */
    private Map<String, Object> loadLocalSettings() {
        try {
            // 注意: SqliteConfig 无 getProjectDir() 方法，使用 user.dir 系统属性
            Path localPath = Path.of(System.getProperty("user.dir"),
                ".ai-assistant", "local-settings.json");
            if (java.nio.file.Files.exists(localPath)) {
                String json = java.nio.file.Files.readString(localPath);
                return objectMapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {});
            }
        } catch (Exception e) {
            log.warn("Failed to load local settings: {}", e.getMessage());
        }
        return null;
    }
}
