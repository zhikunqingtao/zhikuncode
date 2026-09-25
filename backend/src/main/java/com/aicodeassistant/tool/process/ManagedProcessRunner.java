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
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
        int headCapacity = maxCaptureBytes / 2;
        int tailCapacity = maxCaptureBytes - headCapacity;
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
        boolean truncated = total > maxCaptureBytes;
        if (!truncated) {
            byte[] combined = new byte[(int) total];
            System.arraycopy(head, 0, combined, 0, headLength);
            if (tailLength > 0) System.arraycopy(tail, 0, combined, headLength, tailLength);
            return new Capture(new String(combined, StandardCharsets.UTF_8), false);
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
}
