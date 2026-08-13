package com.aicodeassistant.run;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * RunEnvelope 数据访问层 — 操作 data.db 的 run_envelopes 表。
 */
@Repository
public class RunEnvelopeRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final RowMapper<RunEnvelope> ROW_MAPPER = (rs, rowNum) -> {
        String finishedAtStr = rs.getString("finished_at");
        return new RunEnvelope(
                rs.getString("id"),
                rs.getString("session_id"),
                rs.getString("parent_run_id"),
                RunEnvelope.RunStatus.fromDbValue(rs.getString("status")),
                rs.getString("agent_type"),
                rs.getString("model"),
                rs.getString("prompt_hash"),
                Instant.parse(rs.getString("started_at")),
                finishedAtStr != null ? Instant.parse(finishedAtStr) : null,
                rs.getString("abort_reason"),
                rs.getInt("total_tokens"),
                rs.getDouble("total_cost_usd"),
                rs.getInt("tool_call_count"),
                rs.getInt("turn_count"),
                rs.getString("error_summary"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")),
                rs.getLong("version"),
                RunEnvelope.RunExitReason.fromDbValue(rs.getString("exit_reason")),
                RunEnvelope.RunExitReason.fromDbValue(rs.getString("requested_exit_reason")),
                RunEnvelope.VerificationStatus.fromDbValue(rs.getString("verification_status")),
                rs.getString("terminal_at") == null ? null : Instant.parse(rs.getString("terminal_at")),
                rs.getString("waiting_reason")
        );
    };

    public RunEnvelopeRepository(@Qualifier("projectJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 按 ID 查找运行信封 */
    public Optional<RunEnvelope> findById(String runId) {
        List<RunEnvelope> results = jdbcTemplate.query(
                "SELECT * FROM run_envelopes WHERE id = ?", ROW_MAPPER, runId);
        return results.stream().findFirst();
    }

    /** 按会话 ID 查找运行信封列表 */
    public List<RunEnvelope> findBySession(String sessionId, int limit) {
        return jdbcTemplate.query(
                "SELECT * FROM run_envelopes WHERE session_id = ? ORDER BY started_at DESC LIMIT ?",
                ROW_MAPPER, sessionId, limit);
    }

    /** 会话最近一次根 Run；子 Agent Run 不参与当前交付版本选择。 */
    public Optional<RunEnvelope> findLatestRootBySession(String sessionId) {
        List<RunEnvelope> results = jdbcTemplate.query(
                """
                SELECT * FROM run_envelopes
                WHERE session_id = ? AND parent_run_id IS NULL
                ORDER BY started_at DESC, id DESC LIMIT 1
                """, ROW_MAPPER, sessionId);
        return results.stream().findFirst();
    }

    /** 按时间倒序读取会话根 Run，用于寻找上次可用交付。 */
    public List<RunEnvelope> findRootRunsBySession(String sessionId, int limit) {
        return jdbcTemplate.query(
                """
                SELECT * FROM run_envelopes
                WHERE session_id = ? AND parent_run_id IS NULL
                ORDER BY started_at DESC, id DESC LIMIT ?
                """, ROW_MAPPER, sessionId, limit);
    }

    /** 返回 rootRun 及其递归子 Run。 */
    public List<RunEnvelope> findTree(String rootRunId) {
        return jdbcTemplate.query("""
                WITH RECURSIVE run_tree(id) AS (
                  SELECT id FROM run_envelopes WHERE id = ? AND parent_run_id IS NULL
                  UNION ALL
                  SELECT child.id FROM run_envelopes child
                  JOIN run_tree parent ON child.parent_run_id = parent.id
                )
                SELECT r.* FROM run_envelopes r JOIN run_tree t ON t.id = r.id
                ORDER BY r.started_at ASC, r.id ASC
                """, ROW_MAPPER, rootRunId);
    }

    /** 查找所有正在运行的信封 */
    public List<RunEnvelope> findRunning() {
        return jdbcTemplate.query(
                "SELECT * FROM run_envelopes WHERE status = ?",
                ROW_MAPPER, RunEnvelope.RunStatus.RUNNING.dbValue());
    }

}
