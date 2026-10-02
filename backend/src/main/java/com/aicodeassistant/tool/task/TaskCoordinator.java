package com.aicodeassistant.tool.task;

import com.aicodeassistant.model.TaskStatus;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Owns task admission, execution outcomes and cancellation until actual execution exits. */
@Service
public class TaskCoordinator {
    private static final Logger log = LoggerFactory.getLogger(TaskCoordinator.class);
    static final int MAX_CONCURRENT_TASKS = 10;
    static final Duration PER_TASK_TIMEOUT = Duration.ofMinutes(30);
    /** Retain the existing character limit, rather than changing to a byte-based limit. */
    static final int MAX_OUTPUT_SIZE = 1024 * 1024;

    private final ExecutorService executor;
    private final ScheduledExecutorService watchdog;
    private final Duration taskTimeout;
    private final ConcurrentMap<String, TaskState> tasks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TaskJob> jobs = new ConcurrentHashMap<>();
    private final Semaphore slots = new Semaphore(MAX_CONCURRENT_TASKS);
    private final Object admissionLock = new Object();
    private final SimpMessagingTemplate messagingTemplate;
    private boolean closed;

    @Autowired
    public TaskCoordinator(SimpMessagingTemplate messagingTemplate) {
        this(messagingTemplate, Executors.newVirtualThreadPerTaskExecutor(),
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "task-watchdog");
                    t.setDaemon(true);
                    return t;
                }), PER_TASK_TIMEOUT);
    }

    TaskCoordinator(SimpMessagingTemplate messagingTemplate, ExecutorService executor,
                    ScheduledExecutorService watchdog, Duration taskTimeout) {
        this.messagingTemplate = messagingTemplate;
        this.executor = executor;
        this.watchdog = watchdog;
        this.taskTimeout = taskTimeout;
    }

    /** Compatibility adapter for execution bodies without a result. */
    public TaskState submit(String taskId, String sessionId, String description, Runnable runnable) {
        return submitResult(taskId, sessionId, description, () -> {
            runnable.run();
            return TaskExecutionResult.completed(null);
        });
    }

    public TaskState submitResult(String taskId, String sessionId, String description,
                                  Callable<TaskExecutionResult> execution) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(execution, "execution");
        synchronized (admissionLock) {
            if (closed) {
                throw new SubmissionException("TASK_SCHEDULER_UNAVAILABLE", "Task coordinator is shutting down");
            }
            if (!slots.tryAcquire()) {
                throw new SubmissionException("TASK_CAPACITY_REACHED",
                        "Max concurrent tasks reached: " + MAX_CONCURRENT_TASKS);
            }
            TaskState state = new TaskState(taskId, sessionId, TaskStatus.PENDING, description);
            if (tasks.putIfAbsent(taskId, state) != null) {
                slots.release();
                throw new SubmissionException("TASK_ID_CONFLICT", "Task ID is already in use");
            }
            TaskJob job = new TaskJob(state, execution);
            jobs.put(taskId, job);
            state.setFuture(job.completion);
            try {
                // Register the timeout before execution can start. A rejected scheduler cannot
                // leave an already-running task behind a NOT_STARTED response.
                job.timeout = watchdog.schedule(() -> { requestCancellation(job, true, new HashSet<>()); },
                        taskTimeout.toMillis(), TimeUnit.MILLISECONDS);
                executor.execute(job);
            } catch (RuntimeException failure) {
                boolean notStarted;
                synchronized (state) {
                    notStarted = !job.started;
                    if (notStarted) {
                        job.abandonBeforeStart();
                        tasks.remove(taskId, state);
                    }
                }
                if (notStarted && failure instanceof RejectedExecutionException) {
                    throw new SubmissionException("TASK_SCHEDULER_UNAVAILABLE", "Task scheduler rejected submission");
                }
                throw failure;
            }
            notifyTaskUpdate(state.snapshot());
            return state;
        }
    }

    /** Only this exception proves that submission did not start execution. */
    public static class SubmissionException extends IllegalStateException {
        private final String code;
        SubmissionException(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }

    public enum CancellationResult { NOT_FOUND, ALREADY_TERMINAL, ALREADY_CANCELLED, REQUESTED }

    /** Registers cleanup owned by this execution, before the execution adapter starts. */
    void registerCancellationCleanup(String taskId, Runnable cleanup) {
        TaskJob job = jobs.get(taskId);
        if (job == null) throw new IllegalStateException("Task execution is no longer active");
        synchronized (job.state) {
            if (job.finished || job.cancellationCleanup != null) {
                throw new IllegalStateException("Task cancellation cleanup cannot be replaced");
            }
            job.cancellationCleanup = Objects.requireNonNull(cleanup);
        }
    }

    /** Close this task's execution admission before acknowledging a cancellation request. */
    void registerCancellationRequest(String taskId, java.util.function.Consumer<Boolean> request) {
        TaskJob job = jobs.get(taskId);
        if (job == null) throw new IllegalStateException("Task execution is no longer active");
        boolean requested;
        boolean timedOut;
        synchronized (job.state) {
            if (job.finished || job.cancellationRequest != null) {
                throw new IllegalStateException("Task cancellation request cannot be replaced");
            }
            job.cancellationRequest = Objects.requireNonNull(request);
            requested = job.state.isCancellationRequested();
            timedOut = job.timedOut;
        }
        if (requested) request.accept(timedOut);
    }

    /** Session-facing cancellation never resolves another session's task. */
    public CancellationResult cancelTask(String taskId, String sessionId) {
        TaskState task = ownedTask(taskId, sessionId);
        if (task == null) return CancellationResult.NOT_FOUND;
        TaskJob job = jobs.get(taskId);
        if (job != null && job.state != task) return CancellationResult.NOT_FOUND;
        if (job == null || !requestCancellation(job, false, new HashSet<>())) {
            TaskState.Snapshot snapshot = task.snapshot();
            if (snapshot.status() == TaskStatus.CANCELLED && snapshot.cancellationRequested()) {
                return CancellationResult.ALREADY_CANCELLED;
            }
            return CancellationResult.ALREADY_TERMINAL;
        }
        return CancellationResult.REQUESTED;
    }

    /** Internal lifecycle entry point, also retained for package-local tests. */
    boolean cancelTask(String taskId) {
        TaskJob job = taskId == null ? null : jobs.get(taskId);
        return job != null && requestCancellation(job, false, new HashSet<>());
    }

    private boolean requestCancellation(TaskJob job, boolean timedOut, Set<String> visited) {
        TaskState state = job.state;
        if (!visited.add(state.getTaskId())) return false;
        Thread worker;
        java.util.function.Consumer<Boolean> request;
        boolean timeoutAccepted;
        List<String> children;
        synchronized (state) {
            if (job.finished) return false;
            if (!state.isCancellationRequested()) {
                state.setCancellationRequested(true);
                job.timedOut = timedOut;
            }
            worker = job.worker;
            request = job.cancellationRequest;
            timeoutAccepted = job.timedOut;
            children = List.copyOf(state.getChildTaskIds());
            // No execution has started, so there are no owned resources to await.
            // A queued wrapper will see finished and skip the execution body.
            if (!job.started) job.finish(TaskExecutionResult.cancelled(null, null));
        }
        // May persist approval cancellation. Never hold a Task monitor across external work.
        if (request != null) {
            try { request.accept(timeoutAccepted); }
            catch (RuntimeException failure) {
                log.warn("Task {} cancellation request remains pending", state.getTaskId(), failure);
            }
        }
        if (worker != null) worker.interrupt();
        for (String childId : children) {
            TaskJob child = jobs.get(childId);
            if (child != null && state.getSessionId().equals(child.state.getSessionId())) {
                requestCancellation(child, timedOut, visited);
            }
        }
        notifyTaskUpdate(state.snapshot());
        return true;
    }

    /** Raw references are reserved for coordinator internals and package-local tests. */
    Optional<TaskState> getTask(String taskId) {
        return Optional.ofNullable(taskId == null ? null : tasks.get(taskId));
    }

    public Optional<TaskState.Snapshot> getTask(String taskId, String sessionId) {
        TaskState state = ownedTask(taskId, sessionId);
        return state == null ? Optional.empty() : Optional.of(state.snapshot());
    }

    public Optional<TaskState.Snapshot> updateOutput(String taskId, String sessionId,
                                                    String output, boolean isError) {
        TaskState state = ownedTask(taskId, sessionId);
        if (state == null) return Optional.empty();
        TaskState.Snapshot snapshot;
        synchronized (state) {
            if (output != null) {
                String bounded = truncateOutput(output);
                state.setOutput(bounded);
                if (isError && !state.getStatus().isTerminal()) state.setError(bounded);
            }
            snapshot = state.snapshot();
        }
        return Optional.of(snapshot);
    }

    private TaskState ownedTask(String taskId, String sessionId) {
        if (taskId == null || sessionId == null || sessionId.isBlank()) return null;
        TaskState task = tasks.get(taskId);
        return task != null && sessionId.equals(task.getSessionId()) ? task : null;
    }

    List<TaskState> listTasks(String sessionId, TaskStatus filterStatus) {
        if (sessionId == null || sessionId.isBlank()) return List.of();
        return tasks.values().stream()
                .filter(t -> sessionId.equals(t.getSessionId()))
                .filter(t -> filterStatus == null || t.getStatus() == filterStatus)
                .sorted(Comparator.comparing(TaskState::getCreatedAt).reversed())
                .toList();
    }

    public List<TaskState.Snapshot> listTaskSnapshots(String sessionId, TaskStatus filterStatus) {
        if (sessionId == null || sessionId.isBlank()) return List.of();
        return tasks.values().stream()
                .filter(t -> sessionId.equals(t.getSessionId()))
                .map(TaskState::snapshot)
                .filter(t -> filterStatus == null || t.status() == filterStatus)
                .sorted(Comparator.comparing(TaskState.Snapshot::createdAt).reversed())
                .toList();
    }

    public static String truncateOutput(String output) {
        if (output == null || output.length() <= MAX_OUTPUT_SIZE) return output;
        int end = MAX_OUTPUT_SIZE;
        if (Character.isHighSurrogate(output.charAt(end - 1)) && Character.isLowSurrogate(output.charAt(end))) end--;
        return output.substring(0, end) + "\n[Output truncated at 1048576 character limit]";
    }

    private void notifyTaskUpdate(TaskState.Snapshot task) {
        try {
            messagingTemplate.convertAndSend("/topic/session/" + task.sessionId(),
                    Map.of("type", "task_update", "taskId", task.taskId(),
                            "status", task.status().name(), "cancellationRequested", task.cancellationRequested(),
                            "terminationConfirmed", task.terminationConfirmed(),
                            "updatedAt", Instant.now().toEpochMilli()));
        } catch (Exception e) {
            log.warn("Failed to send task update for {}: {}", task.taskId(), e.getMessage());
        }
    }

    private final class TaskJob implements Runnable {
        private final TaskState state;
        private final Callable<TaskExecutionResult> execution;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private ScheduledFuture<?> timeout;
        // Guarded by state. Do not use Future.isDone/cancel as evidence of physical exit.
        private Thread worker;
        private boolean started;
        private boolean finished;
        private boolean timedOut;
        private Runnable cancellationCleanup;
        private java.util.function.Consumer<Boolean> cancellationRequest;
        private boolean cleanupCompleted;

        private TaskJob(TaskState state, Callable<TaskExecutionResult> execution) {
            this.state = state;
            this.execution = execution;
        }

        @Override public void run() {
            synchronized (state) {
                if (finished) return;
                started = true;
                worker = Thread.currentThread();
                state.setStatus(TaskStatus.RUNNING);
            }
            notifyTaskUpdate(state.snapshot());
            TaskExecutionResult result = TaskExecutionResult.failed(null, "Task exited without a result");
            try {
                result = execution.call();
                if (result == null) result = TaskExecutionResult.failed(null, "Task returned no execution result");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                result = TaskExecutionResult.cancelled(null, "Task execution interrupted");
            } catch (Throwable failure) {
                result = TaskExecutionResult.failed(null, failure.getClass().getSimpleName() + ": " + failure.getMessage());
                log.error("Task {} failed", state.getTaskId(), failure);
                if (failure instanceof Error error) throw error;
            } finally {
                // Task execution adapters return only after their owned execution has exited.
                finish(result);
            }
        }

        private void finish(TaskExecutionResult result) {
            while (true) {
                Runnable cleanup;
                synchronized (state) {
                    if (finished) return;
                    cleanup = state.isCancellationRequested() && !cleanupCompleted ? cancellationCleanup : null;
                    if (cleanup == null) {
                        publishResult(result);
                        break;
                    }
                }
                // The adapter may have returned just before cancellation was accepted. Recheck
                // its exact owned scope before announcing a terminal outcome or releasing capacity.
                runCleanupUntilConfirmed(cleanup);
                synchronized (state) { cleanupCompleted = true; }
            }
            notifyTaskUpdate(state.snapshot());
        }

        private void runCleanupUntilConfirmed(Runnable cleanup) {
            boolean interrupted = Thread.interrupted();
            long retryDelayMillis = 25;
            int failures = 0;
            try {
                while (true) {
                    try {
                        cleanup.run();
                        return;
                    } catch (RuntimeException failure) {
                        if (failures++ % 60 == 0) {
                            log.warn("Task {} cancellation cleanup has not completed", state.getTaskId(), failure);
                        }
                        try { Thread.sleep(retryDelayMillis); }
                        catch (InterruptedException ignored) { interrupted = true; }
                        retryDelayMillis = Math.min(1000, retryDelayMillis * 2);
                    }
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        /** Caller holds state; this is the only execution-to-terminal transition. */
        private void publishResult(TaskExecutionResult result) {
            if (finished) return;
            finished = true;
            worker = null;
            if (result.output() != null) state.setOutput(truncateOutput(result.output()));
            String error = result.error();
            TaskStatus status = result.status();
            if (state.isCancellationRequested()) {
                status = timedOut ? TaskStatus.FAILED : TaskStatus.CANCELLED;
                if (timedOut) error = "Task timed out after " + taskTimeout;
            }
            state.setError(truncateOutput(error));
            state.setStatus(status);
            if (timeout != null) timeout.cancel(false);
            jobs.remove(state.getTaskId(), this);
            slots.release();
            completion.complete(null);
        }

        /** Submission rejected before execution; do not broadcast a nonexistent task. */
        private void abandonBeforeStart() {
            if (finished) return;
            finished = true;
            if (timeout != null) timeout.cancel(false);
            jobs.remove(state.getTaskId(), this);
            slots.release();
            completion.complete(null);
        }
    }

    @PreDestroy
    public void cleanup() {
        synchronized (admissionLock) { closed = true; }
        jobs.values().forEach(job -> requestCancellation(job, false, new HashSet<>()));
        executor.shutdown();
        watchdog.shutdownNow();
    }
}
