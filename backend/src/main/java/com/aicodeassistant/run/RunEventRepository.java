package com.aicodeassistant.run;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * RunEvent 数据访问层 — 操作 data.db 的 run_event_log 表。
 */
@Repository
public class RunEventRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<RunEvent> ROW_MAPPER = (rs, rowNum) ->
            new RunEvent(
                    rs.getLong("id"),
                    rs.getString("run_id"),
                    rs.getInt("seq"),
                    rs.getString("event_type"),
                    rs.getString("event_data"),
                    rs.getLong("ts")
            );

    public RunEventRepository(@Qualifier("projectJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
