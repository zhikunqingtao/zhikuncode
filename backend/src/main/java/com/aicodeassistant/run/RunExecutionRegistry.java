package com.aicodeassistant.run;

import com.aicodeassistant.engine.AbortContext;
import com.aicodeassistant.engine.AbortReason;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
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
    private final ConcurrentHashMap<String, Execution> byRun = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> runBySession = new ConcurrentHashMap<>();

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
        execution.cancellation.abort(reason);
        return true;
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
        private boolean admissionsOpen = true;
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

        private boolean closeAdmissions(boolean cancel) {
            ArrayList<Runnable> callbacks = new ArrayList<>();
            boolean changed;
            lock.lock();
            try {
                changed = admissionsOpen;
                admissionsOpen = false;
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
