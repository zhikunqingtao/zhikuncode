package com.aicodeassistant.config.database;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Persists the session permission mode; existing sessions default to standard authorization. */
@Component
@Order(25)
public final class V025_AddSessionPermissionMode implements Migration {
    private static final String SQL = """
            ALTER TABLE sessions ADD COLUMN permission_mode TEXT NOT NULL DEFAULT 'DEFAULT'
                CHECK(permission_mode IN ('DEFAULT','PLAN','ACCEPT_EDITS','DONT_ASK','AUTO_APPROVE'))
            """;
    private final JdbcTemplate jdbc;

    public V025_AddSessionPermissionMode(@Qualifier("projectJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override public String checksum() { return MigrationChecksums.sha256(SQL); }

    @Override
    public void execute() {
        if (jdbc.queryForList("PRAGMA table_info(sessions)").stream()
                .noneMatch(column -> "permission_mode".equals(column.get("name")))) {
            jdbc.execute(SQL);
        }
    }

    @Override
    public void validate() {
        boolean valid = jdbc.queryForList("PRAGMA table_info(sessions)").stream()
                .anyMatch(column -> "permission_mode".equals(column.get("name"))
                        && "TEXT".equalsIgnoreCase(String.valueOf(column.get("type")))
                        && ((Number) column.get("notnull")).intValue() == 1
                        && "'DEFAULT'".equals(column.get("dflt_value")));
        if (!valid) throw new IllegalStateException("V025 sessions.permission_mode schema incomplete");
    }
}
