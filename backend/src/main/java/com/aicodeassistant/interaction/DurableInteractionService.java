package com.aicodeassistant.interaction;

import com.aicodeassistant.config.database.DatabaseResolver;
import com.aicodeassistant.config.database.SqliteConfig;
import com.aicodeassistant.run.RunControlService;
import com.aicodeassistant.run.RunEnvelope;
import com.aicodeassistant.authorization.AuthorizationInteractionContext;
import com.aicodeassistant.authorization.PermissionGrantRepository;
import com.aicodeassistant.model.PermissionScope;
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
import org.springframework.dao.DataIntegrityViolationException;

import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/** 基于数据库 CAS 的交互唯一权威；内存 Future 只负责唤醒，不参与裁决。 */
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
    private final PermissionGrantRepository grants;
    private final Semaphore capacity = new Semaphore(MAX_WAITING);
    private final Map<String, java.util.concurrent.CompletableFuture<InteractionRequest.Status>> wakeups = new ConcurrentHashMap<>();

    /** 服务端交互构造或持久化失败；code 可直接映射为结构化 ToolResult。 */
    public static final class InteractionOperationException extends RuntimeException {
        private final String code;
        public InteractionOperationException(String code, Throwable cause) {
            super(code, cause);
            this.code = code;
        }
        public String code() { return code; }
    }

    public DurableInteractionService(@Qualifier("projectJdbcTemplate") JdbcTemplate jdbc,
            SqliteConfig sqlite, DatabaseResolver resolver,
            @Qualifier("projectTransactionManager") PlatformTransactionManager txManager,
            ObjectMapper json, RunControlService runs, ApplicationEventPublisher events,
            PermissionGrantRepository grants) {
        this.jdbc = jdbc; this.sqlite = sqlite;
        this.dbPath = resolver.getProjectDbPath(Path.of(System.getProperty("user.dir")));
        this.transaction = new TransactionTemplate(txManager); this.json = json; this.runs = runs;
        this.events = events; this.grants = grants;
    }

    public InteractionRequest create(String correlationKey, String sessionId, String runId,
            InteractionRequest.Type type, Object prompt, List<String> decisions,
            List<String> scopes, String source, String childSessionId) {
        return createInternal(correlationKey, sessionId, runId, type, prompt, decisions, scopes,
                source, childSessionId, null);
    }

    public InteractionRequest createAuthorization(String correlationKey, String sessionId, String runId,
            Object prompt, List<String> decisions, List<String> scopes,
            String source, AuthorizationInteractionContext authorizationContext) {
        if (authorizationContext == null || authorizationContext.protocolVersion() != 3) {
            throw new IllegalArgumentException("PERMISSION_PROTOCOL_MISMATCH");
        }
        return createInternal(correlationKey, sessionId, runId, InteractionRequest.Type.PERMISSION,
                prompt, decisions, scopes, source, null, authorizationContext);
    }

    private InteractionRequest createInternal(String correlationKey, String sessionId, String runId,
            InteractionRequest.Type type, Object prompt, List<String> decisions,
            List<String> scopes, String source, String childSessionId,
            AuthorizationInteractionContext authorizationContext) {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("INTERACTION_REQUIRES_RUN");
        if (!capacity.tryAcquire()) {
            log.warn("Interaction capacity exhausted: runId={}, sessionId={}, type={}, limit={}",
                    runId, sessionId, type, MAX_WAITING);
            safePublish(new com.aicodeassistant.run.RunTerminationRequestedEvent(
                    runId, RunEnvelope.RunExitReason.INTERACTION_CAPACITY_EXCEEDED,
                    "Maximum pending interactions reached"), "run-termination-requested", runId);
            throw new IllegalStateException("INTERACTION_CAPACITY_EXCEEDED");
        }
        Instant now = Instant.now();
        String id = UUID.randomUUID().toString();
        final String promptJson;
        final String decisionsJson;
        final String scopesJson;
        final String authorizationJson;
        try {
            promptJson = json.writeValueAsString(prompt);
            decisionsJson = json.writeValueAsString(decisions);
            scopesJson = json.writeValueAsString(scopes);
            authorizationJson = authorizationContext == null ? null : json.writeValueAsString(authorizationContext);
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalidPayload) {
            capacity.release();
            throw new InteractionOperationException("INTERACTION_PAYLOAD_INVALID", invalidPayload);
        }
        try {
            write(() -> {
                RunControlService.TransitionResult waiting = ensureRunWaitingInCurrentWrite(
                        runId, InteractionRequest.db(type));
                if (waiting != RunControlService.TransitionResult.APPLIED) {
                    throw new IllegalStateException("INTERACTION_RUN_NOT_RUNNING: " + waiting);
                }
                int inserted = jdbc.update("""
                    INSERT INTO interaction_requests(interaction_id,correlation_key,session_id,run_id,type,status,
                      prompt_json,allowed_decisions_json,scope_options_json,created_at,delivery_window_ends_at,
                      source,child_session_id,updated_at,authorization_context_json)
                      VALUES(?,?,?,?,?,'pending',?,?,?,?,?,?,?,?,?)
                    """, id, correlationKey, sessionId, runId, InteractionRequest.db(type), promptJson,
                    decisionsJson, scopesJson, now.toString(), now.plusSeconds(DELIVERY_WINDOW_SECONDS).toString(),
                    source == null ? "direct" : source, childSessionId, now.toString(), authorizationJson);
                Map<String, Object> created = new java.util.LinkedHashMap<>();
                created.put("interactionId", id);
                created.put("type", InteractionRequest.db(type));
                created.put("status", "pending");
                if (authorizationContext != null) {
                    created.putAll(com.aicodeassistant.authorization.AuthorizationDiagnostic.payload(
                            authorizationContext.subject().toSubject(), authorizationContext.operation(),
                            com.aicodeassistant.tool.ToolUseContext.of(
                                    authorizationContext.subject().authorizationRoot(), sessionId)
                                    .withCurrentRunId(authorizationContext.subject().currentRunId())
                                    .withToolUseId(authorizationContext.toolUseId()),
                            authorizationContext.executionAttemptId(),
                            com.aicodeassistant.authorization.AuthorizationDiagnostic.Outcome.ASK,
                            com.aicodeassistant.authorization.AuthorizationDiagnostic.EvaluationStage.INTERACTION,
                            com.aicodeassistant.authorization.AuthorizationDiagnostic.Source.POLICY,
                            "USER_DECISION_REQUIRED", null, null, id));
                }
                runs.appendEventInCurrentWrite(runId, "interaction_created", null, Map.copyOf(created));
                return inserted;
            });
        } catch (DataIntegrityViolationException duplicate) {
            // 并行分支可能同时请求同一逻辑工具授权。数据库唯一键是权威，竞争失败方复用已有交互，
            // 避免重复弹窗或将唯一键竞争误报成请求内容错误。
            InteractionRequest existing = findByCorrelationKey(runId, correlationKey);
            if (existing != null) {
                capacity.release();
                log.debug("Joined existing interaction after unique-key race: runId={}, correlationKey={}, interactionId={}",
                        runId, correlationKey, existing.interactionId());
                return existing;
            }
            capacity.release();
            throw new InteractionOperationException("INTERACTION_STORE_FAILED", duplicate);
        } catch (com.aicodeassistant.config.database.SqliteConfig.DatabaseWriteUnavailableException unavailable) {
            InteractionRequest existing = findAfterCreateFailure(runId, correlationKey, unavailable);
            if (existing != null) {
                if (id.equals(existing.interactionId())) return finishCreatedInteraction(id);
                capacity.release();
                log.debug("Joined existing interaction after database contention: runId={}, correlationKey={}, interactionId={}",
                        runId, correlationKey, existing.interactionId());
                return existing;
            }
            capacity.release();
            throw unavailable;
        } catch (IllegalStateException state) {
            capacity.release();
            if (state.getMessage() != null && state.getMessage().startsWith("INTERACTION_RUN_NOT_RUNNING")) {
                throw new InteractionOperationException("INTERACTION_RUN_NOT_RUNNING", state);
            }
            throw state;
        } catch (org.springframework.dao.DataAccessException storeFailure) {
            InteractionRequest existing = findAfterCreateFailure(runId, correlationKey, storeFailure);
            if (existing != null) {
                if (id.equals(existing.interactionId())) return finishCreatedInteraction(id);
                capacity.release();
                return existing;
            }
            capacity.release();
            throw new InteractionOperationException("INTERACTION_STORE_FAILED", storeFailure);
        } catch (RuntimeException unexpected) {
            capacity.release();
            throw unexpected;
        }
        return finishCreatedInteraction(id);
    }

    private InteractionRequest findAfterCreateFailure(String runId, String correlationKey, RuntimeException failure) {
        try {
            return findByCorrelationKey(runId, correlationKey);
        } catch (RuntimeException lookupFailure) {
            failure.addSuppressed(lookupFailure);
            return null;
        }
    }

    private InteractionRequest finishCreatedInteraction(String id) {
        wakeups.put(id, new java.util.concurrent.CompletableFuture<>());
        InteractionRequest created = findById(id);
        safePublish(new InteractionCreatedEvent(created), "interaction-created", id);
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
     * 原子领取一次计划重投。expectedAttempts 防止重连恢复与调度器重复发送同一轮重试。
     * 首次投递计为第 1 次，随后按 1、2、4 秒退避，最多执行到第 4 次。
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
            log.warn("ACK rejected without valid deliveryGeneration: interactionId={}", id);
            return false;
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

    /** 重连投递：仅在尚未收到 ACK 时递增投递代次，防止旧页面确认新请求。 */
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
        // 交互终态、可选持久授权与 Run 恢复必须在同一个项目库事务中完成。
        // 任一步失败都回滚，避免出现“前端显示已允许，但授权或 Run 状态未落库”的分裂状态。
        boolean applied = write(() -> {
            InteractionRequest before = findById(id);
            if (before.status() != InteractionRequest.Status.PENDING || before.version() != expectedVersion) return false;
            AuthorizationInteractionContext authorization = null;
            Map<String, Object> permissionResponse = null;
            if (before.type() == InteractionRequest.Type.PERMISSION) {
                try {
                    permissionResponse = responseJson == null ? Map.of()
                            : json.readValue(responseJson, new com.fasterxml.jackson.core.type.TypeReference<>() { });
                    String stored = jdbc.queryForObject(
                            "SELECT authorization_context_json FROM interaction_requests WHERE interaction_id=?",
                            String.class, id);
                    if (stored == null) throw new IllegalStateException("PERMISSION_PROTOCOL_MISMATCH");
                    authorization = json.readValue(stored, AuthorizationInteractionContext.class);
                    if (authorization.protocolVersion() != 3
                            || !authorization.operationHash().equals(permissionResponse.get("operationHash"))) {
                        throw new IllegalArgumentException("PERMISSION_PROTOCOL_MISMATCH");
                    }
                    if (!java.util.Objects.equals(before.deliveryGeneration(),
                            ((Number) permissionResponse.getOrDefault("deliveryGeneration", -1)).intValue())
                            || before.receivedAt() == null) {
                        throw new IllegalArgumentException("PERMISSION_DELIVERY_STALE");
                    }
                    String optionId = String.valueOf(permissionResponse.get("optionId"));
                    var selected = authorization.options().stream()
                            .filter(option -> option.optionId().equals(optionId)).findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("PERMISSION_OPTION_NOT_ALLOWED"));
                    boolean selectedRemember = "allow".equals(selected.decision()) && !"once".equals(selected.scope());
                    if (!selected.decision().equals(permissionResponse.get("decision"))
                            || !selected.scope().equals(permissionResponse.get("scope"))
                            || selectedRemember != Boolean.TRUE.equals(permissionResponse.get("remember"))) {
                        throw new IllegalArgumentException("PERMISSION_OPTION_MISMATCH");
                    }
                } catch (RuntimeException protocol) { throw protocol; }
                catch (Exception protocol) { throw new IllegalArgumentException("PERMISSION_PROTOCOL_MISMATCH", protocol); }
            }
            int updated = jdbc.update("""
                UPDATE interaction_requests SET status=?,response_json=?,decided_at=?,terminal_reason=?,
                  updated_at=?,version=version+1 WHERE interaction_id=? AND status='pending' AND version=?
                """, InteractionRequest.db(terminal), responseJson, now.toString(), reason,
                now.toString(), id, expectedVersion);
            if (updated == 1) {
                InteractionRequest request = findById(id);
                if (authorization != null && terminal == InteractionRequest.Status.ANSWERED
                        && Boolean.TRUE.equals(permissionResponse.get("remember"))) {
                    PermissionScope scope;
                    try { scope = PermissionScope.valueOf(String.valueOf(permissionResponse.get("scope")).toUpperCase()); }
                    catch (Exception invalid) { throw new IllegalArgumentException("PERMISSION_SCOPE_NOT_ALLOWED"); }
                    String selectedScope = null;
                    String selectedOptionId = String.valueOf(permissionResponse.get("optionId"));
                    for (AuthorizationInteractionContext.DecisionOption option : authorization.options()) {
                        if (option.optionId().equals(selectedOptionId)) {
                            selectedScope = option.scope();
                            break;
                        }
                    }
                    if (selectedScope == null)
                        throw new IllegalArgumentException("PERMISSION_OPTION_NOT_ALLOWED");
                    if (!scope.name().equalsIgnoreCase(selectedScope))
                        throw new IllegalArgumentException("PERMISSION_SCOPE_NOT_ALLOWED");
                    String grantId = grants.createInCurrentTransaction(authorization.subject().toSubject(),
                            authorization.operation(), scope, id);
                    if (grantId != null) {
                        runs.appendEventInCurrentWrite(request.runId(), "permission_grant_created", null, Map.of(
                                "interactionId", id, "grantId", grantId, "operationHash", authorization.operationHash()));
                    }
                }
                if (authorization != null) {
                    runs.appendEventInCurrentWrite(request.runId(), "permission_resolved", null, Map.of(
                            "interactionId", id,
                            "outcome", InteractionRequest.db(terminal),
                            "reasonCode", reason == null ? "" : reason,
                            "inputHash", authorization.inputHash(),
                            "operationHash", authorization.operationHash()));
                }
                appendTerminalEventInCurrentWrite(request);
                Integer remaining = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM interaction_requests WHERE run_id=? AND status='pending'",
                        Integer.class, request.runId());
                if (remaining == null || remaining == 0) {
                    requireRunTransition(runs.markRunningInCurrentWrite(request.runId()),
                            "resume after final interaction decision");
                }
            }
            return updated == 1;
        });
        InteractionRequest current = findById(id);
        if (applied) {
            log.info("Interaction decided: interactionId={}, runId={}, type={}, status={}",
                    id, current.runId(), current.type(), current.status());
            finish(current);
        } else {
            log.debug("Interaction decision lost CAS race: interactionId={}, expectedVersion={}, currentVersion={}, status={}",
                    id, expectedVersion, current.version(), current.status());
        }
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
            String operationHash = null;
            String actorRunId = null;
            String actorType = null;
            List<Map<String, String>> options = List.of();
            int protocol = 2;
            if (request.type() == InteractionRequest.Type.PERMISSION) {
                String stored = jdbc.queryForObject(
                        "SELECT authorization_context_json FROM interaction_requests WHERE interaction_id=?",
                        String.class, request.interactionId());
                if (stored == null) throw new IllegalStateException("PERMISSION_PROTOCOL_MISMATCH");
                AuthorizationInteractionContext authorization =
                        json.readValue(stored, AuthorizationInteractionContext.class);
                if (authorization.protocolVersion() != AuthorizationInteractionContext.PROTOCOL_VERSION) {
                    throw new IllegalStateException("PERMISSION_PROTOCOL_MISMATCH");
                }
                protocol = authorization.protocolVersion();
                operationHash = authorization.operationHash();
                actorRunId = authorization.subject().currentRunId();
                actorType = authorization.subject().rootRunId().equals(actorRunId)
                        ? "direct" : "descendant";
                options = authorization.options().stream().map(option -> Map.of(
                        "optionId", option.optionId(), "decision", option.decision(),
                        "scope", option.scope())).toList();
            }
            return new InteractionView(protocol, request.interactionId(), request.correlationKey(),
                    request.sessionId(), request.runId(), InteractionRequest.db(request.type()),
                    InteractionRequest.db(request.status()), Map.copyOf(prompt), List.copyOf(decisions),
                    List.copyOf(scopes), response, actorType == null ? request.source() : actorType,
                    request.childSessionId(), actorRunId, actorType,
                    request.deliveryGeneration(), request.dispatchAttempts(), request.createdAt(), request.receivedAt(),
                    request.decisionDeadlineAt(), request.deliveryWindowEndsAt(), request.decidedAt(),
                    request.terminalReason(), request.version(), System.currentTimeMillis(), operationHash, options);
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

    /** ONCE 授权的最终权威复检；必须在 Gateway 的有界写事务中调用。 */
    public void requireAnsweredOnce(String interactionId,
                                    com.aicodeassistant.authorization.AuthorizationSubject subject,
                                    com.aicodeassistant.authorization.OperationDescriptor operation,
                                    String toolUseId) {
        if (interactionId == null) {
            throw new IllegalStateException("PERMISSION_ONCE_DECISION_MISSING");
        }
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT status,run_id,response_json,authorization_context_json
                FROM interaction_requests WHERE interaction_id=?
                """, interactionId);
        if (!"answered".equals(String.valueOf(row.get("status")))
                || !subject.currentRunId().equals(String.valueOf(row.get("run_id")))) {
            throw new IllegalStateException("PERMISSION_ONCE_DECISION_STALE");
        }
        try {
            AuthorizationInteractionContext stored = json.readValue(
                    String.valueOf(row.get("authorization_context_json")), AuthorizationInteractionContext.class);
            Map<String, Object> response = json.readValue(String.valueOf(row.get("response_json")),
                    new com.fasterxml.jackson.core.type.TypeReference<>() { });
            var option = stored.options().stream()
                    .filter(candidate -> candidate.optionId().equals(String.valueOf(response.get("optionId"))))
                    .findFirst().orElseThrow();
            boolean exactSubject = stored.subject().rootSessionId().equals(subject.rootSessionId())
                    && stored.subject().rootRunId().equals(subject.rootRunId())
                    && stored.subject().currentRunId().equals(subject.currentRunId())
                    && stored.subject().workspaceKey().equals(subject.workspaceKey());
            if (stored.protocolVersion() != AuthorizationInteractionContext.PROTOCOL_VERSION
                    || !stored.toolUseId().equals(toolUseId)
                    || !stored.inputHash().equals(operation.inputHash())
                    || !stored.operationHash().equals(operation.operationHash())
                    || !exactSubject
                    || !"allow".equals(option.decision()) || !"once".equals(option.scope())
                    || !stored.operationHash().equals(response.get("operationHash"))) {
                throw new IllegalStateException("PERMISSION_ONCE_DECISION_STALE");
            }
        } catch (IllegalStateException stale) {
            throw stale;
        } catch (Exception invalid) {
            throw new IllegalStateException("PERMISSION_ONCE_DECISION_INVALID", invalid);
        }
    }

    public java.util.concurrent.CompletableFuture<InteractionRequest.Status> awaitTerminal(String id) {
        InteractionRequest before = findById(id);
        if (before.status() != InteractionRequest.Status.PENDING) {
            return java.util.concurrent.CompletableFuture.completedFuture(before.status());
        }
        var terminal = new java.util.concurrent.CompletableFuture<InteractionRequest.Status>();
        var signal = wakeups.computeIfAbsent(id, ignored -> new java.util.concurrent.CompletableFuture<>());
        signal.whenComplete((status, failure) -> {
            if (failure != null) terminal.completeExceptionally(failure);
            else if (status != InteractionRequest.Status.PENDING) terminal.complete(status);
        });
        // 二次读取闭合“首次读取/注册唤醒器”之间的终态竞争，并清理竞争中创建的孤立唤醒器。
        InteractionRequest afterRegistration = findById(id);
        if (afterRegistration.status() != InteractionRequest.Status.PENDING) {
            wakeups.remove(id, signal);
            signal.complete(afterRegistration.status());
            terminal.complete(afterRegistration.status());
            return terminal;
        }
        waitForCurrentDeadline(id, terminal);
        return terminal;
    }

    /**
     * 仅数据库终态可以结束等待。投递 ACK 会把短投递期限切换为用户决策期限，
     * 因此旧期限唤醒后若状态仍为 PENDING，必须按数据库中的最新期限继续等待。
     */
    private void waitForCurrentDeadline(String id,
            java.util.concurrent.CompletableFuture<InteractionRequest.Status> terminal) {
        if (terminal.isDone()) return;
        final InteractionRequest current;
        try {
            current = findById(id);
        } catch (RuntimeException lookupFailure) {
            terminal.completeExceptionally(lookupFailure);
            return;
        }
        if (current.status() != InteractionRequest.Status.PENDING) {
            terminal.complete(current.status());
            return;
        }
        Instant deadline = current.receivedAt() == null
                ? (current.deliveryAckDeadlineAt() == null
                    ? current.deliveryWindowEndsAt() : current.deliveryAckDeadlineAt())
                : current.decisionDeadlineAt();
        if (deadline == null) {
            terminal.completeExceptionally(new IllegalStateException("INTERACTION_DEADLINE_MISSING"));
            return;
        }
        long delayMillis = Math.max(0, Duration.between(Instant.now(), deadline.plusSeconds(2)).toMillis());
        java.util.concurrent.CompletableFuture.delayedExecutor(delayMillis,
                java.util.concurrent.TimeUnit.MILLISECONDS).execute(() -> {
            if (terminal.isDone()) return;
            try {
                expireIfDue(id);
                InteractionRequest afterDeadline = findById(id);
                if (afterDeadline.status() != InteractionRequest.Status.PENDING) {
                    terminal.complete(afterDeadline.status());
                } else {
                    waitForCurrentDeadline(id, terminal);
                }
            } catch (RuntimeException deadlineFailure) {
                terminal.completeExceptionally(deadlineFailure);
            }
        });
    }

    private void expireIfDue(String id) {
        InteractionRequest current = findById(id);
        if (current.status() != InteractionRequest.Status.PENDING) return;
        Instant now = Instant.now();
        if (current.receivedAt() == null) {
            Instant deadline = current.deliveryAckDeadlineAt() == null
                    ? current.deliveryWindowEndsAt() : current.deliveryAckDeadlineAt();
            if (deadline != null && !deadline.isAfter(now)) {
                expire(id, InteractionRequest.Status.UNDELIVERABLE, "delivery_not_acknowledged");
            }
        } else if (current.decisionDeadlineAt() != null
                && !current.decisionDeadlineAt().isAfter(now)) {
            expire(id, InteractionRequest.Status.EXPIRED, "decision_deadline_exceeded");
        }
    }

    /**
     * 在同一个项目库事务内将 Run 原子切换到 CANCELLING，并终结其全部待决交互。
     * 进程停止和 Run 最终终态仍由调用方负责。
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

    private void completeCancelled(List<String> ids) {
        ids.forEach(id -> {
            capacity.release();
            var future = wakeups.remove(id);
            if (future != null) future.complete(InteractionRequest.Status.CANCELLED);
            safePublish(new InteractionTerminalEvent(findById(id)), "interaction-terminal", id);
        });
    }

    public record CancellationResult(RunControlService.TransitionResult runTransition,
                                     int interactionsCancelled) { }

    /**
     * RunControlService 会先终止重启前遗留的活动 Run。接收新等待前先核对其交互，
     * 再为仍然有效的待决记录预留信号量配额。
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
        log.info("Interaction capacity reconciled after restart: pending={}, available={}",
                count, capacity.availablePermits());
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
            log.info("Interaction deadline reached: interactionId={}, runId={}, status={}, reason={}",
                    id, request.runId(), status, reason);
            finish(request);
            safePublish(new com.aicodeassistant.run.RunTerminationRequestedEvent(
                    request.runId(), RunEnvelope.RunExitReason.INTERACTION_EXPIRED,
                    request.terminalReason()), "run-termination-requested", request.interactionId());
        }
    }
    private void finish(InteractionRequest request) {
        capacity.release();
        var future = wakeups.remove(request.interactionId());
        if (future != null) future.complete(request.status());
        safePublish(new InteractionTerminalEvent(request), "interaction-terminal", request.interactionId());
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
    private RunControlService.TransitionResult ensureRunWaitingInCurrentWrite(String runId, String reason) {
        List<String> status = jdbc.query("SELECT status FROM run_envelopes WHERE id=?",
                (rs, row) -> rs.getString(1), runId);
        if (status.isEmpty()) return RunControlService.TransitionResult.NOT_FOUND;
        if ("waiting_interaction".equals(status.getFirst())) return RunControlService.TransitionResult.APPLIED;
        return runs.markWaitingInCurrentWrite(runId, reason);
    }

    private void safePublish(Object event, String eventType, String interactionId) {
        try {
            events.publishEvent(event);
        } catch (RuntimeException deliveryFailure) {
            log.error("After-commit event delivery failed: type={}, interactionId={}",
                    eventType, interactionId, deliveryFailure);
        }
    }

    private <T> T write(java.util.function.Supplier<T> op) {
        return sqlite.executeWriteBounded(dbPath, Duration.ofSeconds(5),
                () -> transaction.execute(s -> op.get()));
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
