package com.aicodeassistant.run;

import com.aicodeassistant.config.database.DatabaseResolver;
import com.aicodeassistant.config.database.SqliteConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * RunEnvelope 数据访问层 — 操作 data.db 的 run_envelopes 表。
 */
@Repository
public class RunEnvelopeRepository {

    private static final Logger log = LoggerFactory.getLogger(RunEnvelopeRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final SqliteConfig sqliteConfig;
    private final Path dbPath;

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
                Instant.parse(rs.getString("updated_at"))
        );
    };

    public RunEnvelopeRepository(@Qualifier("projectJdbcTemplate") JdbcTemplate jdbcTemplate,
                                  SqliteConfig sqliteConfig,
                                  DatabaseResolver databaseResolver) {
        this.jdbcTemplate = jdbcTemplate;
        this.sqliteConfig = sqliteConfig;
        this.dbPath = databaseResolver.getProjectDbPath(Path.of(System.getProperty("user.dir")));
    }

    /** 插入新运行信封 */
    public void insert(RunEnvelope envelope) {
        sqliteConfig.executeWriteVoid(dbPath, () ->
                jdbcTemplate.update("""
                        INSERT INTO run_envelopes
                        (id, session_id, parent_run_id, status, agent_type, model, prompt_hash,
                         started_at, finished_at, abort_reason, total_tokens, total_cost_usd,
                         tool_call_count, turn_count, error_summary, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        envelope.id(),
                        envelope.sessionId(),
                        envelope.parentRunId(),
                        envelope.status().dbValue(),
                        envelope.agentType(),
                        envelope.model(),
                        envelope.promptHash(),
                        envelope.startedAt().toString(),
                        envelope.finishedAt() != null ? envelope.finishedAt().toString() : null,
                        envelope.abortReason(),
                        envelope.totalTokens(),
                        envelope.totalCostUsd(),
                        envelope.toolCallCount(),
                        envelope.turnCount(),
                        envelope.errorSummary(),
                        envelope.createdAt().toString(),
                        envelope.updatedAt().toString()
                ));
        log.debug("Inserted run envelope: id={}, session={}", envelope.id(), envelope.sessionId());
    }

    /** 更新运行信封状态 */
    public void updateStatus(String runId, RunEnvelope updated) {
        sqliteConfig.executeWriteVoid(dbPath, () ->
                jdbcTemplate.update("""
                        UPDATE run_envelopes SET
                            status = ?, finished_at = ?, abort_reason = ?,
                            total_tokens = ?, total_cost_usd = ?,
                            tool_call_count = ?, turn_count = ?,
                            error_summary = ?, updated_at = ?
                        WHERE id = ?
                        """,
                        updated.status().dbValue(),
                        updated.finishedAt() != null ? updated.finishedAt().toString() : null,
                        updated.abortReason(),
                        updated.totalTokens(),
                        updated.totalCostUsd(),
                        updated.toolCallCount(),
                        updated.turnCount(),
                        updated.errorSummary(),
                        updated.updatedAt().toString(),
                        runId
                ));
        log.debug("Updated run envelope: id={}, status={}", runId, updated.status());
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

    /** 查找所有正在运行的信封 */
    public List<RunEnvelope> findRunning() {
        return jdbcTemplate.query(
                "SELECT * FROM run_envelopes WHERE status = ?",
                ROW_MAPPER, RunEnvelope.RunStatus.RUNNING.dbValue());
    }

    /** 批量将 running 状态标记为 aborted — 启动清理使用 */
    public int markStaleRunsAborted(String abortReason) {
        String now = Instant.now().toString();
        return sqliteConfig.executeWrite(dbPath, () ->
                jdbcTemplate.update("""
                        UPDATE run_envelopes SET status = ?, abort_reason = ?,
                            finished_at = ?, updated_at = ?
                        WHERE status = ?
                        """,
                        RunEnvelope.RunStatus.ABORTED.dbValue(),
                        abortReason,
                        now, now,
                        RunEnvelope.RunStatus.RUNNING.dbValue()
                ));
    }
}
