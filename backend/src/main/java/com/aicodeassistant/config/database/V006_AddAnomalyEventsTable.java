package com.aicodeassistant.config.database;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * V006: 异常事件持久化表。
 * 存储 Swarm/Worker 运行期间的异常事件（中止、超时、规则违规等）。
 * Phase 2 异常检测和诊断基础设施。
 */
@Order(6)
@Component
public class V006_AddAnomalyEventsTable implements Migration {

    private static final Logger log = LoggerFactory.getLogger(V006_AddAnomalyEventsTable.class);

    private final JdbcTemplate projectJdbcTemplate;

    public V006_AddAnomalyEventsTable(@Qualifier("projectJdbcTemplate") JdbcTemplate projectJdbcTemplate) {
        this.projectJdbcTemplate = projectJdbcTemplate;
    }

    @Override
    public void execute() {
        log.info("Creating anomaly_events table...");

        projectJdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS anomaly_events (
                    id                TEXT PRIMARY KEY,
                    swarm_id          TEXT NOT NULL,
                    worker_id         TEXT NOT NULL,
                    rule_id           TEXT NOT NULL,
                    severity          TEXT NOT NULL,
                    message           TEXT NOT NULL,
                    detected_at       INTEGER NOT NULL,
                    resolved_at       INTEGER,
                    resolution        TEXT,
                    context_snapshot  TEXT
                )
                """);

        projectJdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_anomaly_swarm ON anomaly_events(swarm_id)");
        projectJdbcTemplate.execute(
                "CREATE INDEX IF NOT EXISTS idx_anomaly_worker ON anomaly_events(worker_id, detected_at)");

        log.info("V006: anomaly_events table created.");
    }
}
