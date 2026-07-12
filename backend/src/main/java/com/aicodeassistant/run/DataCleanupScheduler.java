package com.aicodeassistant.run;

import com.aicodeassistant.config.database.DatabaseResolver;
import com.aicodeassistant.config.database.SqliteConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
public class DataCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(DataCleanupScheduler.class);

    private final JdbcTemplate jdbcTemplate;
    private final SqliteConfig sqliteConfig;
    private final Path dbPath;

    public DataCleanupScheduler(@Qualifier("projectJdbcTemplate") JdbcTemplate jdbcTemplate,
                                 SqliteConfig sqliteConfig,
                                 DatabaseResolver databaseResolver) {
        this.jdbcTemplate = jdbcTemplate;
        this.sqliteConfig = sqliteConfig;
        this.dbPath = databaseResolver.getProjectDbPath(Path.of(System.getProperty("user.dir")));
    }

    /** Orphan checkpoint retention: 24 hours. */
    private static final long ORPHAN_RETENTION_HOURS = 24;

    /**
     * 每日凌晨3点清理过期数据。
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanupExpiredData() {
        try {
            // Delete events for completed/aborted runs older than 30 days
            Instant thirtyDaysAgo = Instant.now().minus(30, ChronoUnit.DAYS);

            sqliteConfig.executeWriteVoid(dbPath, () -> {
                int deletedEvents = jdbcTemplate.update("""
                    DELETE FROM run_event_log WHERE run_id IN (
                        SELECT id FROM run_envelopes
                        WHERE status IN ('completed', 'aborted', 'error', 'timeout')
                        AND finished_at < ?
                    )
                    """, thirtyDaysAgo.toString());

                int deletedCheckpoints = jdbcTemplate.update("""
                    DELETE FROM agent_checkpoints WHERE run_id IN (
                        SELECT id FROM run_envelopes
                        WHERE status IN ('completed', 'aborted', 'error', 'timeout')
                        AND finished_at < ?
                    )
                    """, thirtyDaysAgo.toString());

                // Clean orphan checkpoints whose run_id does not exist in run_envelopes
                // (e.g. SubAgent checkpoints using childSessionId like "subagent-xxx")
                Instant orphanThreshold = Instant.now().minus(ORPHAN_RETENTION_HOURS, ChronoUnit.HOURS);
                int deletedOrphans = jdbcTemplate.update("""
                    DELETE FROM agent_checkpoints
                    WHERE run_id NOT IN (SELECT id FROM run_envelopes)
                      AND created_at < ?
                    """, orphanThreshold.toString());

                if (deletedEvents > 0 || deletedCheckpoints > 0 || deletedOrphans > 0) {
                    log.info("Cleanup: deleted {} events, {} checkpoints, {} orphan checkpoints",
                        deletedEvents, deletedCheckpoints, deletedOrphans);
                }
            });
        } catch (Exception e) {
            log.error("Data cleanup failed: {}", e.getMessage(), e);
        }
    }
}
