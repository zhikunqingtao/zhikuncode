package com.aicodeassistant.config.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * V007: 证据包持久化表。
 * 存储 RV-1 运行时验证产生的证据包、证据条目及回归脚本。
 */
@Order(7)
@Component
public class V007_AddEvidenceTables implements Migration {

    private static final Logger log = LoggerFactory.getLogger(V007_AddEvidenceTables.class);

    private final JdbcTemplate projectJdbcTemplate;

    public V007_AddEvidenceTables(@Qualifier("projectJdbcTemplate") JdbcTemplate projectJdbcTemplate) {
        this.projectJdbcTemplate = projectJdbcTemplate;
    }

    @Override
    public void execute() {
        log.info("Creating evidence tables...");

        projectJdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS evidence_bundles (
                    bundle_id    TEXT PRIMARY KEY,
                    session_id   TEXT NOT NULL,
                    agent_id     TEXT,
                    kind         TEXT NOT NULL,
                    claim        TEXT,
                    verdict      TEXT NOT NULL,
                    created_at   TEXT NOT NULL
                )
                """);
        projectJdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_evidence_bundles_session ON evidence_bundles(session_id, created_at)");

        projectJdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS evidence_items (
                    id           TEXT PRIMARY KEY,
                    bundle_id    TEXT NOT NULL,
                    type         TEXT NOT NULL,
                    summary      TEXT,
                    blob_sha256  TEXT,
                    meta_json    TEXT,
                    sort_order   INTEGER NOT NULL DEFAULT 0
                )
                """);
        projectJdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_evidence_items_bundle ON evidence_items(bundle_id, sort_order)");

        projectJdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS regression_scripts (
                    script_id      TEXT PRIMARY KEY,
                    session_id     TEXT NOT NULL,
                    name           TEXT NOT NULL,
                    steps_json     TEXT NOT NULL,
                    base_url       TEXT,
                    start_command  TEXT,
                    created_at     TEXT NOT NULL,
                    last_verdict   TEXT
                )
                """);

        log.info("V007: evidence tables created.");
    }
}
