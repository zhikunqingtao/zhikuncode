package com.aicodeassistant.run;

import com.aicodeassistant.config.database.DatabaseResolver;
import com.aicodeassistant.config.database.SqliteConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.aicodeassistant.security.SensitiveDataFilter;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.DependsOn;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Single database authority for Run state transitions and event sequencing. */
@Service
@DependsOn("migrationRunner")
public class RunControlService {
    private static final Logger log = LoggerFactory.getLogger(RunControlService.class);
    private final JdbcTemplate jdbc;
    private final SqliteConfig sqliteConfig;
    private final Path dbPath;
    private final TransactionTemplate transaction;
    private final ObjectMapper objectMapper;
    private final SensitiveDataFilter sensitiveDataFilter;
    private static final int MAX_EVENT_BYTES = 10 * 1024;

    @Autowired
    public RunControlService(@Qualifier("projectJdbcTemplate") JdbcTemplate jdbc,
                             SqliteConfig sqliteConfig, DatabaseResolver resolver,
                             @Qualifier("projectTransactionManager") PlatformTransactionManager txManager,
                             ObjectMapper objectMapper, SensitiveDataFilter sensitiveDataFilter) {
        this.jdbc = jdbc;
        this.sqliteConfig = sqliteConfig;
        this.dbPath = resolver.getProjectDbPath(Path.of(System.getProperty("user.dir")));
        this.transaction = new TransactionTemplate(txManager);
        this.objectMapper = objectMapper;
        this.sensitiveDataFilter = sensitiveDataFilter;
    }

    /** Isolated test/source compatibility constructor. */
    public RunControlService(JdbcTemplate jdbc, SqliteConfig sqliteConfig, DatabaseResolver resolver,
                             PlatformTransactionManager txManager, ObjectMapper objectMapper) {
        this(jdbc, sqliteConfig, resolver, txManager, objectMapper, null);
    }

    public RunEnvelope start(String sessionId, String parentRunId, String agentType, String model) {
        RunEnvelope run = RunEnvelope.start(sessionId, parentRunId, agentType, model);
        write(() -> {
            jdbc.update("""
                    INSERT INTO run_envelopes
                    (id,session_id,parent_run_id,status,version,agent_type,model,started_at,
                     total_tokens,total_cost_usd,tool_call_count,turn_count,verification_status,
                     created_at,updated_at)
                    VALUES(?,?,?,?,0,?,?,?,0,0,0,0,'not_requested',?,?)
                    """, run.id(), run.sessionId(), run.parentRunId(), run.status().dbValue(),
                    run.agentType(), run.model(), run.startedAt().toString(),
                    run.createdAt().toString(), run.updatedAt().toString());
            appendEventInCurrentWrite(run.id(), "run_started", null, Map.of(
                    "sessionId", sessionId, "agentType", value(agentType), "model", value(model)));
            return null;
        });
        return run;
    }

    public TransitionResult markWaiting(String runId, String reason) {
        return transition(runId, List.of("running"), RunEnvelope.RunStatus.WAITING_INTERACTION,
                null, null, reason, null, null, 0, 0.0, 0);
    }

    public TransitionResult markRunning(String runId) {
        return transition(runId, List.of("waiting_interaction"), RunEnvelope.RunStatus.RUNNING,
                null, null, null, null, null, 0, 0.0, 0);
    }

    public TransitionResult requestCancel(String runId, RunEnvelope.RunExitReason reason) {
        return transition(runId, List.of("running", "waiting_interaction"),
                RunEnvelope.RunStatus.CANCELLING, null, reason, null,
                null, null, 0, 0.0, 0);
    }

    public TransitionResult complete(String runId, int tokens, double cost, int turns) {
        return transition(runId, List.of("running"), RunEnvelope.RunStatus.COMPLETED,
                RunEnvelope.RunExitReason.MODEL_FINISHED, null, null,
                null, null, tokens, cost, turns);
    }

    public TransitionResult fail(String runId, RunEnvelope.RunExitReason reason, String error) {
        return transition(runId, List.of("running", "waiting_interaction", "cancelling"),
                RunEnvelope.RunStatus.FAILED, reason, null, null, null, error, 0, 0.0, 0);
    }

    public TransitionResult cancel(String runId) {
        return transition(runId, List.of("cancelling", "running", "waiting_interaction"),
                RunEnvelope.RunStatus.CANCELLED, RunEnvelope.RunExitReason.USER_CANCELLED,
                null, null, "user_cancelled", null, 0, 0.0, 0);
    }

    public TransitionResult interrupt(String runId, RunEnvelope.RunExitReason reason) {
        return transition(runId, List.of("queued", "running", "waiting_interaction", "cancelling"),
                RunEnvelope.RunStatus.INTERRUPTED, reason, null, null,
                reason.dbValue(), null, 0, 0.0, 0);
    }

    public RunEvent appendEvent(String runId, String type, String toolUseId, Object data) {
        return write(() -> appendEventInCurrentWrite(runId, type, toolUseId, data));
    }

    public TransitionResult setVerification(String runId, RunEnvelope.VerificationStatus expected,
                                            RunEnvelope.VerificationStatus target, String detail) {
        return write(() -> {
            List<Map<String,Object>> rows=jdbc.queryForList(
                    "SELECT version,verification_status FROM run_envelopes WHERE id=?",runId);
            if(rows.isEmpty())return TransitionResult.NOT_FOUND;
            String current=String.valueOf(rows.getFirst().get("verification_status"));
            if(!expected.dbValue().equals(current))return TransitionResult.INVALID_TRANSITION;
            long version=((Number)rows.getFirst().get("version")).longValue();
            int updated=jdbc.update("UPDATE run_envelopes SET verification_status=?,version=version+1,updated_at=? WHERE id=? AND version=? AND verification_status=?",
                    target.dbValue(),Instant.now().toString(),runId,version,expected.dbValue());
            if(updated!=1)return TransitionResult.VERSION_CONFLICT;
            appendEventInCurrentWrite(runId,"verification_status_changed",null,Map.of(
                    "from",current,"to",target.dbValue(),"detail",value(detail)));
            return TransitionResult.APPLIED;
        });
    }

    private TransitionResult transition(String runId, List<String> expected,
                                        RunEnvelope.RunStatus target,
                                        RunEnvelope.RunExitReason exitReason,
                                        RunEnvelope.RunExitReason requestedReason,
                                        String waitingReason, String abortReason, String error,
                                        int tokens, double cost, int turns) {
        return write(() -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT status,version FROM run_envelopes WHERE id=?", runId);
            if (rows.isEmpty()) return TransitionResult.NOT_FOUND;
            String current = String.valueOf(rows.getFirst().get("status"));
            long version = ((Number) rows.getFirst().get("version")).longValue();
            if (!expected.contains(current)) {
                return isTerminal(current) ? TransitionResult.ALREADY_TERMINAL : TransitionResult.INVALID_TRANSITION;
            }
            Instant now = Instant.now();
            boolean terminal = target.terminal();
            int updated = jdbc.update("""
                    UPDATE run_envelopes SET status=?, exit_reason=?, requested_exit_reason=COALESCE(?,requested_exit_reason),
                      waiting_reason=?, abort_reason=?, error_summary=?,
                      total_tokens=CASE WHEN ?>0 THEN ? ELSE total_tokens END,
                      total_cost_usd=CASE WHEN ?>0 THEN ? ELSE total_cost_usd END,
                      turn_count=CASE WHEN ?>0 THEN ? ELSE turn_count END,
                      finished_at=?, terminal_at=?, version=version+1, updated_at=?
                    WHERE id=? AND version=? AND status IN (%s)
                    """.formatted(expected.stream().map(s -> "'" + s + "'").reduce((a,b) -> a+","+b).orElse("''")),
                    target.dbValue(), db(exitReason), db(requestedReason), waitingReason,
                    abortReason, error, tokens, tokens, cost, cost, turns, turns,
                    terminal ? now.toString() : null, terminal ? now.toString() : null,
                    now.toString(), runId, version);
            if (updated != 1) return TransitionResult.VERSION_CONFLICT;
            if (terminal) {
                // ChildExactGrant lifetime is bounded by the parent Run. Keeping
                // this in the same project transaction avoids an authority gap.
                jdbc.update("UPDATE permission_grants SET expires_at=COALESCE(expires_at,?), revoked_at=COALESCE(revoked_at,?) " +
                                "WHERE grant_kind='CHILD_EXACT' AND parent_run_id=? AND revoked_at IS NULL",
                        now.toString(), now.toString(), runId);
            }
            appendEventInCurrentWrite(runId, "run_status_changed", null, Map.of(
                    "from", current, "to", target.dbValue(),
                    "exitReason", value(db(exitReason))));
            return TransitionResult.APPLIED;
        });
    }

    /**
     * Appends an event inside a caller-owned project-database write lock/transaction.
     * Only project-database authority services may use this; controllers must call appendEvent().
     */
    public RunEvent appendEventInCurrentWrite(String runId, String type, String toolUseId, Object data) {
        Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(seq),0) FROM run_event_log WHERE run_id=?",
                Integer.class, runId);
        int seq = (max == null ? 0 : max) + 1;
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", 2);
        envelope.put("entityId", runId);
        if (toolUseId != null) envelope.put("toolUseId", toolUseId);
        envelope.put("data", data);
        String json;
        try {
            json = objectMapper.writeValueAsString(envelope);
            byte[] eventBytes = json.getBytes(StandardCharsets.UTF_8);
            if (eventBytes.length > MAX_EVENT_BYTES) {
                String hash = HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(eventBytes));
                envelope.put("data", Map.of(
                        "truncated", true,
                        "originalBytes", eventBytes.length,
                        "payloadSha256", hash,
                        "payloadPreview", sensitiveDataFilter == null
                                ? json.substring(0, Math.min(2048, json.length()))
                                : sensitiveDataFilter.filter(json.substring(0, Math.min(2048, json.length())))));
                json = objectMapper.writeValueAsString(envelope);
            } else if (sensitiveDataFilter != null) {
                json = objectMapper.writeValueAsString(sanitizeNode(objectMapper.readTree(json)));
            }
        }
        catch (Exception e) { throw new IllegalArgumentException("RUN_EVENT_SERIALIZATION_FAILED", e); }
        long ts = System.currentTimeMillis();
        jdbc.update("INSERT INTO run_event_log(run_id,seq,event_type,event_data,ts) VALUES(?,?,?,?,?)",
                runId, seq, type, json, ts);
        if ("tool_started".equals(type) || "tool_call".equals(type)) {
            jdbc.update("UPDATE run_envelopes SET tool_call_count=tool_call_count+1,updated_at=? WHERE id=?",
                    Instant.now().toString(), runId);
        }
        return new RunEvent(null, runId, seq, type, json, ts);
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

    @PostConstruct
    void interruptStaleRuns() {
        List<String> ids = jdbc.queryForList("SELECT id FROM run_envelopes WHERE status IN ('queued','running','waiting_interaction','cancelling')", String.class);
        ids.forEach(id -> interrupt(id, RunEnvelope.RunExitReason.SERVICE_RESTART));
    }

    private <T> T write(java.util.function.Supplier<T> operation) {
        return sqliteConfig.executeWrite(dbPath,
                () -> transaction.execute(status -> operation.get()));
    }
    private static boolean isTerminal(String s) { return List.of("completed","failed","cancelled","interrupted").contains(s); }
    private static String db(RunEnvelope.RunExitReason r) { return r == null ? null : r.dbValue(); }
    private static String value(Object value) { return value == null ? "unknown" : String.valueOf(value); }

    public enum TransitionResult { APPLIED, ALREADY_TERMINAL, VERSION_CONFLICT, INVALID_TRANSITION, NOT_FOUND }
}
