package com.aicodeassistant.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 消息数据访问层 — 操作 data.db 的 messages 表。
 *
 * @see <a href="SPEC §7.2">SQLite Schema</a>
 */
@Repository
public class MessageRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public MessageRepository(@Qualifier("projectJdbcTemplate") JdbcTemplate jdbcTemplate,
                             ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 插入单条消息。
     */
    public String insert(String sessionId, String role, Object content,
                         String stopReason, int inputTokens, int outputTokens, int seqNum) {
        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        String contentJson;
        try {
            contentJson = objectMapper.writeValueAsString(content);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize message content", e);
        }
        jdbcTemplate.update(
                """
                INSERT INTO messages (id, session_id, role, content_json, stop_reason,
                    input_tokens, output_tokens, created_at, seq_num)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, sessionId, role, contentJson, stopReason,
                inputTokens, outputTokens, now, seqNum
        );
        return id;
    }

    /**
     * 按会话 ID 查询所有消息（按 seq_num 升序）。
     */
    public List<Map<String, Object>> findBySessionId(String sessionId) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM messages WHERE session_id = ? ORDER BY seq_num ASC",
                sessionId
        );
    }

    /**
     * 获取指定会话的下一个 seq_num。
     */
    public int getNextSeqNum(String sessionId) {
        Integer max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(seq_num), 0) FROM messages WHERE session_id = ?",
                Integer.class, sessionId
        );
        return (max != null ? max : 0) + 1;
    }

    /**
     * 删除会话中指定序号之后的所有消息（用于 rewind）。
     */
    public int deleteAfterSeqNum(String sessionId, int seqNum) {
        return jdbcTemplate.update(
                "DELETE FROM messages WHERE session_id = ? AND seq_num > ?",
                sessionId, seqNum
        );
    }

    /**
     * 按消息 ID 删除。
     */
    public void deleteById(String messageId) {
        jdbcTemplate.update("DELETE FROM messages WHERE id = ?", messageId);
    }
}
