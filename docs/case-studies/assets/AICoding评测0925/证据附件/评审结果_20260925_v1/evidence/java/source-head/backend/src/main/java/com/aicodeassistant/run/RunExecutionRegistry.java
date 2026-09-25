package com.aicodeassistant.run;

import com.aicodeassistant.engine.AbortContext;
import com.aicodeassistant.engine.AbortReason;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory ownership and admission authority for currently executing Runs.
 *
 * <p>Every Run-owned asynchronous unit must acquire a {@link WorkLease} before it
 * can create an external resource. Closing admission and cancelling registered
 * leases is atomic with respect to new acquisition, which eliminates the
 * scan-then-register race during termination.</p>
 */
@Component
public class RunExecutionRegistry {
    private static final int MAX_INPUT_CHARS = 16 * 1024;
    private static final int MAX_PENDING_INPUTS = 10;
    private static final int MAX_TOTAL_INPUTS_PER_RUN = 50;

    private final ConcurrentHashMap<String, Execution> byRun = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> runBySession = new ConcurrentHashMap<>();

    public enum InputState {
        QUEUED,
        APPLYING,
        APPLIED,
        REJECTED
    }

    public record SteeringInput(
            String requestId,
            String text,
            long submittedAt,
            /** 客户端提交的通用元数据（如 {"steering": true}），随应用的消息持久化 */
            Map<String, Object> meta
    ) {
        public SteeringInput(String requestId, String text, long submittedAt) {
            this(requestId, text, submittedAt, null);
        }
    }

    public record InputReceipt(
            String requestId,
            String text,
            InputState state,
            String rejectionCode,
            String rejectionMessage,
            long submittedAt,
            Long appliedAt,
            Long rejectedAt
    ) {}

    public record InputOfferResult(
            boolean accepted,
            InputReceipt receipt
    ) {}

    public record CompletionInputDecision(
            boolean sealed,
            List<InputApplication> applications
    ) {
        public CompletionInputDecision {
            applications = applications == null
                    ? List.of() : List.copyOf(applications);
        }

        public boolean hasApplications() {
            return !applications.isEmpty();
        }
    }

    public void register(String runId, String sessionId, AbortContext cancellation) {
        if (runId == null || sessionId == null || cancellation == null)
            throw new IllegalArgumentException("runId, sessionId and cancellation are required");
        Execution execution = new Execution(runId, sessionId, cancellation);
        if (byRun.putIfAbsent(runId, execution) != null)
            throw new IllegalStateException("RUN_EXECUTION_ALREADY_REGISTERED");
        String previous = runBySession.putIfAbsent(sessionId, runId);
        if (previous != null && !previous.equals(runId)) {
            byRun.remove(runId, execution);
            throw new IllegalStateException("SESSION_EXECUTION_ALREADY_REGISTERED");
        }
    }

    public boolean abortRun(String runId, AbortReason reason) {
        Execution execution = byRun.get(runId);
        if (execution == null) return false;
        execution.closeAdmissions(true);
        execution.cancellation.abort(reason);
        return true;
    }

    /** Offers one idempotent instruction to the active Run for a Session. */
    public InputOfferResult offerInputForSession(
            String sessionId, String requestId, String text) {
        return offerInputForSession(sessionId, requestId, text, null);
    }

    /** Offers one idempotent instruction (with optional client metadata) to the active Run for a Session. */
    public InputOfferResult offerInputForSession(
            String sessionId, String requestId, String text,
            Map<String, Object> meta) {
        long now = System.currentTimeMillis();
        if (sessionId == null || sessionId.isBlank()
                || requestId == null || requestId.isBlank()) {
            return rejectedOffer(requestId, text, "INVALID_REQUEST",
                    "Invalid run input request", now);
        }
        try {
            UUID.fromString(requestId);
        } catch (IllegalArgumentException invalidUuid) {
            return rejectedOffer(requestId, text, "INVALID_REQUEST",
                    "requestId must be a UUID", now);
        }
        if (text == null || text.isBlank()) {
            return rejectedOffer(requestId, text, "EMPTY_INPUT",
                    "Input text must not be empty", now);
        }
        if (text.length() > MAX_INPUT_CHARS) {
            return rejectedOffer(requestId, text, "INPUT_TOO_LARGE",
                    "Input text exceeds " + MAX_INPUT_CHARS + " characters", now);
        }
        // Fail fast, before any state is read or mutated: invalid metadata
        // must never reach the receipt/queue writes inside offerInput.
        Map<String, Object> normalizedMeta = validateAndNormalize(meta);

        String runId = runBySession.get(sessionId);
        Execution execution = runId == null ? null : byRun.get(runId);
        if (execution == null) {
            return rejectedOffer(requestId, text, "NO_ACTIVE_RUN",
                    "No active task is running for this session", now);
        }
        return execution.offerInput(requestId, text, now, normalizedMeta);
    }

    public List<InputApplication> claimInputs(String runId, int limit) {
        Execution execution = byRun.get(runId);
        if (execution == null || limit <= 0) return List.of();
        return execution.claimInputs(limit);
    }

    public CompletionInputDecision claimOrSealInputs(String runId, int limit) {
        Execution execution = byRun.get(runId);
        if (execution == null) {
            return new CompletionInputDecision(true, List.of());
        }
        return execution.claimOrSealInputs(limit);
    }

    public List<InputReceipt> sealAndRejectInputs(
            String runId, String rejectionCode) {
        Execution execution = byRun.get(runId);
        if (execution == null) return List.of();
        String code = rejectionCode == null
                ? "RUN_NOT_ACCEPTING_INPUT" : rejectionCode;
        return execution.sealAndRejectInputs(code, rejectionMessage(code));
    }

    private static InputOfferResult rejectedOffer(
            String requestId, String text, String code,
            String message, long now) {
        return new InputOfferResult(false, new InputReceipt(
                requestId, text, InputState.REJECTED, code, message,
                now, null, now));
    }

    /**
     * Validates client-supplied metadata and returns an immutable copy, or
     * null when no metadata was supplied. Null keys or values are rejected
     * up front (Jackson keeps explicit JSON nulls, and Map.copyOf would
     * otherwise fail with an NPE after the receipt was already persisted),
     * so the offer sequence — write receipt, then enqueue — stays atomic.
     */
    private static Map<String, Object> validateAndNormalize(
            Map<String, Object> meta) {
        if (meta == null || meta.isEmpty()) return null;
        for (Map.Entry<String, Object> entry : meta.entrySet()) {
            if (entry.getKey() == null) {
                throw new IllegalArgumentException(
                        "Input metadata keys must not be null");
            }
            if (entry.getValue() == null) {
                throw new IllegalArgumentException(
                        "Input metadata values must not be null (key: "
                                + entry.getKey() + ")");
            }
        }
        return Map.copyOf(meta);
    }

    private static String rejectionMessage(String code) {
        return switch (code == null ? "" : code) {
            case "TURN_LIMIT_REACHED" ->
                    "The task reached its maximum turn limit";
            case "APPLY_FAILED" ->
                    "The queued instruction could not be applied";
            default ->
                    "The task is no longer accepting additional instructions";
        };
    }

    public WorkLease acquireWork(String runId, String kind, String workId) {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("runId is required");
        Execution execution = byRun.get(runId);
        if (execution == null) throw new WorkRejectedException("RUN_EXECUTION_NOT_REGISTERED");
        return execution.acquire(kind, workId);
    }

    /** Closes admission before cancellation scans and requests cancellation of every acquired lease. */
    public boolean beginTermination(String runId) {
        Execution execution = byRun.get(runId);
        return execution != null && execution.closeAdmissions(true);
    }

    /** Closes admission for successful completion without cancelling already completed work. */
    public boolean beginCompletion(String runId) {
        Execution execution = byRun.get(runId);
        return execution != null && execution.closeAdmissions(false);
    }

    public boolean awaitQuiescence(String runId, Duration timeout) {
        Execution execution = byRun.get(runId);
        return execution == null || execution.awaitQuiescence(timeout);
    }

    public boolean isRegistered(String runId) { return byRun.containsKey(runId); }

    public boolean abortSession(String sessionId, AbortReason reason) {
        String runId = runBySession.get(sessionId);
        return runId != null && abortRun(runId, reason);
    }

    public Optional<AbortContext> cancellationForSession(String sessionId) {
        String runId = runBySession.get(sessionId);
        Execution execution = runId == null ? null : byRun.get(runId);
        return Optional.ofNullable(execution == null ? null : execution.cancellation);
    }

    public Optional<String> activeRunForSession(String sessionId) {
        return Optional.ofNullable(runBySession.get(sessionId));
    }

    public void unregister(String runId) {
        Execution execution = byRun.get(runId);
        if (execution != null) {
            execution.requestUnregister();
            if (execution.awaitQuiescence(Duration.ofSeconds(2))) removeExecution(execution);
        }
    }

    private void removeExecution(Execution execution) {
        if (byRun.remove(execution.runId, execution)) {
            runBySession.remove(execution.sessionId, execution.runId);
        }
    }

    public final class WorkLease implements AutoCloseable {
        private final Execution owner;
        private final String token;
        private WorkLease(Execution owner, String token) { this.owner = owner; this.token = token; }
        public void onCancel(Runnable action) { owner.installCancellation(token, action); }
        @Override public void close() { owner.release(token); }
    }

    /** A claimed input is Run-owned work until it reaches a terminal state. */
    public final class InputApplication implements AutoCloseable {
        private final Execution owner;
        private final String workToken;
        private final SteeringInput input;
        private boolean settled;

        private InputApplication(
                Execution owner, String workToken, SteeringInput input) {
            this.owner = owner;
            this.workToken = workToken;
            this.input = input;
        }

        public SteeringInput input() {
            return input;
        }

        public synchronized InputReceipt applyIfAccepting(
                long appliedAt, Runnable applyAction) {
            java.util.Objects.requireNonNull(applyAction, "applyAction");
            if (settled) return owner.receipt(input.requestId());
            InputReceipt receipt = owner.applyInputIfAccepting(
                    workToken, input.requestId(), appliedAt, applyAction);
            settled = true;
            return receipt;
        }

        public synchronized InputReceipt reject(String code) {
            if (settled) return owner.receipt(input.requestId());
            long now = System.currentTimeMillis();
            InputReceipt receipt = owner.settleInput(
                    workToken, input.requestId(), InputState.REJECTED,
                    code, rejectionMessage(code), now);
            settled = true;
            return receipt;
        }

        @Override
        public synchronized void close() {
            if (settled) return;
            long now = System.currentTimeMillis();
            owner.settleInput(
                    workToken, input.requestId(), InputState.REJECTED,
                    "APPLY_FAILED", rejectionMessage("APPLY_FAILED"), now);
            settled = true;
        }
    }

    public static final class WorkRejectedException extends IllegalStateException {
        public WorkRejectedException(String message) { super(message); }
    }

    private final class Execution {
        private final String runId;
        private final String sessionId;
        private final AbortContext cancellation;
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition quiescent = lock.newCondition();
        private final Map<String, Work> work = new HashMap<>();
        private final Deque<String> pendingInputIds = new ArrayDeque<>();
        private final Map<String, InputReceipt> inputReceipts =
                new LinkedHashMap<>();
        /** 客户端随指令提交的元数据，按 requestId 键控；生命周期与 Execution 一致。 */
        private final Map<String, Map<String, Object>> inputMetaById =
                new HashMap<>();
        private boolean admissionsOpen = true;
        private boolean inputAdmissionOpen = true;
        private boolean unregisterRequested;

        private Execution(String runId, String sessionId, AbortContext cancellation) {
            this.runId = runId;
            this.sessionId = sessionId;
            this.cancellation = cancellation;
        }

        private WorkLease acquire(String kind, String workId) {
            lock.lock();
            try {
                if (!admissionsOpen) throw new WorkRejectedException("RUN_WORK_ADMISSION_CLOSED");
                String token = UUID.randomUUID().toString();
                work.put(token, new Work(kind, workId));
                return new WorkLease(this, token);
            } finally { lock.unlock(); }
        }

        private InputOfferResult offerInput(
                String requestId, String text, long submittedAt,
                Map<String, Object> normalizedMeta) {
            // normalizedMeta was already validated and made immutable by
            // validateAndNormalize before any state was touched, so the
            // receipt write and the enqueue below cannot fail halfway.
            lock.lock();
            try {
                InputReceipt existing = inputReceipts.get(requestId);
                if (existing != null) {
                    return new InputOfferResult(
                            existing.state() != InputState.REJECTED, existing);
                }
                if (!inputAdmissionOpen || cancellation.isAborted()
                        || unregisterRequested) {
                    return rejectedOffer(requestId, text,
                            "RUN_NOT_ACCEPTING_INPUT",
                            "The task is no longer accepting additional instructions",
                            submittedAt);
                }
                if (pendingInputIds.size() >= MAX_PENDING_INPUTS) {
                    return rejectedOffer(requestId, text, "QUEUE_FULL",
                            "Too many instructions are already queued", submittedAt);
                }
                if (inputReceipts.size() >= MAX_TOTAL_INPUTS_PER_RUN) {
                    return rejectedOffer(requestId, text, "INPUT_LIMIT_REACHED",
                            "The task reached its run input limit", submittedAt);
                }

                InputReceipt receipt = new InputReceipt(
                        requestId, text, InputState.QUEUED,
                        null, null, submittedAt, null, null);
                inputReceipts.put(requestId, receipt);
                if (normalizedMeta != null) {
                    inputMetaById.put(requestId, normalizedMeta);
                }
                pendingInputIds.addLast(requestId);
                return new InputOfferResult(true, receipt);
            } finally {
                lock.unlock();
            }
        }

        private List<InputApplication> claimInputs(int limit) {
            lock.lock();
            try {
                return claimInputsLocked(limit);
            } finally {
                lock.unlock();
            }
        }

        private List<InputApplication> claimInputsLocked(int limit) {
            if (limit <= 0 || !admissionsOpen || !inputAdmissionOpen
                    || cancellation.isAborted() || unregisterRequested) {
                return List.of();
            }
            List<InputApplication> applications = new ArrayList<>();
            while (applications.size() < limit
                    && !pendingInputIds.isEmpty()) {
                String requestId = pendingInputIds.removeFirst();
                InputReceipt current = inputReceipts.get(requestId);
                if (current == null || current.state() != InputState.QUEUED) {
                    continue;
                }
                InputReceipt applying = new InputReceipt(
                        current.requestId(), current.text(), InputState.APPLYING,
                        null, null, current.submittedAt(), null, null);
                inputReceipts.put(requestId, applying);

                String workToken = UUID.randomUUID().toString();
                work.put(workToken, new Work("run_input", requestId));
                applications.add(new InputApplication(
                        this, workToken,
                        new SteeringInput(requestId, current.text(),
                                current.submittedAt(),
                                inputMetaById.get(requestId))));
            }
            return List.copyOf(applications);
        }

        private CompletionInputDecision claimOrSealInputs(int limit) {
            lock.lock();
            try {
                if (!inputAdmissionOpen || cancellation.isAborted()
                        || unregisterRequested) {
                    return new CompletionInputDecision(true, List.of());
                }
                List<InputApplication> applications = claimInputsLocked(limit);
                if (!applications.isEmpty()) {
                    return new CompletionInputDecision(false, applications);
                }
                inputAdmissionOpen = false;
                return new CompletionInputDecision(true, List.of());
            } finally {
                lock.unlock();
            }
        }

        private List<InputReceipt> sealAndRejectInputs(
                String code, String message) {
            lock.lock();
            try {
                inputAdmissionOpen = false;
                List<InputReceipt> rejected = new ArrayList<>();
                while (!pendingInputIds.isEmpty()) {
                    String requestId = pendingInputIds.removeFirst();
                    InputReceipt current = inputReceipts.get(requestId);
                    if (current == null
                            || current.state() != InputState.QUEUED) {
                        continue;
                    }
                    long now = System.currentTimeMillis();
                    InputReceipt terminal = new InputReceipt(
                            current.requestId(), current.text(),
                            InputState.REJECTED, code, message,
                            current.submittedAt(), null, now);
                    inputReceipts.put(requestId, terminal);
                    rejected.add(terminal);
                }
                return List.copyOf(rejected);
            } finally {
                lock.unlock();
            }
        }

        private InputReceipt settleInput(
                String workToken, String requestId,
                InputState terminalState, String rejectionCode,
                String rejectionMessage, long terminalAt) {
            boolean removeAfterRelease = false;
            lock.lock();
            try {
                InputReceipt current = inputReceipts.get(requestId);
                if (current == null
                        || current.state() != InputState.APPLYING) {
                    throw new IllegalStateException("RUN_INPUT_NOT_APPLYING");
                }
                InputReceipt terminal = new InputReceipt(
                        current.requestId(), current.text(), terminalState,
                        rejectionCode, rejectionMessage, current.submittedAt(),
                        terminalState == InputState.APPLIED ? terminalAt : null,
                        terminalState == InputState.REJECTED ? terminalAt : null);
                inputReceipts.put(requestId, terminal);
                if (work.remove(workToken) != null && work.isEmpty()) {
                    quiescent.signalAll();
                    removeAfterRelease = unregisterRequested;
                }
                return terminal;
            } finally {
                lock.unlock();
                if (removeAfterRelease) removeExecution(this);
            }
        }

        private InputReceipt applyInputIfAccepting(
                String workToken, String requestId, long appliedAt,
                Runnable applyAction) {
            boolean removeAfterRelease = false;
            lock.lock();
            try {
                InputReceipt current = inputReceipts.get(requestId);
                if (current == null
                        || current.state() != InputState.APPLYING) {
                    throw new IllegalStateException("RUN_INPUT_NOT_APPLYING");
                }
                Work inputWork = work.get(workToken);
                if (inputWork == null) {
                    throw new IllegalStateException("RUN_INPUT_WORK_NOT_FOUND");
                }

                final InputReceipt terminal;
                if (!admissionsOpen || !inputAdmissionOpen
                        || cancellation.isAborted()
                        || unregisterRequested
                        || inputWork.cancelRequested) {
                    long rejectedAt = System.currentTimeMillis();
                    terminal = new InputReceipt(
                            current.requestId(), current.text(),
                            InputState.REJECTED,
                            "RUN_NOT_ACCEPTING_INPUT",
                            rejectionMessage("RUN_NOT_ACCEPTING_INPUT"),
                            current.submittedAt(), null, rejectedAt);
                } else {
                    applyAction.run();
                    terminal = new InputReceipt(
                            current.requestId(), current.text(),
                            InputState.APPLIED, null, null,
                            current.submittedAt(), appliedAt, null);
                }
                inputReceipts.put(requestId, terminal);
                if (work.remove(workToken) != null && work.isEmpty()) {
                    quiescent.signalAll();
                    removeAfterRelease = unregisterRequested;
                }
                return terminal;
            } finally {
                lock.unlock();
                if (removeAfterRelease) removeExecution(this);
            }
        }

        private InputReceipt receipt(String requestId) {
            lock.lock();
            try {
                return inputReceipts.get(requestId);
            } finally {
                lock.unlock();
            }
        }

        private boolean closeAdmissions(boolean cancel) {
            ArrayList<Runnable> callbacks = new ArrayList<>();
            boolean changed;
            lock.lock();
            try {
                changed = admissionsOpen || inputAdmissionOpen;
                admissionsOpen = false;
                inputAdmissionOpen = false;
                if (cancel) {
                    for (Work item : work.values()) {
                        item.cancelRequested = true;
                        if (item.cancellation != null && !item.cancellationInvoked) {
                            item.cancellationInvoked = true;
                            callbacks.add(item.cancellation);
                        }
                    }
                }
                if (work.isEmpty()) quiescent.signalAll();
            } finally { lock.unlock(); }
            callbacks.forEach(callback -> {
                try { callback.run(); }
                catch (RuntimeException ignored) { /* cancellation remains best-effort; owner confirms termination */ }
            });
            return changed;
        }

        private void installCancellation(String token, Runnable action) {
            java.util.Objects.requireNonNull(action, "action");
            boolean invoke = false;
            lock.lock();
            try {
                Work item = work.get(token);
                if (item == null) return;
                if (item.cancellation != null) throw new IllegalStateException("WORK_CANCELLATION_ALREADY_REGISTERED");
                item.cancellation = action;
                if (item.cancelRequested && !item.cancellationInvoked) {
                    item.cancellationInvoked = true;
                    invoke = true;
                }
            } finally { lock.unlock(); }
            if (invoke) action.run();
        }

        private void release(String token) {
            boolean removeAfterRelease = false;
            lock.lock();
            try {
                if (work.remove(token) != null && work.isEmpty()) {
                    quiescent.signalAll();
                    removeAfterRelease = unregisterRequested;
                }
            } finally { lock.unlock(); }
            if (removeAfterRelease) removeExecution(this);
        }

        private void requestUnregister() {
            lock.lock();
            try {
                unregisterRequested = true;
            } finally { lock.unlock(); }
            closeAdmissions(true);
            sealAndRejectInputs(
                    "RUN_NOT_ACCEPTING_INPUT",
                    "The task is no longer accepting additional instructions");
        }

        private boolean awaitQuiescence(Duration timeout) {
            long nanos = timeout.toNanos();
            lock.lock();
            try {
                while (!work.isEmpty()) {
                    if (nanos <= 0) return false;
                    try { nanos = quiescent.awaitNanos(nanos); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
                }
                return true;
            } finally { lock.unlock(); }
        }
    }

    private static final class Work {
        private final String kind;
        private final String workId;
        private Runnable cancellation;
        private boolean cancelRequested;
        private boolean cancellationInvoked;
        private Work(String kind, String workId) { this.kind = kind; this.workId = workId; }
    }
}
