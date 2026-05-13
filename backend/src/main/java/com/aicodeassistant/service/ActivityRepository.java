package com.aicodeassistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Activity 数据访问层 — 操作 data.db 的 activities 表。
 */
@Repository
public class ActivityRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ActivityRepository(@Qualifier("projectJdbcTemplate") JdbcTemplate jdbcTemplate,
                              ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 插入或替换一条 Activity 记录。
     */
    public void upsert(String id, String sessionId, String operationType, String summary,
                       String status, long timestamp, Integer duration, int fileCount,
                       String decision, String toolResultJson, String changedFilesJson, String insightJson) {
        String now = Instant.now().toString();
        jdbcTemplate.update(
                """
                INSERT OR REPLACE INTO activities (id, session_id, operation_type, summary,
                    status, timestamp, duration, file_count, decision,
                    tool_result_json, changed_files_json, insight_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, sessionId, operationType, summary,
                status, timestamp, duration, fileCount, decision,
                toolResultJson, changedFilesJson, insightJson, now, now
        );
    }

    /**
     * 按会话 ID 查询所有 Activity（按 timestamp 升序）。
     */
    public List<Map<String, Object>> findBySessionId(String sessionId) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM activities WHERE session_id = ? ORDER BY timestamp ASC",
                sessionId
        );
    }

    /**
     * 更新 Activity 的 decision 字段。
     */
    public void updateDecision(String id, String decision) {
        String now = Instant.now().toString();
        jdbcTemplate.update(
                "UPDATE activities SET decision = ?, updated_at = ? WHERE id = ?",
                decision, now, id
        );
    }

    /**
     * 更新 Activity 的 insight_json 字段。
     */
    public void updateInsight(String id, String insightJson) {
        String now = Instant.now().toString();
        jdbcTemplate.update(
                "UPDATE activities SET insight_json = ?, updated_at = ? WHERE id = ?",
                insightJson, now, id
        );
    }

    /**
     * 按会话 ID 分页查询 Activity（按 timestamp DESC，最新的在前）。
     *
     * @param sessionId 会话 ID
     * @param offset    跳过条数
     * @param limit     返回条数上限
     * @return Activity 列表（按 timestamp DESC 排序）
     */
    public List<Map<String, Object>> findBySessionIdPaged(String sessionId, int offset, int limit) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM activities WHERE session_id = ? ORDER BY timestamp DESC LIMIT ? OFFSET ?",
                sessionId, limit, offset
        );
    }

    /**
     * 查询指定会话的 Activity 总数。
     */
    public int countBySessionId(String sessionId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM activities WHERE session_id = ?",
                Integer.class, sessionId
        );
        return count != null ? count : 0;
    }

    /**
     * 删除指定会话的所有 Activity。
     */
    public void deleteBySessionId(String sessionId) {
        jdbcTemplate.update(
                "DELETE FROM activities WHERE session_id = ?",
                sessionId
        );
    }
}
