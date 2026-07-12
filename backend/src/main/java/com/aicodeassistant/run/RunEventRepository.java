package com.aicodeassistant.run;

import com.aicodeassistant.config.database.DatabaseResolver;
import com.aicodeassistant.config.database.SqliteConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * RunEvent 数据访问层 — 操作 data.db 的 run_event_log 表。
 */
@Repository
public class RunEventRepository {

    private static final Logger log = LoggerFactory.getLogger(RunEventRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final SqliteConfig sqliteConfig;
    private final Path dbPath;

    private static final RowMapper<RunEvent> ROW_MAPPER = (rs, rowNum) ->
            new RunEvent(
                    rs.getLong("id"),
                    rs.getString("run_id"),
                    rs.getInt("seq"),
                    rs.getString("event_type"),
                    rs.getString("event_data"),
                    rs.getLong("ts")
            );

    public RunEventRepository(@Qualifier("projectJdbcTemplate") JdbcTemplate jdbcTemplate,
                               SqliteConfig sqliteConfig,
                               DatabaseResolver databaseResolver) {
        this.jdbcTemplate = jdbcTemplate;
        this.sqliteConfig = sqliteConfig;
        this.dbPath = databaseResolver.getProjectDbPath(Path.of(System.getProperty("user.dir")));
    }

    /** 追加单个事件 */
    @Transactional
    public void append(RunEvent event) {
        sqliteConfig.executeWriteVoid(dbPath, () ->
                jdbcTemplate.update(
                        "INSERT INTO run_event_log (run_id, seq, event_type, event_data, ts) VALUES (?, ?, ?, ?, ?)",
                        event.runId(), event.seq(), event.eventType(), event.eventData(), event.ts()
                ));
        log.debug("Appended event: run={}, seq={}, type={}", event.runId(), event.seq(), event.eventType());
    }

    /** 批量追加事件 */
    @Transactional
    public void appendBatch(List<RunEvent> events) {
        if (events.isEmpty()) return;
        sqliteConfig.executeWriteVoid(dbPath, () ->
            jdbcTemplate.batchUpdate(
                "INSERT INTO run_event_log (run_id, seq, event_type, event_data, ts) VALUES (?, ?, ?, ?, ?)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        RunEvent event = events.get(i);
                        ps.setString(1, event.runId());
                        ps.setInt(2, event.seq());
                        ps.setString(3, event.eventType());
                        ps.setString(4, event.eventData());
                        ps.setLong(5, event.ts());
                    }
                    @Override
                    public int getBatchSize() {
                        return events.size();
                    }
                }
            )
        );
        log.debug("Batch appended {} events for run={}", events.size(), events.getFirst().runId());
    }

    /** 获取事件列表 — 支持游标分页 */
    public List<RunEvent> getEvents(String runId, int afterSeq, int limit) {
        return jdbcTemplate.query(
                "SELECT * FROM run_event_log WHERE run_id = ? AND seq > ? ORDER BY seq ASC LIMIT ?",
                ROW_MAPPER, runId, afterSeq, limit);
    }

    /** 按事件类型查询 */
    public List<RunEvent> getEventsByType(String runId, String eventType) {
        return jdbcTemplate.query(
                "SELECT * FROM run_event_log WHERE run_id = ? AND event_type = ? ORDER BY seq ASC",
                ROW_MAPPER, runId, eventType);
    }

    /** 获取当前最大 seq */
    public int getMaxSeq(String runId) {
        Integer max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(seq), 0) FROM run_event_log WHERE run_id = ?",
                Integer.class, runId);
        return max != null ? max : 0;
    }
}
