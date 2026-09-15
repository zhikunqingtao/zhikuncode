package com.aicodeassistant.config.database;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Order(23)
public final class V023_CreateMeooPublications implements Migration {
    private final JdbcTemplate jdbc;
    public V023_CreateMeooPublications(@Qualifier("projectJdbcTemplate") JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Override public String checksum() { return MigrationChecksums.sha256("v023-meoo-publications-v1"); }
    @Override public void execute() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS meoo_publications (
                id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL,
                run_id TEXT NOT NULL,
                tool_use_id TEXT NOT NULL,
                snapshot_hash TEXT NOT NULL,
                label TEXT NOT NULL,
                runtime TEXT NOT NULL,
                account_id TEXT NOT NULL,
                state TEXT NOT NULL,
                project_id TEXT,
                project_url TEXT,
                version TEXT,
                access_url TEXT,
                error_code TEXT,
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                UNIQUE(run_id, tool_use_id)
            )
            """);
    }
    @Override public void validate() {
        jdbc.queryForObject("SELECT count(*) FROM meoo_publications",Long.class);
    }
}
