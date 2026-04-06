package com.aicodeassistant.service;

import com.aicodeassistant.model.SessionStatus;
import com.aicodeassistant.model.SessionSummary;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 会话数据访问层 — 操作 data.db 的 sessions 表。
 *
 * @see <a href="SPEC §7.2">SQLite Schema</a>
 */
@Repository
public class SessionRepository {

    private final JdbcTemplate jdbcTemplate;

    public SessionRepository(@Qualifier("projectJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<SessionSummary> SUMMARY_ROW_MAPPER = (rs, rowNum) ->
            new SessionSummary(
                    rs.getString("id"),
                    rs.getString("title"),
                    rs.getString("model"),
                    rs.getString("working_dir"),
                    rs.getInt("message_count"),
                    rs.getDouble("total_cost_usd"),
                    Instant.parse(rs.getString("created_at")),
                    Instant.parse(rs.getString("updated_at"))
            );

    /**
     * 创建新会话。
     */
    public String create(String model, String workingDir) {
        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();
        jdbcTemplate.update(
                "INSERT INTO sessions (id, model, working_dir, status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)",
                id, model, workingDir, SessionStatus.ACTIVE.name().toLowerCase(), now, now
        );
        return id;
    }

    /**
     * 按 ID 查询会话摘要。
     */
    public Optional<SessionSummary> findById(String sessionId) {
        List<SessionSummary> results = jdbcTemplate.query(
                """
                SELECT s.*, (SELECT COUNT(*) FROM messages m WHERE m.session_id = s.id) AS message_count
                FROM sessions s WHERE s.id = ?
                """,
                SUMMARY_ROW_MAPPER,
                sessionId
        );
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    /**
     * 列出所有会话（按更新时间倒序）。
     */
    public List<SessionSummary> listAll(int limit) {
        return jdbcTemplate.query(
                """
                SELECT s.*, (SELECT COUNT(*) FROM messages m WHERE m.session_id = s.id) AS message_count
                FROM sessions s ORDER BY s.updated_at DESC LIMIT ?
                """,
                SUMMARY_ROW_MAPPER,
                limit
        );
    }

    /**
     * 更新会话标题。
     */
    public void updateTitle(String sessionId, String title) {
        jdbcTemplate.update(
                "UPDATE sessions SET title = ?, updated_at = ? WHERE id = ?",
                title, Instant.now().toString(), sessionId
        );
    }

    /**
     * 更新 Token 使用量和成本。
     */
    public void updateUsage(String sessionId, int inputTokens, int outputTokens,
                            int cacheRead, int cacheCreate, double costUsd) {
        jdbcTemplate.update(
                """
                UPDATE sessions SET
                    total_input_tokens = total_input_tokens + ?,
                    total_output_tokens = total_output_tokens + ?,
                    total_cache_read = total_cache_read + ?,
                    total_cache_create = total_cache_create + ?,
                    total_cost_usd = total_cost_usd + ?,
                    updated_at = ?
                WHERE id = ?
                """,
                inputTokens, outputTokens, cacheRead, cacheCreate, costUsd,
                Instant.now().toString(), sessionId
        );
    }

    /**
     * 更新会话状态。
     */
    public void updateStatus(String sessionId, SessionStatus status) {
        jdbcTemplate.update(
                "UPDATE sessions SET status = ?, updated_at = ? WHERE id = ?",
                status.name().toLowerCase(), Instant.now().toString(), sessionId
        );
    }

    /**
     * 删除会话（级联删除消息、文件快照、任务）。
     */
    public void delete(String sessionId) {
        jdbcTemplate.update("DELETE FROM sessions WHERE id = ?", sessionId);
    }
}
