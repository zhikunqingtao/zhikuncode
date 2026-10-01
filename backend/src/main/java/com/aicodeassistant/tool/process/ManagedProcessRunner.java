package com.aicodeassistant.tool.process;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.annotation.PostConstruct;
import com.aicodeassistant.run.RunExecutionRegistry;
import com.aicodeassistant.observability.BestEffortObservabilityRecorder;
import com.aicodeassistant.observability.SafeLogValue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Semaphore;

/** Foreground process supervisor with hard deadlines, bounded drains and run cancellation. */
@Component
public class ManagedProcessRunner {
    private static final Logger log = LoggerFactory.getLogger(ManagedProcessRunner.class);
    @Value("${process.runner.max-capture-bytes:1048576}")
    private int maxCaptureBytes = 1024 * 1024;
    @Value("${process.runner.max-preview-chars:30000}")
    private int maxPreviewChars = 30_000;
    @Value("${process.runner.terminate-grace-ms:1000}")
    private long terminateGraceMs = 1_000;
    @Value("${process.runner.drain-join-ms:1000}")
    private long drainJoinMs = 1_000;
    @Value("${process.runner.max-concurrent:16}")
    private int maxConcurrent = 16;
    private final Map<ProcessKey, ActiveProcess> active = new ConcurrentHashMap<>();
    // Raw Git has a different natural-exit policy. Never put these scopes in
    // active: default finally/retry/shutdown cleanup is allowed to kill descendants.
    private final Map<ProcessKey, GitScope> gitScopes = new ConcurrentHashMap<>();
    private volatile Semaphore capacity = new Semaphore(16);
    private final RunExecutionRegistry runExecutions;
    private volatile BestEffortObservabilityRecorder observabilityRecorder;
    @Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.aicodeassistant.session.SessionManager sessions;

    @Autowired
    public ManagedProcessRunner(RunExecutionRegistry runExecutions) {
        this.runExecutions = runExecutions;
    }

    /** Isolated-test constructor. Production always injects the Run admission authority. */
    public ManagedProcessRunner() { this.runExecutions = null; }

    @Autowired(required = false)
    void setObservabilityRecorder(BestEffortObservabilityRecorder observabilityRecorder) {
        this.observabilityRecorder = observabilityRecorder;
    }

    @PostConstruct
    void validateConfiguration() {
        if (maxCaptureBytes < 64 * 1024 || maxCaptureBytes > 16 * 1024 * 1024
                || maxPreviewChars < 1_000 || maxPreviewChars > 100_000
                || terminateGraceMs < 100 || terminateGraceMs > 5_000
                || drainJoinMs < 100 || drainJoinMs > 5_000
                || maxConcurrent < 1 || maxConcurrent > 128) {
            throw new IllegalStateException("Invalid process.runner configuration");
        }
        capacity = new Semaphore(maxConcurrent);
    }

    public Result run(Request request) throws IOException, InterruptedException {
        return runWithEnvironment(request, null);
    }

    /**
     * Internal Git transport: bounded complete UTF-8, no preview/trim, and an
     * independent SERVICE owner. Natural foreground exit (including nonzero)
     * preserves hook descendants and their open pipes. Output completeness and
     * whole-scope exit are independent result fields.
     */
    public Result runRawGit(Request request) throws IOException, InterruptedException {
        if (request.ownership() != Ownership.SERVICE || "service".equals(request.runId()))
            throw new IllegalArgumentException("Raw Git requires an independent SERVICE owner");
        if (request.terminationHook() != null)
            throw new IllegalArgumentException("Raw Git does not accept a termination hook");
        if (!capacity.tryAcquire()) throw new IOException("PROCESS_CAPACITY_EXCEEDED");
        ProcessKey key = new ProcessKey(request.runId(), request.toolUseId());
        GitScope scope = new GitScope(key);
        if (gitScopes.putIfAbsent(key, scope) != null) {
            releaseGitCapacity(scope);
            throw new IOException("PROCESS_OWNERSHIP_CONFLICT");
        }
        long started = System.nanoTime();
        try {
            ProcessBuilder builder = new ProcessBuilder(request.command())
                    .directory(request.workingDirectory().toFile()).redirectErrorStream(false);
            RawGitProcess process;
            try {
                process = RawGitProcess.start(builder);
            } catch (RawGitProcess.LaunchFailure partialLaunch) {
                synchronized (scope) {
                    scope.process = partialLaunch.retainedProcess();
                    scope.phase = GitPhase.CANCELLING;
                    scope.cancelled = true;
                }
                throw partialLaunch;
            }
            synchronized (scope) { scope.process = process; }
            startGitDrain(process.getInputStream(), scope.stdout);
            scope.stdoutStarted = true;
            startGitDrain(process.getErrorStream(), scope.stderr);
            scope.stderrStarted = true;
            boolean cancellationPending;
            synchronized (scope) { cancellationPending = scope.phase == GitPhase.CANCELLING; }
            if (cancellationPending) stopCancelledGit(scope);
            long remaining = request.timeout().toNanos() - (System.nanoTime() - started);
            boolean completed = remaining > 0 && process.waitFor(remaining, TimeUnit.NANOSECONDS);
            if (completed) observeGitRootExit(scope);
            else requestGitCancellation(scope, true);
            stopCancelledGit(scope);

            // Do not cancel drains on this deadline: a successful hook may still
            // own the pipes. The scope retains the bounded drain until natural EOF.
            long drainDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(drainJoinMs);
            Capture out = awaitGitDrain(scope.stdout, drainDeadline);
            Capture err = awaitGitDrain(scope.stderr, drainDeadline);
            reapGitScope(scope);
            boolean timedOut;
            boolean cancelled;
            synchronized (scope) {
                timedOut = scope.timedOut;
                cancelled = scope.cancelled;
            }
            OwnedProcess.ScopeSnapshot snapshot = process.observeScope();
            boolean stopped = snapshot.allExited() && scope.stdout.isDone() && scope.stderr.isDone();
            if (!stopped) log.debug("Raw Git scope retained: owner={}, inspectionComplete={}, activeCount={}, stdoutDrained={}, stderrDrained={}",
                    request.runId(), snapshot.inspectionComplete(), snapshot.activeCount(), scope.stdout.isDone(), scope.stderr.isDone());
            return new Result(cancelled ? 130 : timedOut ? 137 : process.exitValue(),
                    out.text(), err.text(), out.truncated(), err.truncated(),
                    timedOut, cancelled, stopped,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), !snapshot.inspectionComplete());
        } catch (InterruptedException interrupted) {
            requestGitCancellation(scope, false);
            throw interrupted;
        } finally {
            // A failed launch has no process scope. Otherwise all automatic
            // signalling is restricted to cancellation claimed while RUNNING.
            boolean interrupted = Thread.interrupted();
            if (!scope.stdoutStarted) scope.stdout.complete(new Capture("[Git stdout reader did not start]", true));
            if (!scope.stderrStarted) scope.stderr.complete(new Capture("[Git stderr reader did not start]", true));
            if (scope.process == null) {
                gitScopes.remove(key, scope);
                releaseGitCapacity(scope);
            } else {
                observeGitRootExit(scope);
                synchronized (scope) {
                    if (scope.phase == GitPhase.RUNNING) {
                        scope.phase = GitPhase.CANCELLING;
                        scope.cancelled = true;
                    }
                }
                stopCancelledGit(scope);
                reapGitScope(scope);
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private void startGitDrain(InputStream stream, CompletableFuture<Capture> capture) {
        try {
            Thread.ofVirtual().name("raw-git-drain").start(() -> {
                try { capture.complete(drain(stream, maxCaptureBytes, true)); }
                catch (Throwable failure) {
                    capture.complete(new Capture("[Git output could not be read as complete UTF-8]", true));
                    if (failure instanceof Error error) throw error;
                }
            });
        } catch (RuntimeException | Error startFailure) {
            capture.complete(new Capture("[Git output reader could not start]", true));
            throw startFailure;
        }
    }

    private Capture awaitGitDrain(CompletableFuture<Capture> capture, long deadline) throws InterruptedException {
        try {
            if (capture.isDone()) return capture.get();
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) return capture.get(remaining, TimeUnit.NANOSECONDS);
        } catch (java.util.concurrent.TimeoutException | ExecutionException incomplete) {
            // Keep the reader alive; closing its pipe can signal a surviving hook.
        }
        return new Capture("[Git output is incomplete; its pipe remains supervised]", true);
    }

    private void observeGitRootExit(GitScope scope) {
        synchronized (scope) {
            if (scope.process != null && !scope.process.isAlive()) {
                if (scope.phase == GitPhase.RUNNING) scope.phase = GitPhase.OBSERVING;
                // Cancellation may retain descendants/pipes, but the foreground slot
                // belongs only to the main Git process. Preserve CANCELLING for retries.
                releaseGitCapacity(scope);
            }
        }
    }

    private void requestGitCancellation(GitScope scope, boolean timeout) {
        synchronized (scope) {
            // Natural exit wins a race with cancellation; its children are preserved.
            observeGitRootExit(scope);
            if (scope.phase == GitPhase.RUNNING) {
                scope.phase = GitPhase.CANCELLING;
                scope.timedOut = timeout;
                scope.cancelled = !timeout;
            }
        }
    }

    private void stopCancelledGit(GitScope scope) {
        RawGitProcess process;
        synchronized (scope) {
            if (scope.phase != GitPhase.CANCELLING || scope.process == null) return;
            process = scope.process;
        }
        process.terminate(System.nanoTime() + TimeUnit.SECONDS.toNanos(2), terminateGraceMs, false);
    }

    private void releaseGitCapacity(GitScope scope) {
        if (scope.capacityReleased.compareAndSet(false, true)) capacity.release();
    }

    private void reapGitScope(GitScope scope) {
        observeGitRootExit(scope);
        RawGitProcess process = scope.process;
        if (process == null || !process.observeScope().allExited()
                || !scope.stdout.isDone() || !scope.stderr.isDone()) return;
        if (gitScopes.remove(scope.key, scope)) {
            synchronized (scope) { scope.phase = GitPhase.RELEASED; }
            try {
                // Stream acquisition itself may have failed during launch.
                try { closeQuietly(process.getInputStream()); } catch (RuntimeException ignored) { }
                try { closeQuietly(process.getErrorStream()); } catch (RuntimeException ignored) { }
                try { closeQuietly(process.getOutputStream()); } catch (RuntimeException ignored) { }
            } finally { releaseGitCapacity(scope); }
        }
    }

    /** Exact RawGit owner query; it never signals a process or closes a live pipe. */
    public CancelSummary currentGitOperation(String ownerId) {
        for (GitScope scope : gitScopes.values()) {
            if (ownerId != null && ownerId.equals(scope.key.runId())) reapGitScope(scope);
        }
        int remaining = (int) gitScopes.keySet().stream()
                .filter(key -> ownerId != null && ownerId.equals(key.runId())).count();
        return new CancelSummary(remaining, 0, remaining);
    }

    /** Cancels only still-running Git; naturally exited hook scopes remain observable. */
    public CancelSummary cancelGitOperation(String ownerId) {
        int found = 0;
        int confirmed = 0;
        for (GitScope scope : List.copyOf(gitScopes.values())) {
            if (ownerId == null || !ownerId.equals(scope.key.runId())) continue;
            found++;
            requestGitCancellation(scope, false);
            stopCancelledGit(scope);
            reapGitScope(scope);
            if (gitScopes.get(scope.key) != scope) confirmed++;
        }
        return new CancelSummary(found, confirmed, found - confirmed);
    }

    /** Passive reclamation only: a retained natural-exit hook is never a cleanup target. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${process.runner.cleanup-retry-ms:30000}")
    public void observeRetainedGit() {
        for (GitScope scope : List.copyOf(gitScopes.values())) reapGitScope(scope);
    }

    /** Internal services only: replaces inherited environment; values are never logged. */
    public Result runIsolated(Request request, Map<String, String> environment) throws IOException, InterruptedException {
        return runWithEnvironment(request, Map.copyOf(environment));
    }

    private Result runWithEnvironment(Request request, Map<String, String> environment) throws IOException, InterruptedException {
        RunExecutionRegistry.WorkLease workLease = acquireLease(request.runId(), request.toolUseId(), request.ownership());
        var leaseTransferred = new AtomicBoolean(false);
        try {
            return runWithLease(request, workLease, environment, leaseTransferred);
        } finally {
            if (workLease != null && !leaseTransferred.get()) workLease.close();
        }
    }

    private Result runWithLease(Request request, RunExecutionRegistry.WorkLease workLease,
                                Map<String, String> environment, AtomicBoolean leaseTransferred)
            throws IOException, InterruptedException {
        if (!capacity.tryAcquire()) throw new IOException("PROCESS_CAPACITY_EXCEEDED");
        long started = System.nanoTime();
        ActiveProcess activeProcess = new ActiveProcess(new AtomicReference<>(), new AtomicBoolean(false),
                new AtomicReference<>(), request.terminationHook(), null, new AtomicBoolean(false), workLease);
        ProcessKey key = new ProcessKey(request.runId(), request.toolUseId());
        // Reserve ownership before starting user code, including on duplicate requests.
        if (active.putIfAbsent(key, activeProcess) != null) {
            capacity.release();
            throw new IOException("PROCESS_OWNERSHIP_CONFLICT");
        }
        leaseTransferred.set(true);
        java.util.concurrent.ExecutorService drains = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(request.command());
            builder.directory(request.workingDirectory().toFile());
            if (environment != null) {
                builder.environment().clear();
                builder.environment().putAll(environment);
            }
            builder.redirectErrorStream(false);
            Process process = OwnedProcess.start(builder);
            activeProcess.processRef().set(process);
            if (workLease != null) {
                workLease.onCancel(() -> {
                    activeProcess.cancelled().set(true);
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                    boolean stopped = terminate(activeProcess, deadline);
                    if (cleanup(activeProcess, deadline) && stopped) releaseRetained(key, activeProcess);
                });
            }
            if (activeProcess.cancelled().get()) {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                terminate(activeProcess, deadline);
            }
            drains = Executors.newVirtualThreadPerTaskExecutor();
            Future<Capture> stdout = drains.submit(() -> drain(process.getInputStream()));
            Future<Capture> stderr = drains.submit(() -> drain(process.getErrorStream()));
            recordProcessEvent(request.runId(), "process_started", request.toolUseId(), () -> Map.of(
                    "pid", process.pid(),
                    "executableCategory", executableCategory(request.command()),
                    "argvCount", request.command().size(),
                    "argvLength", SafeLogValue.totalLength(request.command()),
                    "commandFingerprint", SafeLogValue.fingerprintParts(request.command())));
            long remainingNanos = request.timeout().toNanos() - (System.nanoTime() - started);
            boolean completed = remainingNanos > 0
                    && process.waitFor(remainingNanos, TimeUnit.NANOSECONDS);
            long cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            // Foreground completion owns its remaining children, even after the shell has exited.
            boolean terminationConfirmed = terminate(activeProcess, cleanupDeadline);
            terminationConfirmed = cleanup(activeProcess, cleanupDeadline) && terminationConfirmed;
            Capture out = awaitDrain(stdout, cleanupDeadline);
            Capture err = awaitDrain(stderr, cleanupDeadline);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            boolean cancelled=activeProcess.cancelled().get();
            Result result = new Result(cancelled?130:(completed ? process.exitValue() : 137),
                    preview(out.text()), preview(err.text()), out.truncated(), err.truncated(),
                    !completed&&!cancelled, cancelled, terminationConfirmed, elapsedMs,
                    descendantsUnavailable(process));
            recordProcessEvent(request.runId(), "process_finished", request.toolUseId(), () -> Map.of(
                    "pid", process.pid(), "exitCode", result.exitCode(),
                    "durationMs", result.elapsedMs(), "timedOut", result.timedOut(),
                    "cancelled", result.cancelled(),
                    "terminationConfirmed", result.terminationConfirmed(),
                    "stdoutTruncated", result.stdoutTruncated(),
                    "stderrTruncated", result.stderrTruncated()));
            return result;
        } finally {
            boolean interrupted = Thread.interrupted();
            activeProcess.runnerFinished().set(true);
            Process process = activeProcess.process();
            long finalDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            boolean stopped = process == null || terminate(activeProcess, finalDeadline);
            if (process != null) stopped = cleanup(activeProcess, finalDeadline) && stopped;
            if (stopped) {
                releaseRetained(key, activeProcess);
            } else {
                // Retain ownership and its capacity slot; a later cancellation can retry termination.
                log.warn("Foreground process cleanup unconfirmed: pid={}", process.pid());
            }
            if (process != null) {
                closeQuietly(process.getInputStream());
                closeQuietly(process.getErrorStream());
                closeQuietly(process.getOutputStream());
            }
            if (drains != null) drains.shutdownNow();
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** Starts a bounded, owned background process whose output is discarded. */
    public BackgroundResult startBackground(BackgroundRequest request) throws IOException {
        var lease = sessions == null ? null : sessions.acquireBackgroundLease(request.sessionId());
        var processOwnsLease = new AtomicBoolean(false);
        try {
            return startBackgroundLeased(request, lease, processOwnsLease);
        } catch (IOException | RuntimeException | Error failure) {
            if (lease != null && !processOwnsLease.get()) lease.close();
            throw failure;
        }
    }

    private BackgroundResult startBackgroundLeased(BackgroundRequest request,
            com.aicodeassistant.session.SessionExecutionGate.BackgroundLease sessionLease,
            AtomicBoolean processOwnsLease) throws IOException {
        long startedNanos = System.nanoTime();
        RunExecutionRegistry.WorkLease workLease = acquireLease(
                request.runId(), request.toolUseId(), Ownership.RUN);
        if (!capacity.tryAcquire()) {
            if (workLease != null) workLease.close();
            throw new IOException("PROCESS_CAPACITY_EXCEEDED");
        }
        BackgroundProcessGroup group;
        try {
            group = BackgroundProcessGroup.launch(request.command(), request.workingDirectory());
        } catch (IOException | RuntimeException failure) {
            capacity.release();
            if (workLease != null) workLease.close();
            throw failure;
        }
        Process process = group.launcher;
        ProcessKey key = new ProcessKey("session:" + request.sessionId(), request.toolUseId());
        ActiveProcess owned = new ActiveProcess(new AtomicReference<>(process), new AtomicBoolean(false),
                new AtomicReference<>(), null, group, new AtomicBoolean(false), null);
        ActiveProcess previous = active.putIfAbsent(key, owned);
        processOwnsLease.set(true);
        group.exited.whenComplete((ignored, error) -> {
            active.remove(key, owned);
            capacity.release();
            if (sessionLease != null) sessionLease.close();
            recordProcessEvent(request.runId(), owned.cancelled().get() ? "process_cancelled" : "process_exited",
                    request.toolUseId(), () -> Map.of("pid", group.pid,
                            "exitCode", process.exitValue(), "errorType", SafeLogValue.errorType(error),
                            "durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos),
                            "cancelled", owned.cancelled().get()));
        });
        try {
            if (previous != null) {
                throw new IOException("PROCESS_OWNERSHIP_CONFLICT");
            }
            if (workLease != null) {
                workLease.onCancel(() -> {
                    owned.cancelled().set(true);
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                    terminate(owned, deadline);
                });
            }
            if (!process.isAlive() || owned.cancelled().get()) {
                throw new IOException("PROCESS_CANCELLED_DURING_BACKGROUND_START");
            }
            group.admit();
            // Launch is Run-owned, but the successfully-started background service is
            // session-owned. This transfer lets development servers survive the query
            // that started them while still making a cancellation during launch safe.
            recordProcessEvent(request.runId(), "process_started", request.toolUseId(), () -> Map.of(
                    "pid", group.pid, "background", true,
                    "executableCategory", executableCategory(request.command()),
                    "argvCount", request.command().size(),
                    "argvLength", SafeLogValue.totalLength(request.command()),
                    "commandFingerprint", SafeLogValue.fingerprintParts(request.command())));
            return new BackgroundResult(group.pid);
        } catch (IOException | RuntimeException | Error failure) {
            group.terminate(System.nanoTime() + TimeUnit.SECONDS.toNanos(2), terminateGraceMs);
            throw failure;
        } finally {
            if (workLease != null) workLease.close();
        }
    }

    public CancelSummary cancelSessionBackground(String sessionId) {
        return cancelOwnedBy("session:" + sessionId);
    }

    /** Read after the child Run has closed admission and become quiescent. */
    public CancelSummary currentSessionBackground(String sessionId) {
        if (sessionId == null) return new CancelSummary(0, 0, 0);
        return currentRunTermination("session:" + sessionId);
    }

    /**
     * Read-only foreground occupancy for the session's current Run. This does not
     * close admission or require the caller Run itself to be quiescent. A changed
     * mapping is not an idle proof; callers must retain resources and try later.
     */
    public SessionForegroundSnapshot currentSessionForeground(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || runExecutions == null) {
            throw new IllegalStateException("SESSION_FOREGROUND_OCCUPANCY_UNAVAILABLE");
        }
        String before = runExecutions.activeRunForSession(sessionId).orElse(null);
        CancelSummary occupancy = before == null ? new CancelSummary(0, 0, 0) : currentRunTermination(before);
        String after = runExecutions.activeRunForSession(sessionId).orElse(null);
        if (!Objects.equals(before, after)) {
            throw new IllegalStateException("SESSION_FOREGROUND_MAPPING_CHANGED");
        }
        return new SessionForegroundSnapshot(before, occupancy);
    }

    public record SessionForegroundSnapshot(String runId, CancelSummary occupancy) { }

    private CancelSummary cancelOwnedBy(String ownerId) {
        int found = 0;
        int confirmed = 0;
        for (var entry : active.entrySet()) {
            if (ownerId.equals(entry.getKey().runId())) {
                found++;
                entry.getValue().cancelled().set(true);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                if (terminate(entry.getValue(), deadline) && cleanup(entry.getValue(), deadline)) confirmed++;
            }
        }
        return new CancelSummary(found, confirmed, found - confirmed);
    }

    @jakarta.annotation.PreDestroy
    void shutdown() {
        for (var entry : List.copyOf(active.entrySet())) {
            ActiveProcess process = entry.getValue();
            process.cancelled().set(true);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            boolean stopped = terminate(process, deadline);
            if (cleanup(process, deadline) && stopped) releaseRetained(entry.getKey(), process);
        }
        // Natural-exit RawGit scopes are deliberately excluded from termination.
        for (String owner : gitScopes.keySet().stream().map(ProcessKey::runId).distinct().toList()) {
            cancelGitOperation(owner);
        }
    }

    private RunExecutionRegistry.WorkLease acquireLease(String runId, String toolUseId, Ownership ownership)
            throws IOException {
        if (ownership == Ownership.SERVICE || runExecutions == null) return null;
        try {
            return runExecutions.acquireWork(runId, "process", toolUseId);
        } catch (RunExecutionRegistry.WorkRejectedException rejected) {
            throw new IOException(rejected.getMessage(), rejected);
        }
    }

    public int cancelRun(String runId) {
        return cancelRunDetailed(runId).confirmedCount();
    }

    public CancelSummary cancelRunDetailed(String runId) {
        int found = 0;
        int confirmed = 0;
        for (var entry : active.entrySet()) {
            if (runId != null && runId.equals(entry.getKey().runId())) {
                found++;
                entry.getValue().cancelled().set(true);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                if (terminate(entry.getValue(), deadline) && cleanup(entry.getValue(), deadline)) {
                    confirmed++;
                    releaseRetained(entry.getKey(), entry.getValue());
                }
            }
        }
        CancelSummary summary = new CancelSummary(found, confirmed, found - confirmed);
        recordProcessEvent(runId, "process_cancellation_summary", null, () -> Map.of(
                "activeCount", summary.activeCount(),
                "confirmedCount", summary.confirmedCount(),
                "unconfirmedCount", summary.unconfirmedCount()));
        return summary;
    }

    /** Read after Run quiescence: entries disappear only after confirmed cleanup. */
    public CancelSummary currentRunTermination(String runId) {
        int remaining = (int) active.keySet().stream()
                .filter(key -> runId != null && runId.equals(key.runId())).count();
        return new CancelSummary(remaining, 0, remaining);
    }

    private int cleanupCursor;

    /** Retry only retained foreground work, never a running command/background service. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString = "${process.runner.cleanup-retry-ms:30000}")
    public synchronized void retryRetainedCleanup() {
        var entries = List.copyOf(active.entrySet());
        if (entries.isEmpty()) return;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        for (int scanned = 0; scanned < entries.size() && System.nanoTime() < deadline; scanned++) {
            var entry = entries.get(Math.floorMod(cleanupCursor++, entries.size()));
            ActiveProcess process = entry.getValue();
            if (process.backgroundGroup() != null || !process.runnerFinished().get()) continue;
            if (terminate(process, deadline) && cleanup(process, deadline)) {
                releaseRetained(entry.getKey(), process);
            }
        }
    }

    public boolean cancel(String runId, String toolUseId) {
        ActiveProcess process = active.get(new ProcessKey(runId, toolUseId));
        if(process==null)return false;
        process.cancelled().set(true);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        boolean confirmed = terminate(process, deadline) && cleanup(process, deadline);
        if (confirmed) releaseRetained(new ProcessKey(runId, toolUseId), process);
        return confirmed;
    }

    private void releaseRetained(ProcessKey key, ActiveProcess process) {
        if (process.backgroundGroup() == null && process.runnerFinished().get()
                && active.remove(key, process)) {
            try {
                if (process.workLease() != null) process.workLease().close();
            } finally {
                capacity.release();
            }
        }
    }

    private void recordProcessEvent(String runId, String eventType, String toolUseId,
                                    java.util.function.Supplier<Map<String, Object>> data) {
        try {
            BestEffortObservabilityRecorder recorder = observabilityRecorder;
            if (recorder != null) recorder.record(runId, eventType, toolUseId, data.get());
        } catch (Throwable ignored) {
            // Supplemental process observation must not affect lifecycle cleanup.
        }
    }

    private boolean terminate(ActiveProcess owned, long cleanupDeadlineNanos) {
        if (owned.backgroundGroup() != null) return owned.backgroundGroup().terminate(cleanupDeadlineNanos, terminateGraceMs);
        if (owned.process() instanceof OwnedProcess process) {
            return process.terminate(cleanupDeadlineNanos, terminateGraceMs, false);
        }
        return false; // A launch path without ownership must never claim whole-task cleanup.
    }

    private boolean cleanup(ActiveProcess owned, long deadlineNanos) {
        // Cancellation may observe the reservation before launch; no resource is clean yet.
        if (owned.process() == null) return false;
        if (owned.terminationHook() == null) return true;
        CompletableFuture<Boolean> attempt;
        for (;;) {
            attempt = owned.cleanupAttempt().get();
            if (attempt != null && (!attempt.isDone() || attempt.getNow(false))) break;
            if (remainingMillis(deadlineNanos) <= 0) return false;
            var next = new CompletableFuture<Boolean>();
            if (!owned.cleanupAttempt().compareAndSet(attempt, next)) continue;
            boolean result = false;
            try {
                result = owned.terminationHook().cleanup(deadlineNanos);
                return result;
            } catch (Exception e) {
                log.warn("Process cleanup hook failed: {}", e.getMessage());
                return false;
            } finally {
                // Cache success; a later caller may replace a failed attempt, never an active one.
                next.complete(result);
            }
        }
        long remaining = remainingMillis(deadlineNanos);
        if (remaining <= 0) return attempt.getNow(false);
        try { return attempt.get(remaining, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        catch (Exception e) { return false; }
    }

    private static long remainingMillis(long deadlineNanos) {
        long nanos = deadlineNanos - System.nanoTime();
        return nanos <= 0 ? 0 : Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos));
    }

    private Capture drain(InputStream stream) throws IOException {
        return drain(stream, maxCaptureBytes, false);
    }

    private Capture drain(InputStream stream, int captureLimit, boolean strictUtf8) throws IOException {
        int headCapacity = captureLimit / 2;
        int tailCapacity = captureLimit - headCapacity;
        byte[] head = new byte[headCapacity];
        byte[] tail = new byte[tailCapacity];
        int headLength = 0;
        int tailLength = 0;
        int tailPosition = 0;
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = stream.read(buffer)) >= 0) {
            int offset = 0;
            if (headLength < headCapacity) {
                int copy = Math.min(read, headCapacity - headLength);
                System.arraycopy(buffer, 0, head, headLength, copy);
                headLength += copy;
                offset += copy;
            }
            while (offset < read && tailCapacity > 0) {
                int copy = Math.min(read - offset, tailCapacity - tailPosition);
                System.arraycopy(buffer, offset, tail, tailPosition, copy);
                tailPosition = (tailPosition + copy) % tailCapacity;
                tailLength = Math.min(tailCapacity, tailLength + copy);
                offset += copy;
            }
            total += read;
        }
        boolean truncated = total > captureLimit;
        if (!truncated) {
            byte[] combined = new byte[(int) total];
            System.arraycopy(head, 0, combined, 0, headLength);
            if (tailLength > 0) System.arraycopy(tail, 0, combined, headLength, tailLength);
            String text = strictUtf8 ? StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(combined)).toString() : new String(combined, StandardCharsets.UTF_8);
            return new Capture(text, false);
        }
        byte[] orderedTail = new byte[tailLength];
        int start = tailLength == tailCapacity ? tailPosition : 0;
        int first = Math.min(tailLength, tailCapacity - start);
        System.arraycopy(tail, start, orderedTail, 0, first);
        if (first < tailLength) System.arraycopy(tail, 0, orderedTail, first, tailLength - first);
        String marker = "\n...[" + (total - headLength - tailLength) + " bytes omitted]...\n";
        return new Capture(new String(head, 0, headLength, StandardCharsets.UTF_8)
                + marker + new String(orderedTail, StandardCharsets.UTF_8), true);
    }

    private Capture awaitDrain(Future<Capture> future, long cleanupDeadlineNanos) {
        try {
            if (future.isDone()) return future.get();
            long remaining = Math.min(drainJoinMs, remainingMillis(cleanupDeadlineNanos));
            if (remaining <= 0) throw new java.util.concurrent.TimeoutException("cleanup deadline reached");
            return future.get(remaining, TimeUnit.MILLISECONDS);
        }
        catch (Exception e) {
            future.cancel(true);
            if (e instanceof ExecutionException execution && execution.getCause() != null) {
                return new Capture("[stream read failed: " + execution.getCause().getMessage() + "]", true);
            }
            return new Capture("[stream drain incomplete]", true);
        }
    }

    private String preview(String value) {
        return value.length() <= maxPreviewChars ? value : value.substring(0, maxPreviewChars);
    }
    private static void closeQuietly(java.io.Closeable closeable) { try { closeable.close(); } catch (Exception ignored) {} }
    private static String executableCategory(List<String> command) {
        try {
            if (command == null || command.isEmpty() || command.getFirst() == null) return "unknown";
            String executable = Path.of(command.getFirst()).getFileName().toString().toLowerCase();
            if (Set.of("bash", "sh", "zsh", "fish", "cmd", "powershell", "pwsh").contains(executable)) return "shell";
            if (executable.startsWith("python")) return "python";
            if (Set.of("node", "npm", "npx", "pnpm", "yarn", "bun").contains(executable)) return "javascript";
            if (Set.of("java", "javac", "mvn", "mvnw", "gradle", "gradlew").contains(executable)) return "jvm";
            if (Set.of("git", "gh").contains(executable)) return "git";
            return "other";
        } catch (Throwable ignored) {
            return "unknown";
        }
    }
    private static boolean descendantsUnavailable(Process process) {
        try { process.descendants().close(); return false; }
        catch (RuntimeException e) { return true; }
    }

    public enum Ownership { RUN, SERVICE }

    public record Request(List<String> command, Path workingDirectory, Duration timeout,
                          String runId, String toolUseId, TerminationHook terminationHook,
                          Ownership ownership) {
        public Request(List<String> command, Path workingDirectory, Duration timeout,
                       String runId, String toolUseId) {
            this(command, workingDirectory, timeout, runId, toolUseId, null, Ownership.RUN);
        }
        public Request(List<String> command, Path workingDirectory, Duration timeout,
                       String runId, String toolUseId, TerminationHook terminationHook) {
            this(command, workingDirectory, timeout, runId, toolUseId, terminationHook, Ownership.RUN);
        }
        public static Request serviceOwned(List<String> command, Path workingDirectory, Duration timeout,
                                           String operationId) {
            return new Request(command, workingDirectory, timeout, "service", operationId,
                    null, Ownership.SERVICE);
        }
        public Request {
            command = List.copyOf(command);
            if (command.isEmpty()) throw new IllegalArgumentException("command must not be empty");
            if (timeout == null || timeout.isZero() || timeout.isNegative())
                throw new IllegalArgumentException("timeout must be positive");
            if (runId == null || runId.isBlank() || toolUseId == null || toolUseId.isBlank())
                throw new IllegalArgumentException("PROCESS_OWNERSHIP_MISSING");
            if (ownership == null) throw new IllegalArgumentException("ownership is required");
        }
    }
    public record BackgroundRequest(List<String> command, Path workingDirectory,
                                    String runId, String toolUseId, String sessionId) {
        public BackgroundRequest {
            command = List.copyOf(command);
            if (command.isEmpty()) throw new IllegalArgumentException("command must not be empty");
            if (runId == null || runId.isBlank() || toolUseId == null || toolUseId.isBlank())
                throw new IllegalArgumentException("PROCESS_OWNERSHIP_MISSING");
            if (sessionId == null || sessionId.isBlank())
                throw new IllegalArgumentException("SESSION_OWNERSHIP_MISSING");
        }
    }
    public record BackgroundResult(long pid) { }
    public record Result(int exitCode, String stdout, String stderr,
                         boolean stdoutTruncated, boolean stderrTruncated,
                         boolean timedOut, boolean cancelled, boolean terminationConfirmed,
                         long elapsedMs, boolean descendantTrackingUnavailable) {}
    public record CancelSummary(int activeCount, int confirmedCount, int unconfirmedCount) {
        public boolean allTerminated() { return unconfirmedCount == 0; }
    }
    private record Capture(String text, boolean truncated) {}
    @FunctionalInterface public interface TerminationHook { boolean cleanup(long deadlineNanos) throws Exception; }
    private record ActiveProcess(AtomicReference<Process> processRef, AtomicBoolean cancelled,
                                 AtomicReference<CompletableFuture<Boolean>> cleanupAttempt,
                                 TerminationHook terminationHook, BackgroundProcessGroup backgroundGroup,
                                 AtomicBoolean runnerFinished, RunExecutionRegistry.WorkLease workLease) {
        Process process() { return processRef.get(); }
    }
    private record ProcessKey(String runId, String toolUseId) {}

    private enum GitPhase { RUNNING, OBSERVING, CANCELLING, RELEASED }
    private static final class GitScope {
        private final ProcessKey key;
        private final AtomicBoolean capacityReleased = new AtomicBoolean();
        private final CompletableFuture<Capture> stdout = new CompletableFuture<>();
        private final CompletableFuture<Capture> stderr = new CompletableFuture<>();
        private volatile RawGitProcess process;
        private GitPhase phase = GitPhase.RUNNING;
        private boolean timedOut;
        private boolean cancelled;
        private boolean stdoutStarted;
        private boolean stderrStarted;
        private GitScope(ProcessKey key) { this.key = key; }
    }
}
