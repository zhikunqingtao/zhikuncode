package com.aicodeassistant.config.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * V001: 初始化全局数据库 Schema (global.db)。
 * 创建 global_config, auth_tokens, memories 表。
 *
 * @see <a href="SPEC §7.4.2">数据库初始化迁移</a>
 */
@Order(1)
@Component
public class V001_InitGlobalSchema implements Migration {

    private static final Logger log = LoggerFactory.getLogger(V001_InitGlobalSchema.class);

    private final JdbcTemplate globalJdbcTemplate;

    public V001_InitGlobalSchema(@Qualifier("globalJdbcTemplate") JdbcTemplate globalJdbcTemplate) {
        this.globalJdbcTemplate = globalJdbcTemplate;
    }

    @Override
    public void execute() {
        log.info("Creating global.db schema...");

        globalJdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS global_config (
                    key         TEXT PRIMARY KEY,
                    value       TEXT NOT NULL,
                    updated_at  TEXT NOT NULL
                )
                """);

        globalJdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS auth_tokens (
                    key             TEXT PRIMARY KEY,
                    encrypted_value TEXT NOT NULL,
                    expires_at      TEXT
                )
                """);

        globalJdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS memories (
                    id           TEXT PRIMARY KEY,
                    category     TEXT NOT NULL,
                    title        TEXT NOT NULL,
                    content      TEXT NOT NULL,
                    keywords     TEXT,
                    scope        TEXT DEFAULT 'global',
                    project_path TEXT,
                    created_at   TEXT NOT NULL,
                    updated_at   TEXT NOT NULL
                )
                """);

        globalJdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_memories_scope ON memories(scope)");
        globalJdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_memories_category ON memories(category)");

        // 全局权限规则表
        globalJdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS permission_rules (
                    id           TEXT PRIMARY KEY,
                    tool_name    TEXT NOT NULL,
                    rule_content TEXT,
                    rule_type    TEXT NOT NULL,
                    scope        TEXT NOT NULL DEFAULT 'global',
                    session_id   TEXT,
                    created_at   TEXT NOT NULL,
                    expires_at   TEXT
                )
                """);

        globalJdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_permission_rules_scope ON permission_rules(scope, tool_name)");
    }
}
