package com.aicodeassistant.config.database;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 数据库路径解析器 — 根据 scope 决定使用全局库还是项目库。
 *
 */
@Component
public class DatabaseResolver {

    private static final String GLOBAL_DB = "global.db";
    private static final String PROJECT_DB = "data.db";
    private final String configuredGlobalPath;
    private final String configuredProjectRoot;

    public DatabaseResolver(
            @Value("${zhikuncode.database.global-path:}") String configuredGlobalPath,
            @Value("${zhikuncode.database.project-root:}") String configuredProjectRoot) {
        this.configuredGlobalPath = configuredGlobalPath;
        this.configuredProjectRoot = configuredProjectRoot;
    }

    /**
     * 全局数据库路径: ~/.config/ai-code-assistant/global.db
     */
    public Path getGlobalDbPath() {
        if (configuredGlobalPath != null && !configuredGlobalPath.isBlank()) {
            return Path.of(configuredGlobalPath).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home"),
                ".config", "ai-code-assistant", GLOBAL_DB);
    }

    /**
     * 项目数据库路径: &lt;projectRoot&gt;/.ai-code-assistant/data.db
     */
    public Path getProjectDbPath(Path projectRoot) {
        if (configuredProjectRoot != null && !configuredProjectRoot.isBlank()) {
            return Path.of(configuredProjectRoot).toAbsolutePath().normalize()
                    .resolve(".ai-code-assistant").resolve(PROJECT_DB);
        }
        return projectRoot.resolve(".ai-code-assistant").resolve(PROJECT_DB);
    }

    /**
     * 确保数据库父目录存在。
     */
    public void ensureDirectoryExists(Path dbPath) {
        try {
            Files.createDirectories(dbPath.getParent());
        } catch (Exception e) {
            throw new RuntimeException("Failed to create database directory: " + dbPath.getParent(), e);
        }
    }
}
