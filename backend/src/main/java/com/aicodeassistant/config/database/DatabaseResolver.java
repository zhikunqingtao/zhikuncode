package com.aicodeassistant.config.database;

import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 数据库路径解析器 — 根据 scope 决定使用全局库还是项目库。
 *
 * @see <a href="SPEC §7.1">存储策略</a>
 */
@Component
public class DatabaseResolver {

    private static final String GLOBAL_DB = "global.db";
    private static final String PROJECT_DB = "data.db";

    /**
     * 全局数据库路径: ~/.config/ai-code-assistant/global.db
     */
    public Path getGlobalDbPath() {
        return Path.of(System.getProperty("user.home"),
                ".config", "ai-code-assistant", GLOBAL_DB);
    }

    /**
     * 项目数据库路径: &lt;projectRoot&gt;/.ai-code-assistant/data.db
     */
    public Path getProjectDbPath(Path projectRoot) {
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
