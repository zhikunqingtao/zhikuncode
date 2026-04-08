package com.aicodeassistant.config.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * V002: 初始化项目数据库 Schema (data.db)。
 * 创建 sessions, messages, permission_rules, file_snapshots, tasks 表。
 *
 * @see <a href="SPEC §7.4.2">数据库初始化迁移</a>
 */
@Order(2)
@Component
public class V002_InitProjectSchema implements Migration {

    private static final Logger log = LoggerFactory.getLogger(V002_InitProjectSchema.class);

    private final JdbcTemplate projectJdbcTemplate;

    public V002_InitProjectSchema(@Qualifier("projectJdbcTemplate") JdbcTemplate projectJdbcTemplate) {
        this.projectJdbcTemplate = projectJdbcTemplate;
    }

    @Override
    public void execute() {
        log.info("Creating data.db schema...");

        // 项目级配置 KV 表（与 global.db 的 global_config 结构一致）
        projectJdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS project_config (
                    key         TEXT PRIMARY KEY,
                    value       TEXT NOT NULL,
                    updated_at  TEXT NOT NULL
                )
                """);

        // 会话表
        projectJdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS sessions (
                    id                    TEXT PRIMARY KEY,
                    title                 TEXT,
                    model                 TEXT NOT NULL,
                    working_dir           TEXT NOT NULL,
                    status                TEXT NOT NULL DEFAULT 'active',
                    total_input_tokens    INTEGER DEFAULT 0,
                    total_output_tokens   INTEGER DEFAULT 0,
                    total_cache_read      INTEGER DEFAULT 0,
                    total_cache_create    INTEGER DEFAULT 0,
                    total_cost_usd        REAL DEFAULT 0.0,
                    summary               TEXT,
                    metadata_json         TEXT,
                    created_at            TEXT NOT NULL,
                    updated_at            TEXT NOT NULL
                )
                """);
        projectJdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_sessions_updated ON sessions(updated_at DESC)");
        projectJdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_sessions_working_dir ON sessions(working_dir)");

        // 消息表
        projectJdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS messages (
                    id           TEXT PRIMARY KEY,
                    session_id   TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                    role         TEXT NOT NULL,
                    content_json TEXT NOT NULL,
                    stop_reason  TEXT,
                    input_tokens  INTEGER DEFAULT 0,
                    output_tokens INTEGER DEFAULT 0,
                    created_at   TEXT NOT NULL,
                    seq_num      INTEGER NOT NULL,
                    UNIQUE(session_id, seq_num)
                )
                """);
        projectJdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_messages_session ON messages(session_id, seq_num)");

        // 权限规则表
        projectJdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS permission_rules (
                    id           TEXT PRIMARY KEY,
                    tool_name    TEXT NOT NULL,
                    rule_content TEXT,
                    rule_type    TEXT NOT NULL,
                    scope        TEXT NOT NULL,
                    session_id   TEXT,
                    created_at   TEXT NOT NULL,
                    expires_at   TEXT
                )
                """);
        projectJdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_permission_rules_scope ON permission_rules(scope, tool_name)");

        // 文件快照表
        projectJdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS file_snapshots (
                    id           TEXT PRIMARY KEY,
                    session_id   TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                    file_path    TEXT NOT NULL,
                    content      BLOB,
                    operation    TEXT NOT NULL,
                    tool_use_id  TEXT,
                    created_at   TEXT NOT NULL
                )
                """);
        projectJdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_file_snapshots_session ON file_snapshots(session_id, file_path)");

        // 任务表
        projectJdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS tasks (
                    id           TEXT PRIMARY KEY,
                    session_id   TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                    description  TEXT NOT NULL,
                    task_type    TEXT NOT NULL,
                    status       TEXT NOT NULL DEFAULT 'pending',
                    output       TEXT,
                    error        TEXT,
                    progress     REAL DEFAULT 0.0,
                    created_at   TEXT NOT NULL,
                    updated_at   TEXT NOT NULL,
                    completed_at TEXT
                )
                """);
        projectJdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_tasks_session ON tasks(session_id, status)");
    }
}
