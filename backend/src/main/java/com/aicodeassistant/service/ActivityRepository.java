package com.aicodeassistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.aicodeassistant.security.SensitiveDataFilter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Activity 数据访问层 — 操作 data.db 的 activities 表。
 */
@Repository
public class ActivityRepository {

    private static final int MAX_JSON_BYTES = 10 * 1024;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SensitiveDataFilter sensitiveDataFilter;

    public ActivityRepository(@Qualifier("projectJdbcTemplate") JdbcTemplate jdbcTemplate,
                              ObjectMapper objectMapper,
                              SensitiveDataFilter sensitiveDataFilter) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.sensitiveDataFilter = sensitiveDataFilter;
    }

    /**
     * 插入或替换一条 Activity 记录。
     */
    public void upsert(String id, String sessionId, String operationType, String summary,
                       String status, long timestamp, Integer duration, int fileCount,
                       String decision, String toolResultJson, String changedFilesJson, String insightJson) {
        String now = Instant.now().toString();
        toolResultJson = boundJson(sanitizeJson(toolResultJson));
        changedFilesJson = boundJson(sanitizeJson(changedFilesJson));
        insightJson = boundJson(sanitizeJson(insightJson));
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

    /** Repository-level safety net so non-WebSocket producers cannot drop an Activity. */
    String boundJson(String value) {
        if (value == null) return null;
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= MAX_JSON_BYTES) return value;
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            String preview = value.substring(0, Math.min(2048, value.length()));
            String result = objectMapper.writeValueAsString(Map.of(
                    "truncated", true, "originalBytes", bytes.length,
                    "payloadSha256", hash, "payloadPreview", preview));
            if (result.getBytes(StandardCharsets.UTF_8).length <= MAX_JSON_BYTES) return result;
            return objectMapper.writeValueAsString(Map.of(
                    "truncated", true, "originalBytes", bytes.length, "payloadSha256", hash));
        } catch (Exception e) {
            throw new IllegalArgumentException("ACTIVITY_PAYLOAD_BOUNDING_FAILED", e);
        }
    }

    private String sanitizeJson(String value) {
        if (value == null) return null;
        try {
            return objectMapper.writeValueAsString(sanitizeNode(objectMapper.readTree(value)));
        } catch (Exception invalidJson) {
            try {
                return objectMapper.writeValueAsString(Map.of(
                        "invalidJson", true,
                        "payloadPreview", sensitiveDataFilter.filter(
                                value.substring(0, Math.min(2048, value.length())))));
            } catch (Exception serializationError) {
                throw new IllegalArgumentException("ACTIVITY_PAYLOAD_SANITIZATION_FAILED", serializationError);
            }
        }
    }

    private JsonNode sanitizeNode(JsonNode node) {
        if (node == null || node.isNull()) return node;
        if (node.isTextual()) return TextNode.valueOf(sensitiveDataFilter.filter(node.textValue()));
        if (node.isObject()) {
            ObjectNode copy = ((ObjectNode) node).deepCopy();
            copy.fields().forEachRemaining(entry -> copy.set(entry.getKey(), sanitizeNode(entry.getValue())));
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = objectMapper.createArrayNode();
            node.forEach(child -> copy.add(sanitizeNode(child)));
            return copy;
        }
        return node;
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
        insightJson = boundJson(sanitizeJson(insightJson));
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
