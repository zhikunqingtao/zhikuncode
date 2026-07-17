package com.aicodeassistant.config.database;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 创建 WebSocket 会话绑定持久化表 — 支持服务重启后自动恢复 principal↔sessionId 绑定。
 * <p>
 * 解决问题：服务重启后 WebSocketSessionManager 的内存映射清空，
 * 客户端重连时心跳在 bind-session 确认前发送导致 SESSION_NOT_BOUND。
 */
@Component @Order(18)
public class V018_CreateWebSocketBindingSchema implements Migration {

    private static final String CHECKSUM = MigrationChecksums.sha256("v018-websocket-session-binding");
    private final JdbcTemplate jdbc;

    public V018_CreateWebSocketBindingSchema(@Qualifier("projectJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String checksum() {
        return CHECKSUM;
    }

    @Override
    public void execute() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS websocket_session_binding (
                    principal_name TEXT NOT NULL,
                    app_session_id TEXT NOT NULL,
                    binding_epoch INTEGER NOT NULL DEFAULT 0,
                    last_activity_at TEXT NOT NULL DEFAULT (datetime('now')),
                    PRIMARY KEY (principal_name)
                )
                """);
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_ws_binding_session ON websocket_session_binding(app_session_id)");
    }

    @Override
    public void validate() {
        Integer tables = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='websocket_session_binding'",
                Integer.class);
        if (tables == null || tables != 1) {
            throw new IllegalStateException("V018 websocket_session_binding postcondition failed");
        }
    }
}
