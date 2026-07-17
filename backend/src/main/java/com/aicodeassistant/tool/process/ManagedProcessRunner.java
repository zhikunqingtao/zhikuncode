package com.aicodeassistant.tool.process;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.annotation.PostConstruct;
import com.aicodeassistant.run.RunExecutionRegistry;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

    @Autowired
    public ManagedProcessRunner(RunExecutionRegistry runExecutions) {
        this.runExecutions = runExecutions;
    }

    /** Isolated-test constructor. Production always injects the Run admission authority. */
    public ManagedProcessRunner() { this.runExecutions = null; }

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
        RunExecutionRegistry.WorkLease workLease = acquireLease(request.runId(), request.toolUseId(), request.ownership());
        try {
            return runWithLease(request, workLease);
        } finally {
            if (workLease != null) workLease.close();
        }
    }

    private Result runWithLease(Request request, RunExecutionRegistry.WorkLease workLease)
            throws IOException, InterruptedException {
        if (!capacity.tryAcquire()) throw new IOException("PROCESS_CAPACITY_EXCEEDED");
        long started = System.nanoTime();
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(request.command());
            builder.directory(request.workingDirectory().toFile());
            builder.redirectErrorStream(false);
            process = builder.start();
        } catch (IOException | RuntimeException startFailure) {
            capacity.release();
            throw startFailure;
        }
        ActiveProcess activeProcess = new ActiveProcess(process, new AtomicBoolean(false), new AtomicBoolean(false),
                new AtomicBoolean(false), new CompletableFuture<>(), request.terminationHook());
        process.getOutputStream().close();
        ProcessKey key = new ProcessKey(request.runId(), request.toolUseId());
        if (key.trackable() && active.putIfAbsent(key, activeProcess) != null) {
            process.destroyForcibly();
            capacity.release();
            throw new IOException("PROCESS_OWNERSHIP_CONFLICT");
        }
        if (workLease != null) {
            workLease.onCancel(() -> {
                activeProcess.cancelled().set(true);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                terminate(activeProcess, deadline);
                cleanup(activeProcess, deadline);
            });
        }
        var drains = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<Capture> stdout = drains.submit(() -> drain(process.getInputStream()));
            Future<Capture> stderr = drains.submit(() -> drain(process.getErrorStream()));
            long remainingNanos = request.timeout().toNanos() - (System.nanoTime() - started);
            boolean completed = remainingNanos > 0
                    && process.waitFor(remainingNanos, TimeUnit.NANOSECONDS);
            boolean terminationConfirmed = true;
            long cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            if (!completed) terminationConfirmed = terminate(activeProcess, cleanupDeadline);
            terminationConfirmed = cleanup(activeProcess, cleanupDeadline) && terminationConfirmed;
            Capture out = awaitDrain(stdout, cleanupDeadline);
            Capture err = awaitDrain(stderr, cleanupDeadline);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            boolean cancelled=activeProcess.cancelled().get();
            return new Result(cancelled?130:(completed ? process.exitValue() : 137),
                    preview(out.text()), preview(err.text()), out.truncated(), err.truncated(),
                    !completed&&!cancelled, cancelled, terminationConfirmed, elapsedMs,
                    descendantsUnavailable(process));
        } finally {
            active.remove(key, activeProcess);
            long finalDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            if (process.isAlive()) terminate(activeProcess, finalDeadline);
            cleanup(activeProcess, finalDeadline);
            closeQuietly(process.getInputStream());
            closeQuietly(process.getErrorStream());
            closeQuietly(process.getOutputStream());
            drains.shutdownNow();
            capacity.release();
        }
    }

    /** Starts a bounded, owned background process whose output is discarded. */
    public BackgroundResult startBackground(BackgroundRequest request) throws IOException {
        RunExecutionRegistry.WorkLease workLease = acquireLease(
                request.runId(), request.toolUseId(), Ownership.RUN);
        if (!capacity.tryAcquire()) {
            if (workLease != null) workLease.close();
            throw new IOException("PROCESS_CAPACITY_EXCEEDED");
        }
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(request.command());
            builder.directory(request.workingDirectory().toFile());
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            process = builder.start();
            process.getOutputStream().close();
        } catch (IOException | RuntimeException failure) {
            capacity.release();
            if (workLease != null) workLease.close();
            throw failure;
        }
        ProcessKey key = new ProcessKey("session:" + request.sessionId(), request.toolUseId());
        ActiveProcess owned = new ActiveProcess(process, new AtomicBoolean(false), new AtomicBoolean(false),
                new AtomicBoolean(false), new CompletableFuture<>(), null);
        ActiveProcess previous = active.putIfAbsent(key, owned);
        if (previous != null) {
            process.destroyForcibly();
            capacity.release();
            if (workLease != null) workLease.close();
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
            active.remove(key, owned);
            capacity.release();
            if (workLease != null) workLease.close();
            throw new IOException("PROCESS_CANCELLED_DURING_BACKGROUND_START");
        }
        process.onExit().whenComplete((ignored, error) -> {
            active.remove(key, owned);
            capacity.release();
        });
        // Launch is Run-owned, but the successfully-started background service is
        // session-owned. This transfer lets development servers survive the query
        // that started them while still making a cancellation during launch safe.
        if (workLease != null) workLease.close();
        return new BackgroundResult(process.pid());
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
        for (ActiveProcess process : List.copyOf(active.values())) {
            process.cancelled().set(true);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            terminate(process, deadline);
            cleanup(process, deadline);
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
                if (terminate(entry.getValue(), deadline) && cleanup(entry.getValue(), deadline)) confirmed++;
            }
        }
        return new CancelSummary(found, confirmed, found - confirmed);
    }

    public boolean cancel(String runId, String toolUseId) {
        ActiveProcess process = active.get(new ProcessKey(runId, toolUseId));
        if(process==null)return false;
        process.cancelled().set(true);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        return terminate(process, deadline) && cleanup(process, deadline);
    }

    private boolean terminate(ActiveProcess owned, long cleanupDeadlineNanos) {
        Process process = owned.process();
        if (!process.isAlive()) return true;
        if (!owned.terminationStarted().compareAndSet(false, true)) {
            return awaitExit(process, cleanupDeadlineNanos);
        }
        List<ProcessHandle> descendants = new ArrayList<>();
        try { descendants.addAll(process.descendants().toList()); }
        catch (RuntimeException e) { log.debug("Process descendants unavailable: {}", e.getMessage()); }
        descendants.reversed().forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            long firstWait = Math.min(terminateGraceMs, remainingMillis(cleanupDeadlineNanos));
            if (firstWait > 0 && process.waitFor(firstWait, TimeUnit.MILLISECONDS)) return true;
            descendants.reversed().stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            return awaitExit(process, cleanupDeadlineNanos);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return false;
        }
    }

    private boolean cleanup(ActiveProcess owned, long deadlineNanos) {
        if (owned.terminationHook() == null) return true;
        if (owned.cleanupStarted().compareAndSet(false, true)) {
            boolean result;
            try { result = owned.terminationHook().cleanup(deadlineNanos); }
            catch (Exception e) {
                log.warn("Process cleanup hook failed: {}", e.getMessage());
                result = false;
            }
            owned.cleanupResult().complete(result);
            return result;
        }
        long remaining = remainingMillis(deadlineNanos);
        if (remaining <= 0) return owned.cleanupResult().getNow(false);
        try { return owned.cleanupResult().get(remaining, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        catch (Exception e) { return false; }
    }

    private static boolean awaitExit(Process process, long deadlineNanos) {
        if (!process.isAlive()) return true;
        long remaining = remainingMillis(deadlineNanos);
        if (remaining <= 0) return !process.isAlive();
        try { return process.waitFor(remaining, TimeUnit.MILLISECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return !process.isAlive(); }
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
    private record ActiveProcess(Process process, AtomicBoolean cancelled, AtomicBoolean terminationStarted,
                                 AtomicBoolean cleanupStarted, CompletableFuture<Boolean> cleanupResult,
                                 TerminationHook terminationHook) {}
    private record ProcessKey(String runId, String toolUseId) {
        boolean trackable() { return runId != null && toolUseId != null; }
    }
}
