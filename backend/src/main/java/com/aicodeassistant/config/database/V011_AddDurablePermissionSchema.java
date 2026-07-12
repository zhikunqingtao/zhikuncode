package com.aicodeassistant.config.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * V011: Durable Permission 持久化权限请求表。
 * 支持断线重连后恢复 pending 权限请求。
 */
@Order(11)
@Component
public class V011_AddDurablePermissionSchema implements Migration {

    private static final Logger log = LoggerFactory.getLogger(V011_AddDurablePermissionSchema.class);

    private final JdbcTemplate projectJdbcTemplate;

    public V011_AddDurablePermissionSchema(@Qualifier("projectJdbcTemplate") JdbcTemplate projectJdbcTemplate) {
        this.projectJdbcTemplate = projectJdbcTemplate;
    }

    @Override
    public void execute() {
        log.info("Creating permission_requests table...");

        projectJdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS permission_requests (
                id              TEXT PRIMARY KEY,
                run_id          TEXT REFERENCES run_envelopes(id),
                session_id      TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
                tool_use_id     TEXT NOT NULL,
                tool_name       TEXT NOT NULL,
                risk_level      TEXT NOT NULL DEFAULT 'medium',
                reason          TEXT,
                input_summary   TEXT,
                status          TEXT NOT NULL DEFAULT 'pending',
                decision        TEXT,
                decided_by      TEXT,
                remember        INTEGER DEFAULT 0,
                remember_scope  TEXT,
                requested_at    TEXT NOT NULL,
                decided_at      TEXT,
                timeout_at      TEXT NOT NULL,
                source          TEXT DEFAULT 'direct',
                child_session_id TEXT,
                created_at      TEXT NOT NULL
            )
        """);
        projectJdbcTemplate.execute(
            "CREATE INDEX IF NOT EXISTS idx_perm_req_session ON permission_requests(session_id, status)");
        projectJdbcTemplate.execute(
            "CREATE INDEX IF NOT EXISTS idx_perm_req_status ON permission_requests(status, timeout_at)");
        projectJdbcTemplate.execute(
            "CREATE INDEX IF NOT EXISTS idx_perm_req_tool_use ON permission_requests(tool_use_id)");

        log.info("V011: permission_requests table created.");
    }
}
