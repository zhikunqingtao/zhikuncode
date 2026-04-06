package com.aicodeassistant.service;

import com.aicodeassistant.config.database.SqliteConfig;
import com.aicodeassistant.model.McpServerConfig;
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
import java.util.List;
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
        String json = loadConfigJson(globalJdbcTemplate, "user_config");
        if (json == null) {
            return defaultUserConfig();
        }
        try {
            return objectMapper.readValue(json, UserConfig.class);
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
            saveConfigJson(globalJdbcTemplate, "user_config", mergedJson);
            log.info("User config updated: {} fields", updates.size());
            return updated;
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to update user config", e);
        }
    }

    /**
     * 获取项目级配置。
     */
    public ProjectConfig getProjectConfig() {
        String json = loadConfigJson(projectJdbcTemplate, "project_config");
        if (json == null) {
            return defaultProjectConfig();
        }
        try {
            return objectMapper.readValue(json, ProjectConfig.class);
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
            saveConfigJson(projectJdbcTemplate, "project_config", mergedJson);
            log.info("Project config updated: {} fields", updates.size());
            return updated;
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to update project config", e);
        }
    }

    // ───── 内部方法 ─────

    private String loadConfigJson(JdbcTemplate jdbc, String key) {
        List<String> results = jdbc.queryForList(
                "SELECT value FROM config WHERE key = ?", String.class, key);
        return results.isEmpty() ? null : results.getFirst();
    }

    private void saveConfigJson(JdbcTemplate jdbc, String key, String json) {
        int updated = jdbc.update(
                "UPDATE config SET value = ?, updated_at = datetime('now') WHERE key = ?",
                json, key);
        if (updated == 0) {
            jdbc.update(
                    "INSERT INTO config (key, value, updated_at) VALUES (?, ?, datetime('now'))",
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
}
