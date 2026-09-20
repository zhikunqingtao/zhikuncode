package com.aicodeassistant.config.database;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Order(24)
public final class V024_CreateSessionMerges implements Migration {
    private static final String TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS session_merges (
                operation_id TEXT PRIMARY KEY,
                idempotency_key TEXT NOT NULL UNIQUE,
                params_json TEXT NOT NULL,
                target_session_id TEXT NOT NULL,
                status TEXT NOT NULL CHECK(status IN ('preparing','completed','failed')),
                stage TEXT NOT NULL,
                package_path TEXT NOT NULL,
                result_json TEXT NOT NULL DEFAULT '{}',
                usage_json TEXT NOT NULL DEFAULT '[]',
                error TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL
            )
            """;
    private static final String INDEX_SQL = "CREATE INDEX IF NOT EXISTS idx_session_merges_status ON session_merges(status)";
    private final JdbcTemplate jdbc;
    public V024_CreateSessionMerges(@Qualifier("projectJdbcTemplate") JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public String checksum() { return MigrationChecksums.sha256(TABLE_SQL + INDEX_SQL); }
    @Override public void execute() {
        jdbc.execute(TABLE_SQL);
        jdbc.execute(INDEX_SQL);
    }
    @Override public void validate() {
        var columns = jdbc.queryForList("PRAGMA table_info(session_merges)").stream().map(row -> row.get("name").toString()).toList();
        if (!columns.containsAll(java.util.List.of("operation_id", "idempotency_key", "params_json", "target_session_id",
                "status", "stage", "package_path", "result_json", "usage_json", "error", "created_at", "updated_at")))
            throw new IllegalStateException("Session merge schema is incomplete");
    }
}
