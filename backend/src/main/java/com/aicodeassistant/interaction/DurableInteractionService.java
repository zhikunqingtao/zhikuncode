package com.aicodeassistant.interaction;

import com.aicodeassistant.config.database.DatabaseResolver;
import com.aicodeassistant.config.database.SqliteConfig;
import com.aicodeassistant.run.RunControlService;
import com.aicodeassistant.run.RunEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/** Database-CAS interaction authority. Futures are wake-up hints only. */
@Service
@DependsOn("migrationRunner")
public class DurableInteractionService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DurableInteractionService.class);

    public static final int DELIVERY_WINDOW_SECONDS = 30;
    public static final int ACK_WINDOW_SECONDS = 5;
    public static final int DECISION_SECONDS = 300;
    private static final int MAX_WAITING = 32;
    private final JdbcTemplate jdbc;
    private final SqliteConfig sqlite;
    private final Path dbPath;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;
    private final RunControlService runs;
    private final ApplicationEventPublisher events;
    private final Semaphore capacity = new Semaphore(MAX_WAITING);
    private final Map<String, java.util.concurrent.CompletableFuture<InteractionRequest.Status>> wakeups = new ConcurrentHashMap<>();

    public DurableInteractionService(@Qualifier("projectJdbcTemplate") JdbcTemplate jdbc,
            SqliteConfig sqlite, DatabaseResolver resolver,
            @Qualifier("projectTransactionManager") PlatformTransactionManager txManager,
            ObjectMapper json, RunControlService runs, ApplicationEventPublisher events) {
        this.jdbc = jdbc; this.sqlite = sqlite;
        this.dbPath = resolver.getProjectDbPath(Path.of(System.getProperty("user.dir")));
        this.transaction = new TransactionTemplate(txManager); this.json = json; this.runs = runs;
        this.events = events;
    }

    public InteractionRequest create(String correlationKey, String sessionId, String runId,
            InteractionRequest.Type type, Object prompt, List<String> decisions,
            List<String> scopes, String source, String childSessionId) {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("INTERACTION_REQUIRES_RUN");
        if (!capacity.tryAcquire()) {
            events.publishEvent(new com.aicodeassistant.run.RunTerminationRequestedEvent(
                    runId, RunEnvelope.RunExitReason.INTERACTION_CAPACITY_EXCEEDED,
                    "Maximum pending interactions reached"));
            throw new IllegalStateException("INTERACTION_CAPACITY_EXCEEDED");
        }
        Instant now = Instant.now();
        String id = UUID.randomUUID().toString();
        try {
            String promptJson = json.writeValueAsString(prompt);
            String decisionsJson = json.writeValueAsString(decisions);
            String scopesJson = json.writeValueAsString(scopes);
            write(() -> {
                RunControlService.TransitionResult waiting = runs.markWaiting(
                        runId, InteractionRequest.db(type));
                if (waiting != RunControlService.TransitionResult.APPLIED) {
                    throw new IllegalStateException("INTERACTION_RUN_NOT_RUNNING: " + waiting);
                }
                int inserted = jdbc.update("""
                    INSERT INTO interaction_requests(interaction_id,correlation_key,session_id,run_id,type,status,
                      prompt_json,allowed_decisions_json,scope_options_json,created_at,delivery_window_ends_at,
                      source,child_session_id,updated_at) VALUES(?,?,?,?,?,'pending',?,?,?,?,?,?,?,?)
                    """, id, correlationKey, sessionId, runId, InteractionRequest.db(type), promptJson,
                    decisionsJson, scopesJson, now.toString(), now.plusSeconds(DELIVERY_WINDOW_SECONDS).toString(),
                    source == null ? "direct" : source, childSessionId, now.toString());
                runs.appendEventInCurrentWrite(runId, "interaction_created", null, Map.of(
                        "interactionId", id, "type", InteractionRequest.db(type), "status", "pending"));
                return inserted;
            });
        } catch (Exception e) {
            capacity.release();
            if (e instanceof IllegalStateException state) throw state;
            throw new IllegalArgumentException("INTERACTION_PAYLOAD_INVALID", e);
        }
        wakeups.put(id, new java.util.concurrent.CompletableFuture<>());
        InteractionRequest created = findById(id);
        events.publishEvent(new InteractionCreatedEvent(created));
        return created;
    }

    public boolean markDispatched(String id, String transportId) {
        Instant now = Instant.now();
        return write(() -> {
            int updated = jdbc.update("""
                UPDATE interaction_requests SET first_dispatched_at=COALESCE(first_dispatched_at,?),
                  delivery_ack_deadline_at=COALESCE(delivery_ack_deadline_at,?),
                  delivery_generation=delivery_generation+1,dispatch_attempts=dispatch_attempts+1,last_transport_id=?,
                  updated_at=? WHERE interaction_id=? AND status='pending' AND received_at IS NULL
                """, now.toString(), now.plusSeconds(ACK_WINDOW_SECONDS).toString(), transportId,
                now.toString(), id);
            if (updated == 1) return true;
            Integer acknowledged = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM interaction_requests
                    WHERE interaction_id=? AND status='pending' AND received_at IS NOT NULL
                    """, Integer.class, id);
            return acknowledged != null && acknowledged == 1;
        });
    }

    /**
     * Atomically claims a scheduled redelivery. The expected-attempt guard
     * prevents bind recovery and the scheduler from sending the same retry
     * twice. Initial dispatch is attempt 1; retries are due at 1s, 2s and 4s
     * from the first dispatch and stop after attempt 4.
     */
    public boolean claimRedelivery(String id, int expectedAttempts, String transportId) {
        if (expectedAttempts < 1 || expectedAttempts >= 4) return false;
        Instant now = Instant.now();
        return write(() -> jdbc.update("""
                UPDATE interaction_requests SET dispatch_attempts=dispatch_attempts+1,last_transport_id=?,
                  delivery_generation=delivery_generation+1,updated_at=? WHERE interaction_id=? AND status='pending'
                  AND received_at IS NULL AND dispatch_attempts=?
                """, transportId, now.toString(), id, expectedAttempts) == 1);
    }

    public List<InteractionRequest> redeliveryCandidates(Instant now) {
        return jdbc.query("""
                SELECT * FROM interaction_requests WHERE status='pending' AND received_at IS NULL
                  AND first_dispatched_at IS NOT NULL AND dispatch_attempts BETWEEN 1 AND 3
                  AND delivery_ack_deadline_at>?
                ORDER BY first_dispatched_at
                """, this::map, now.toString()).stream().filter(request -> {
            long ageMillis = java.time.Duration.between(request.firstDispatchedAt(), now).toMillis();
            long dueMillis = switch (request.dispatchAttempts()) {
                case 1 -> 1_000L;
                case 2 -> 2_000L;
                case 3 -> 4_000L;
                default -> Long.MAX_VALUE;
            };
            return ageMillis >= dueMillis;
        }).toList();
    }

    public boolean acknowledgeReceived(String id, int deliveryGeneration, String transportId) {
        if (transportId == null) return false;
        if (deliveryGeneration < 1) {
            // 客户端未传或传了默认值，降级为接受任何代际（向后兼容）
            log.warn("ACK received without valid deliveryGeneration (got {}), accepting as best-effort: interactionId={}",
                    deliveryGeneration, id);
            return acknowledgeWithoutGenerationCheck(id, transportId);
        }
        Instant now = Instant.now();
        return write(() -> {
            int updated = jdbc.update("""
                UPDATE interaction_requests SET received_at=?,decision_deadline_at=?,last_transport_id=?,
                  updated_at=? WHERE interaction_id=? AND status='pending'
                  AND received_at IS NULL
                  AND delivery_generation=?
                  AND ((delivery_ack_deadline_at IS NOT NULL AND delivery_ack_deadline_at>=?)
                    OR (delivery_ack_deadline_at IS NULL AND delivery_window_ends_at>=?))
                """, now.toString(), now.plusSeconds(DECISION_SECONDS).toString(), transportId,
                now.toString(), id, deliveryGeneration, now.toString(), now.toString());
            if (updated == 1) {
                InteractionRequest request = findById(id);
                runs.appendEventInCurrentWrite(request.runId(), "interaction_updated", null, Map.of(
                        "interactionId", id, "status", "pending", "received", true,
                        "decisionDeadlineAt", request.decisionDeadlineAt().toEpochMilli()));
                return true;
            }
            Integer already = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM interaction_requests WHERE interaction_id=? AND received_at IS NOT NULL " +
                            "AND delivery_generation=?",
                    Integer.class, id, deliveryGeneration);
            return already != null && already == 1;
        });
    }

    /**
     * 降级路径：不带 delivery_generation 条件的 ACK 确认。
     * 仅匹配 interaction_id + status='pending' + received_at IS NULL，
     * 用于客户端未传 deliveryGeneration 时的向后兼容。
     */
    private boolean acknowledgeWithoutGenerationCheck(String id, String transportId) {
        Instant now = Instant.now();
        return write(() -> {
            int updated = jdbc.update("""
                UPDATE interaction_requests SET received_at=?,decision_deadline_at=?,last_transport_id=?,
                  updated_at=? WHERE interaction_id=? AND status='pending'
                  AND received_at IS NULL
                  AND ((delivery_ack_deadline_at IS NOT NULL AND delivery_ack_deadline_at>=?)
                    OR (delivery_ack_deadline_at IS NULL AND delivery_window_ends_at>=?))
                """, now.toString(), now.plusSeconds(DECISION_SECONDS).toString(), transportId,
                now.toString(), id, now.toString(), now.toString());
            if (updated == 1) {
                InteractionRequest request = findById(id);
                runs.appendEventInCurrentWrite(request.runId(), "interaction_updated", null, Map.of(
                        "interactionId", id, "status", "pending", "received", true,
                        "decisionDeadlineAt", request.decisionDeadlineAt().toEpochMilli()));
                return true;
            }
            Integer already = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM interaction_requests WHERE interaction_id=? AND received_at IS NOT NULL",
                    Integer.class, id);
            return already != null && already == 1;
        });
    }

    /** Rebind delivery: rotate the delivery generation only while an ACK is still outstanding. */
    public InteractionRequest prepareRecoveryDelivery(String id, String transportId) {
        Instant now = Instant.now();
        write(() -> {
            jdbc.update("""
                    UPDATE interaction_requests SET delivery_generation=delivery_generation+1,
                      dispatch_attempts=dispatch_attempts+1,last_transport_id=?,
                      delivery_ack_deadline_at=?,updated_at=?
                    WHERE interaction_id=? AND status='pending' AND received_at IS NULL
                    """, transportId, now.plusSeconds(ACK_WINDOW_SECONDS).toString(),
                    now.toString(), id);
            return null;
        });
        return findById(id);
    }

    public InteractionRequest.Status decide(String id, long expectedVersion,
                                             InteractionRequest.Status terminal, Object response,
                                             String reason) {
        return decideRequest(id, expectedVersion, terminal, response, reason).status();
    }

    public InteractionRequest decideRequest(String id, long expectedVersion,
                                             InteractionRequest.Status terminal, Object response,
                                             String reason) {
        if (terminal != InteractionRequest.Status.ANSWERED && terminal != InteractionRequest.Status.DENIED
                && terminal != InteractionRequest.Status.CANCELLED)
            throw new IllegalArgumentException("Invalid user terminal status");
        Instant now = Instant.now();
        String responseJson;
        try { responseJson = response == null ? null : json.writeValueAsString(response); }
        catch (Exception e) { throw new IllegalArgumentException("INTERACTION_RESPONSE_INVALID", e); }
        boolean applied = write(() -> {
            int updated = jdbc.update("""
                UPDATE interaction_requests SET status=?,response_json=?,decided_at=?,terminal_reason=?,
                  updated_at=?,version=version+1 WHERE interaction_id=? AND status='pending' AND version=?
                """, InteractionRequest.db(terminal), responseJson, now.toString(), reason,
                now.toString(), id, expectedVersion);
            if (updated == 1) {
                InteractionRequest request = findById(id);
                appendTerminalEventInCurrentWrite(request);
                requireRunTransition(runs.markRunning(request.runId()), "resume after interaction decision");
            }
            return updated == 1;
        });
        InteractionRequest current = findById(id);
        if (applied) finish(current);
        return current;
    }

    public List<InteractionRequest> pending(String sessionId) {
        return jdbc.query("SELECT * FROM interaction_requests WHERE session_id=? AND status='pending' ORDER BY created_at",
                this::map, sessionId);
    }

    public List<InteractionView> pendingViews(String sessionId) {
        return pending(sessionId).stream().map(this::view).toList();
    }

    @SuppressWarnings("unchecked")
    public InteractionView view(InteractionRequest request) {
        try {
            Map<String, Object> prompt = json.readValue(request.promptJson(), Map.class);
            List<String> decisions = json.readValue(request.allowedDecisionsJson(), List.class);
            List<String> scopes = json.readValue(request.scopeOptionsJson(), List.class);
            Object response = request.responseJson() == null
                    ? null : json.readValue(request.responseJson(), Object.class);
            return new InteractionView(2, request.interactionId(), request.correlationKey(),
                    request.sessionId(), request.runId(), InteractionRequest.db(request.type()),
                    InteractionRequest.db(request.status()), Map.copyOf(prompt), List.copyOf(decisions),
                    List.copyOf(scopes), response, request.source(), request.childSessionId(),
                    request.deliveryGeneration(), request.dispatchAttempts(), request.createdAt(), request.receivedAt(),
                    request.decisionDeadlineAt(), request.deliveryWindowEndsAt(), request.decidedAt(),
                    request.terminalReason(), request.version(), System.currentTimeMillis());
        } catch (Exception invalidStoredProtocol) {
            throw new IllegalStateException("INTERACTION_PROTOCOL_INVALID", invalidStoredProtocol);
        }
    }
    public InteractionRequest findByCorrelationKey(String runId, String key) {
        List<InteractionRequest> rows = jdbc.query(
                "SELECT * FROM interaction_requests WHERE run_id=? AND correlation_key=?",
                this::map, runId, key);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    public InteractionRequest findById(String id) {
        return jdbc.queryForObject("SELECT * FROM interaction_requests WHERE interaction_id=?", this::map, id);
    }

    public java.util.concurrent.CompletableFuture<InteractionRequest.Status> awaitTerminal(String id) {
        InteractionRequest request = findById(id);
        if (request.status() != InteractionRequest.Status.PENDING) {
            return java.util.concurrent.CompletableFuture.completedFuture(request.status());
        }
        return wakeups.computeIfAbsent(id, ignored -> new java.util.concurrent.CompletableFuture<>());
    }

    public int cancelForRun(String runId, String reason) {
        Instant now=Instant.now();
        List<String> ids=write(()->{
            List<String> pendingIds=jdbc.queryForList(
                    "SELECT interaction_id FROM interaction_requests WHERE run_id=? AND status='pending'",
                    String.class,runId);
            jdbc.update("UPDATE interaction_requests SET status='cancelled',terminal_reason=?,decided_at=?,updated_at=?,version=version+1 WHERE run_id=? AND status='pending'",
                    reason,now.toString(),now.toString(),runId);
            pendingIds.forEach(id->appendTerminalEventInCurrentWrite(findById(id)));
            return pendingIds;
        });
        int changed=ids.size();
        if(changed>0)ids.forEach(id->{
            capacity.release();
            var future=wakeups.remove(id);
            if(future!=null)future.complete(InteractionRequest.Status.CANCELLED);
            events.publishEvent(new InteractionTerminalEvent(findById(id)));
        });
        return changed;
    }

    /**
     * Atomically moves a Run to CANCELLING and terminalizes all of its pending
     * interactions in the same project-database transaction. Process stopping
     * and the final Run terminal remain the caller's responsibility.
     */
    public CancellationResult beginRunCancellation(String runId, String reason) {
        return beginRunTermination(runId, RunEnvelope.RunExitReason.USER_CANCELLED, reason);
    }

    public CancellationResult beginRunTermination(String runId, RunEnvelope.RunExitReason exitReason,
                                                  String reason) {
        record DbResult(RunControlService.TransitionResult transition, List<String> interactionIds) { }
        Instant now = Instant.now();
        DbResult db = write(() -> {
            RunControlService.TransitionResult transition = runs.requestCancel(runId, exitReason);
            if (transition != RunControlService.TransitionResult.APPLIED) {
                return new DbResult(transition, List.of());
            }
            List<String> ids = jdbc.queryForList(
                    "SELECT interaction_id FROM interaction_requests WHERE run_id=? AND status='pending'",
                    String.class, runId);
            if (!ids.isEmpty()) {
                int updated = jdbc.update("""
                        UPDATE interaction_requests SET status='cancelled',terminal_reason=?,decided_at=?,
                          updated_at=?,version=version+1 WHERE run_id=? AND status='pending'
                        """, reason, now.toString(), now.toString(), runId);
                if (updated != ids.size()) {
                    throw new IllegalStateException("INTERACTION_CANCEL_COUNT_MISMATCH");
                }
                ids.forEach(id -> appendTerminalEventInCurrentWrite(findById(id)));
            }
            return new DbResult(transition, ids);
        });
        completeCancelled(db.interactionIds());
        return new CancellationResult(db.transition(), db.interactionIds().size());
    }

    /** Compatibility bulk cancellation, still routed through Run/Interaction DB CAS. */
    public int cancelAll(String reason) {
        List<String> runIds = jdbc.queryForList(
                "SELECT DISTINCT run_id FROM interaction_requests WHERE status='pending'", String.class);
        Integer pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM interaction_requests WHERE status='pending'", Integer.class);
        for (String runId : runIds) {
            events.publishEvent(new com.aicodeassistant.run.RunTerminationRequestedEvent(
                    runId, RunEnvelope.RunExitReason.USER_CANCELLED, reason));
        }
        return pending == null ? 0 : pending;
    }

    private void completeCancelled(List<String> ids) {
        ids.forEach(id -> {
            capacity.release();
            var future = wakeups.remove(id);
            if (future != null) future.complete(InteractionRequest.Status.CANCELLED);
            events.publishEvent(new InteractionTerminalEvent(findById(id)));
        });
    }

    public record CancellationResult(RunControlService.TransitionResult runTransition,
                                     int interactionsCancelled) { }

    /**
     * RunControlService is constructed first and interrupts stale active Runs.
     * Reconcile their pending interactions before accepting new waits, then
     * reserve semaphore permits for any legitimate pending rows that remain.
     */
    @PostConstruct
    void reconcileCapacityAfterRestart() {
        Instant now = Instant.now();
        write(() -> jdbc.update("""
                UPDATE interaction_requests SET status='cancelled',terminal_reason='service_restart',
                  decided_at=?,updated_at=?,version=version+1
                WHERE status='pending' AND run_id IN (
                  SELECT id FROM run_envelopes WHERE status IN ('completed','failed','cancelled','interrupted'))
                """, now.toString(), now.toString()));
        Integer pending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM interaction_requests WHERE status='pending'", Integer.class);
        int count = pending == null ? 0 : pending;
        if (count > MAX_WAITING || !capacity.tryAcquire(count)) {
            throw new IllegalStateException("INTERACTION_CAPACITY_CORRUPT: pending=" + count);
        }
    }

    @Scheduled(fixedRate = 1000)
    public void expireDeadlines() {
        Instant now = Instant.now();
        List<String> undelivered = jdbc.queryForList("""
                SELECT interaction_id FROM interaction_requests WHERE status='pending' AND received_at IS NULL
                AND ((delivery_ack_deadline_at IS NOT NULL AND delivery_ack_deadline_at<=?)
                  OR (delivery_ack_deadline_at IS NULL AND delivery_window_ends_at<=?))
                ORDER BY created_at LIMIT 100
                """, String.class, now.toString(), now.toString());
        undelivered.forEach(id -> expire(id, InteractionRequest.Status.UNDELIVERABLE, "delivery_not_acknowledged"));
        List<String> expired = jdbc.queryForList("""
                SELECT interaction_id FROM interaction_requests
                WHERE status='pending' AND decision_deadline_at<=?
                ORDER BY created_at LIMIT 100
                """, String.class, now.toString());
        expired.forEach(id -> expire(id, InteractionRequest.Status.EXPIRED, "decision_deadline_exceeded"));
    }

    private void expire(String id, InteractionRequest.Status status, String reason) {
        Instant now = Instant.now();
        boolean applied = write(() -> {
            int updated = jdbc.update("UPDATE interaction_requests SET status=?,terminal_reason=?,decided_at=?,updated_at=?,version=version+1 WHERE interaction_id=? AND status='pending'",
                    InteractionRequest.db(status), reason, now.toString(), now.toString(), id);
            if (updated == 1) {
                InteractionRequest request = findById(id);
                appendTerminalEventInCurrentWrite(request);
            }
            return updated == 1;
        });
        if (applied) {
            InteractionRequest request = findById(id);
            finish(request);
            events.publishEvent(new com.aicodeassistant.run.RunTerminationRequestedEvent(
                    request.runId(), RunEnvelope.RunExitReason.INTERACTION_EXPIRED,
                    request.terminalReason()));
        }
    }
    private void finish(InteractionRequest request) {
        capacity.release();
        var future = wakeups.remove(request.interactionId());
        if (future != null) future.complete(request.status());
        events.publishEvent(new InteractionTerminalEvent(request));
    }

    private static void requireRunTransition(RunControlService.TransitionResult result, String operation) {
        if (result != RunControlService.TransitionResult.APPLIED) {
            throw new IllegalStateException("INTERACTION_RUN_TRANSITION_FAILED: " + operation + ": " + result);
        }
    }
    private void appendTerminalEventInCurrentWrite(InteractionRequest request) {
        runs.appendEventInCurrentWrite(request.runId(), "interaction_terminal", null, Map.of(
                "interactionId", request.interactionId(),
                "type", InteractionRequest.db(request.type()),
                "status", InteractionRequest.db(request.status()),
                "terminalReason", request.terminalReason() == null ? "" : request.terminalReason()));
    }
    private <T> T write(java.util.function.Supplier<T> op) {
        return sqlite.executeWrite(dbPath, () -> transaction.execute(s -> op.get()));
    }
    private InteractionRequest map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new InteractionRequest(rs.getString("interaction_id"), rs.getString("correlation_key"),
                rs.getString("session_id"), rs.getString("run_id"),
                InteractionRequest.Type.valueOf(rs.getString("type").toUpperCase()),
                InteractionRequest.Status.valueOf(rs.getString("status").toUpperCase()),
                rs.getString("prompt_json"), rs.getString("allowed_decisions_json"), rs.getString("scope_options_json"),
                rs.getString("response_json"), instant(rs,"created_at"), instant(rs,"delivery_window_ends_at"),
                instant(rs,"first_dispatched_at"), instant(rs,"delivery_ack_deadline_at"), instant(rs,"received_at"),
                instant(rs,"decision_deadline_at"), instant(rs,"decided_at"), rs.getString("terminal_reason"),
                rs.getString("source"), rs.getString("child_session_id"), rs.getInt("delivery_generation"),
                rs.getInt("dispatch_attempts"),
                rs.getString("last_transport_id"), instant(rs,"updated_at"), rs.getLong("version"));
    }
    private static Instant instant(java.sql.ResultSet rs, String name) throws java.sql.SQLException {
        String value=rs.getString(name); return value == null ? null : Instant.parse(value);
    }
}
