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
                Instant.parse(rs.getString("updated_at")),
                rs.getLong("version"),
                RunEnvelope.RunExitReason.fromDbValue(rs.getString("exit_reason")),
                RunEnvelope.RunExitReason.fromDbValue(rs.getString("requested_exit_reason")),
                RunEnvelope.VerificationStatus.fromDbValue(rs.getString("verification_status")),
                rs.getString("terminal_at") == null ? null : Instant.parse(rs.getString("terminal_at")),
                rs.getString("waiting_reason")
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
                         tool_call_count, turn_count, error_summary, created_at, updated_at,
                         version, exit_reason, requested_exit_reason, verification_status,
                         terminal_at, waiting_reason)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                        envelope.updatedAt().toString(),
                        envelope.version(),
                        envelope.exitReason() == null ? null : envelope.exitReason().dbValue(),
                        envelope.requestedExitReason() == null ? null : envelope.requestedExitReason().dbValue(),
                        envelope.verificationStatus().dbValue(),
                        envelope.terminalAt() == null ? null : envelope.terminalAt().toString(),
                        envelope.waitingReason()
                ));
        log.debug("Inserted run envelope: id={}, session={}", envelope.id(), envelope.sessionId());
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

}
