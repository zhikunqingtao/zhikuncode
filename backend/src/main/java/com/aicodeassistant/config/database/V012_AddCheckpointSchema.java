package com.aicodeassistant.config.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * V012: SubAgent Checkpoint 子代理检查点表。
 * 支持子代理执行过程中的状态持久化和超时恢复。
 */
@Order(12)
@Component
public class V012_AddCheckpointSchema implements Migration {

    private static final Logger log = LoggerFactory.getLogger(V012_AddCheckpointSchema.class);

    private final JdbcTemplate projectJdbcTemplate;

    public V012_AddCheckpointSchema(@Qualifier("projectJdbcTemplate") JdbcTemplate projectJdbcTemplate) {
        this.projectJdbcTemplate = projectJdbcTemplate;
    }

    @Override
    public void execute() {
        log.info("Creating agent_checkpoints table...");

        projectJdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS agent_checkpoints (
                id              TEXT PRIMARY KEY,
                run_id          TEXT NOT NULL,
                session_id      TEXT NOT NULL,
                agent_id        TEXT NOT NULL,
                seq             INTEGER NOT NULL,
                messages_json   TEXT NOT NULL,
                file_state_json TEXT,
                tool_call_count INTEGER DEFAULT 0,
                turn_count      INTEGER DEFAULT 0,
                tokens_consumed INTEGER DEFAULT 0,
                working_dir     TEXT,
                created_at      TEXT NOT NULL,
                UNIQUE(run_id, seq)
            )
        """);
        projectJdbcTemplate.execute(
            "CREATE INDEX IF NOT EXISTS idx_checkpoints_run ON agent_checkpoints(run_id, seq DESC)");
        projectJdbcTemplate.execute(
            "CREATE INDEX IF NOT EXISTS idx_checkpoints_agent ON agent_checkpoints(agent_id)");

        log.info("V012: agent_checkpoints table created.");
    }
}
