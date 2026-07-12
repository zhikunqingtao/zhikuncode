package com.aicodeassistant.config.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * V010: RunEnvelope + RunEventLog 任务状态持久化表。
 * 记录每次 LLM 交互的运行信封和事件日志。
 */
@Order(10)
@Component
public class V010_AddRunEnvelopeSchema implements Migration {

    private static final Logger log = LoggerFactory.getLogger(V010_AddRunEnvelopeSchema.class);

    private final JdbcTemplate projectJdbcTemplate;

    public V010_AddRunEnvelopeSchema(@Qualifier("projectJdbcTemplate") JdbcTemplate projectJdbcTemplate) {
        this.projectJdbcTemplate = projectJdbcTemplate;
    }

    @Override
    public void execute() {
        log.info("Creating run_envelopes and run_event_log tables...");

        projectJdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS run_envelopes (
                    id              TEXT PRIMARY KEY,
                    -- session_id: real session ID or virtual subagent ID (subagent-xxx)
                    session_id      TEXT NOT NULL,
                    parent_run_id   TEXT REFERENCES run_envelopes(id),
                    status          TEXT NOT NULL DEFAULT 'running',
                    agent_type      TEXT,
                    model           TEXT NOT NULL,
                    prompt_hash     TEXT,
                    started_at      TEXT NOT NULL,
                    finished_at     TEXT,
                    abort_reason    TEXT,
                    total_tokens    INTEGER DEFAULT 0,
                    total_cost_usd  REAL DEFAULT 0.0,
                    tool_call_count INTEGER DEFAULT 0,
                    turn_count      INTEGER DEFAULT 0,
                    error_summary   TEXT,
                    created_at      TEXT NOT NULL,
                    updated_at      TEXT NOT NULL
                )
                """);
        projectJdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_run_envelopes_session ON run_envelopes(session_id, started_at DESC)");
        projectJdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_run_envelopes_parent ON run_envelopes(parent_run_id)");
        projectJdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_run_envelopes_status ON run_envelopes(status)");

        projectJdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS run_event_log (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    run_id      TEXT NOT NULL REFERENCES run_envelopes(id) ON DELETE CASCADE,
                    seq         INTEGER NOT NULL,
                    event_type  TEXT NOT NULL,
                    event_data  TEXT NOT NULL,
                    ts          INTEGER NOT NULL,
                    UNIQUE(run_id, seq)
                )
                """);
        projectJdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_run_events_run_seq ON run_event_log(run_id, seq)");
        projectJdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_run_events_type ON run_event_log(event_type)");

        log.info("V010: run_envelopes and run_event_log tables created.");
    }
}
