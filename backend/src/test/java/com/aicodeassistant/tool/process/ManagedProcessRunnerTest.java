package com.aicodeassistant.tool.process;

import com.aicodeassistant.engine.AbortContext;
import com.aicodeassistant.run.RunExecutionRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagedProcessRunnerTest {
    private final ManagedProcessRunner runner = new ManagedProcessRunner();

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void failedCleanupRetainsRunLeaseUntilRetryConfirmsExit(boolean cancelViaLease) throws Exception {
        var registry = new RunExecutionRegistry();
        registry.register("retained", "session", new AbortContext());
        var managed = new ManagedProcessRunner(registry);
        var capacity = new Semaphore(1);
        ReflectionTestUtils.setField(managed, "capacity", capacity);
        var recovered = new AtomicBoolean(false);
        var cleanupCalls = new AtomicInteger();
        try {
            var result = managed.run(new ManagedProcessRunner.Request(
                    List.of("bash", "-c", "printf finished"), Path.of(System.getProperty("java.io.tmpdir")),
                    Duration.ofSeconds(2), "retained", "tool", deadline -> {
                        cleanupCalls.incrementAndGet();
                        return recovered.get();
                    }));
            assertThat(result.exitCode()).isZero();
            assertThat(result.stdout()).isEqualTo("finished");
            assertThat(result.terminationConfirmed()).isFalse();
            assertThat(managed.cancelRunDetailed("retained").unconfirmedCount()).isEqualTo(1);
            assertThat(registry.awaitQuiescence("retained", Duration.ZERO)).isFalse();
            assertThat(capacity.availablePermits()).isZero();
            assertThatThrownBy(() -> managed.run(ManagedProcessRunner.Request.serviceOwned(
                    List.of("true"), Path.of(System.getProperty("java.io.tmpdir")), Duration.ofSeconds(2), "blocked")))
                    .isInstanceOf(IOException.class).hasMessage("PROCESS_CAPACITY_EXCEEDED");

            int failedAttempts = cleanupCalls.get();
            recovered.set(true);
            if (cancelViaLease) registry.beginTermination("retained");
            else assertThat(managed.cancelRunDetailed("retained").confirmedCount()).isEqualTo(1);
            assertThat(cleanupCalls).hasValue(failedAttempts + 1);
            assertThat(registry.awaitQuiescence("retained", Duration.ZERO)).isTrue();
            assertThat(capacity.availablePermits()).isEqualTo(1);
            assertThat(managed.cancel("retained", "tool")).isFalse();
            managed.shutdown();
            assertThat(capacity.availablePermits()).isEqualTo(1);
            // SERVICE requests have no Run lease and still release their capacity normally.
            assertThat(managed.run(ManagedProcessRunner.Request.serviceOwned(List.of("true"),
                    Path.of(System.getProperty("java.io.tmpdir")), Duration.ofSeconds(2), "service"))
                    .terminationConfirmed()).isTrue();
            assertThat(capacity.availablePermits()).isEqualTo(1);
        } finally {
            recovered.set(true);
            managed.shutdown();
        }
    }

    @Test
    void failedStartReleasesReservedLeaseAndCapacityWithoutCallingCleanup(@TempDir Path directory) {
        var registry = new RunExecutionRegistry();
        registry.register("start-failure", "session", new AbortContext());
        var managed = new ManagedProcessRunner(registry);
        var capacity = new Semaphore(1);
        ReflectionTestUtils.setField(managed, "capacity", capacity);
        var cleanupCalls = new AtomicInteger();
        assertThatThrownBy(() -> managed.run(new ManagedProcessRunner.Request(List.of("true"),
                directory.resolve("missing"), Duration.ofSeconds(2), "start-failure", "tool", deadline -> {
                    cleanupCalls.incrementAndGet();
                    return true;
                }))).isInstanceOf(IOException.class);
        assertThat(cleanupCalls).hasValue(0);
        assertThat(registry.awaitQuiescence("start-failure", Duration.ZERO)).isTrue();
        assertThat(managed.cancelRunDetailed("start-failure").activeCount()).isZero();
        assertThat(capacity.availablePermits()).isEqualTo(1);
    }

    @Test
    void duplicateRequestDoesNotExecuteCommandOrReleaseOriginalOwnership(@TempDir Path directory) throws Exception {
        var registry = new RunExecutionRegistry();
        registry.register("duplicate", "session", new AbortContext());
        var managed = new ManagedProcessRunner(registry);
        var capacity = new Semaphore(2);
        ReflectionTestUtils.setField(managed, "capacity", capacity);
        var recovered = new AtomicBoolean(false);
        try {
            managed.run(new ManagedProcessRunner.Request(List.of("true"), directory, Duration.ofSeconds(2),
                    "duplicate", "tool", deadline -> recovered.get()));
            assertThatThrownBy(() -> managed.run(new ManagedProcessRunner.Request(
                    List.of("bash", "-c", "touch should-not-exist"), directory, Duration.ofSeconds(2), "duplicate", "tool")))
                    .isInstanceOf(IOException.class).hasMessage("PROCESS_OWNERSHIP_CONFLICT");
            assertThat(Files.exists(directory.resolve("should-not-exist"))).isFalse();
            assertThat(capacity.availablePermits()).isEqualTo(1);
            assertThat(registry.awaitQuiescence("duplicate", Duration.ZERO)).isFalse();
            recovered.set(true);
            assertThat(managed.cancel("duplicate", "tool")).isTrue();
            assertThat(capacity.availablePermits()).isEqualTo(2);
            assertThat(registry.awaitQuiescence("duplicate", Duration.ZERO)).isTrue();
        } finally {
            recovered.set(true);
            managed.shutdown();
        }
    }

    @Test
    void cancellationDuringLaunchDoesNotCleanOrCacheSuccessBeforeProcessExists() throws Exception {
        var registry = new RunExecutionRegistry();
        registry.register("starting", "session", new AbortContext());
        var managed = new ManagedProcessRunner(registry);
        var cleanupCalls = new AtomicInteger();
        var process = org.mockito.Mockito.mock(OwnedProcess.class);
        org.mockito.Mockito.when(process.getInputStream()).thenReturn(java.io.InputStream.nullInputStream());
        org.mockito.Mockito.when(process.getErrorStream()).thenReturn(java.io.InputStream.nullInputStream());
        org.mockito.Mockito.when(process.getOutputStream()).thenReturn(java.io.OutputStream.nullOutputStream());
        org.mockito.Mockito.when(process.waitFor(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(TimeUnit.class))).thenReturn(true);
        org.mockito.Mockito.when(process.terminate(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq(false))).thenReturn(true);
        try (var starts = org.mockito.Mockito.mockStatic(OwnedProcess.class)) {
            starts.when(() -> OwnedProcess.start(org.mockito.ArgumentMatchers.any(ProcessBuilder.class)))
                    .thenAnswer(invocation -> {
                        managed.shutdown();
                        registry.beginTermination("starting");
                        assertThat(cleanupCalls).hasValue(0);
                        assertThat(registry.awaitQuiescence("starting", Duration.ZERO)).isFalse();
                        return process;
                    });
            var result = managed.run(new ManagedProcessRunner.Request(List.of("true"),
                    Path.of(System.getProperty("java.io.tmpdir")), Duration.ofSeconds(2), "starting", "tool", deadline -> {
                        cleanupCalls.incrementAndGet();
                        return true;
                    }));
            assertThat(result.cancelled()).isTrue();
            assertThat(cleanupCalls).hasValue(1);
            assertThat(registry.awaitQuiescence("starting", Duration.ZERO)).isTrue();
            assertThat(managed.cancelRunDetailed("starting").activeCount()).isZero();
        }
    }

    @Test
    void concurrentCancellationsShareOneRetryAfterFailedCleanup() throws Exception {
        var recovered = new AtomicBoolean(false);
        var cleanupCalls = new AtomicInteger();
        var activeCleaners = new AtomicInteger();
        var maximumCleaners = new AtomicInteger();
        var retryStarted = new CountDownLatch(1);
        var allowRetry = new CountDownLatch(1);
        try {
            runner.run(new ManagedProcessRunner.Request(List.of("true"), Path.of(System.getProperty("java.io.tmpdir")),
                    Duration.ofSeconds(2), "retry", "tool", deadline -> {
                        cleanupCalls.incrementAndGet();
                        maximumCleaners.accumulateAndGet(activeCleaners.incrementAndGet(), Math::max);
                        try {
                            if (!recovered.get()) return false;
                            retryStarted.countDown();
                            return allowRetry.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                        } finally { activeCleaners.decrementAndGet(); }
                    }));
            int failedAttempts = cleanupCalls.get();
            recovered.set(true);
            try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var first = executor.submit(() -> runner.cancel("retry", "tool"));
                assertThat(retryStarted.await(1, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> runner.cancel("retry", "tool"));
                try {
                    Thread.sleep(75);
                    assertThat(first.isDone() || second.isDone()).isFalse();
                    allowRetry.countDown();
                    assertThat(first.get(3, TimeUnit.SECONDS)).isTrue();
                    assertThat(second.get(3, TimeUnit.SECONDS)).isTrue();
                } finally { allowRetry.countDown(); }
            }
            assertThat(cleanupCalls).hasValue(failedAttempts + 1);
            assertThat(maximumCleaners).hasValue(1);
            assertThat(runner.cancelRunDetailed("retry").activeCount()).isZero();
            assertThat(((Semaphore) ReflectionTestUtils.getField(runner, "capacity")).availablePermits()).isEqualTo(16);
        } finally {
            recovered.set(true);
            allowRetry.countDown();
            runner.shutdown();
        }
    }

    @Test
    void preservesCompletedOutputEvenWhenCleanupConsumesItsBudget() throws Exception {
        var result = runner.run(new ManagedProcessRunner.Request(
                List.of("bash", "-c", "printf finished; printf diagnostic >&2"),
                Path.of(System.getProperty("java.io.tmpdir")), Duration.ofSeconds(2), "drain", "tool", deadline -> {
                    long left = deadline - System.nanoTime();
                    if (left > 0) TimeUnit.NANOSECONDS.sleep(left);
                    return false;
                }));
        assertThat(result.stdout()).isEqualTo("finished");
        assertThat(result.stderr()).isEqualTo("diagnostic");
        assertThat(result.terminationConfirmed()).isFalse();
    }

    @Test
    void foregroundParallelWorkThatWaitsStillCompletesNormally() throws Exception {
        var result = runner.run(new ManagedProcessRunner.Request(
                List.of("bash", "-c", "printf first & printf second & wait"),
                Path.of(System.getProperty("java.io.tmpdir")), Duration.ofSeconds(2), "parallel", "tool"));
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("first", "second");
        assertThat(result.terminationConfirmed()).isTrue();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void survivingChildRetainsLeaseAfterShellExitsOrIsKilled(boolean killParent,
            @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        var gate = new com.aicodeassistant.session.SessionExecutionGate();
        var ds = org.mockito.Mockito.mock(javax.sql.DataSource.class);
        var sessions = org.mockito.Mockito.mock(com.aicodeassistant.session.SessionManager.class);
        org.mockito.Mockito.when(sessions.acquireBackgroundLease("children"))
                .thenAnswer(call -> gate.acquireBackground(ds, "children"));
        org.springframework.test.util.ReflectionTestUtils.setField(runner, "sessions", sessions);
        // The child ignores TERM, exercises forced group cancellation, and outlives its shell.
        String script = "bash -c 'trap \"\" TERM; echo $$ > child.pid; while :; do sleep 1; done' & "
                + (killParent ? "wait" : "exit 0");
        try {
            var result = runner.startBackground(new ManagedProcessRunner.BackgroundRequest(
                    List.of("bash", "-c", script), directory, "run", "tool", "children"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((!java.nio.file.Files.exists(directory.resolve("child.pid"))
                    || java.nio.file.Files.size(directory.resolve("child.pid")) == 0) && System.nanoTime() < deadline) Thread.sleep(10);
            long child = Long.parseLong(java.nio.file.Files.readString(directory.resolve("child.pid")).strip());
            if (killParent) ProcessHandle.of(result.pid()).ifPresent(ProcessHandle::destroyForcibly);
            var parent = ProcessHandle.of(result.pid());
            if (parent.isPresent()) parent.get().onExit().get(5, TimeUnit.SECONDS);
            Thread.sleep(350); // allow parent-exit callbacks and ownership monitoring to run
            assertThat(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false)).isTrue();
            assertThat(gate.tryAcquireMerge(ds, "children", "merge")).isNull();
            assertThat(runner.cancelSessionBackground("children").allTerminated()).isTrue();
            com.aicodeassistant.session.SessionExecutionGate.Token merge = null;
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (merge == null && System.nanoTime() < deadline) {
                merge = gate.tryAcquireMerge(ds, "children", "merge");
                if (merge == null) Thread.sleep(10);
            }
            assertThat(merge).isNotNull(); merge.close();
        } finally { runner.cancelSessionBackground("children"); }
    }

    @Test void failedBackgroundLaunchReleasesSessionLease() {
        var gate = new com.aicodeassistant.session.SessionExecutionGate();
        var ds = org.mockito.Mockito.mock(javax.sql.DataSource.class);
        var sessions = org.mockito.Mockito.mock(com.aicodeassistant.session.SessionManager.class);
        org.mockito.Mockito.when(sessions.acquireBackgroundLease("failed"))
                .thenAnswer(call -> gate.acquireBackground(ds, "failed"));
        org.springframework.test.util.ReflectionTestUtils.setField(runner, "sessions", sessions);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> runner.startBackground(new ManagedProcessRunner.BackgroundRequest(
                List.of("bash", "-c", "true"), Path.of("/nonexistent-zhikun-test-directory"), "run", "tool", "failed")))
                .isInstanceOf(java.io.IOException.class);
        try (var merge = gate.tryAcquireMerge(ds, "failed", "merge")) { assertThat(merge).isNotNull(); }
    }

    @Test
    void backgroundSessionLeaseOutlivesLaunchAndReleasesOnlyOnProcessExit() throws Exception {
        var gate = new com.aicodeassistant.session.SessionExecutionGate();
        var ds = org.mockito.Mockito.mock(javax.sql.DataSource.class);
        var sessions = org.mockito.Mockito.mock(com.aicodeassistant.session.SessionManager.class);
        org.mockito.Mockito.when(sessions.acquireBackgroundLease("session-merge-test"))
                .thenAnswer(call -> gate.acquireBackground(ds, "session-merge-test"));
        org.springframework.test.util.ReflectionTestUtils.setField(runner, "sessions", sessions);
        try {
            var background = runner.startBackground(new ManagedProcessRunner.BackgroundRequest(List.of("bash", "-c", "sleep 30"),
                    Path.of(System.getProperty("java.io.tmpdir")), "run-test", "tool-test", "session-merge-test"));
            runner.retryRetainedCleanup();
            assertThat(ProcessHandle.of(background.pid()).orElseThrow().isAlive()).isTrue();
            assertThat(gate.tryAcquireMerge(ds, "session-merge-test", "merge")).isNull();
            assertThat(gate.tryAcquire(ds, "unrelated")).isNotNull();
        } finally { runner.cancelSessionBackground("session-merge-test"); }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        com.aicodeassistant.session.SessionExecutionGate.Token merge = null;
        while (merge == null && System.nanoTime() < deadline) {
            merge = gate.tryAcquireMerge(ds, "session-merge-test", "merge");
            if (merge == null) Thread.sleep(10);
        }
        assertThat(merge).isNotNull();
        merge.close();
    }

    @Test
    void drainsStdoutAndStderrWithoutDeadlock() throws Exception {
        var result = runner.run(new ManagedProcessRunner.Request(
                List.of("bash", "-c", "printf out; printf err >&2"),
                Path.of(System.getProperty("java.io.tmpdir")), Duration.ofSeconds(2), "run", "tool"));
        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).isEqualTo("out");
        assertThat(result.stderr()).isEqualTo("err");
        assertThat(result.timedOut()).isFalse();
    }

    @Test
    void enforcesDeadlineFromProcessStart() throws Exception {
        long start = System.nanoTime();
        var result = runner.run(new ManagedProcessRunner.Request(
                List.of("bash", "-c", "sleep 5"),
                Path.of(System.getProperty("java.io.tmpdir")), Duration.ofMillis(150), "run", "tool"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertThat(result.timedOut()).isTrue();
        assertThat(elapsedMs).isLessThan(2_500);
    }

    @Test
    void cancellationIsDistinguishedFromTimeout() throws Exception {
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() -> runner.run(new ManagedProcessRunner.Request(
                    List.of("bash", "-c", "sleep 30"),
                    Path.of(System.getProperty("java.io.tmpdir")), Duration.ofSeconds(30), "run-c", "tool-c")));
            Thread.sleep(100);
            assertThat(runner.cancel("run-c", "tool-c")).isTrue();
            var result = future.get();
            assertThat(result.cancelled()).isTrue();
            assertThat(result.timedOut()).isFalse();
            assertThat(result.exitCode()).isEqualTo(130);
        }
    }

    @Test
    void backgroundProcessRemainsOwnedAndIsCancelledWithItsRun() throws Exception {
        var started = runner.startBackground(new ManagedProcessRunner.BackgroundRequest(
                List.of("bash", "-c", "sleep 30"),
                Path.of(System.getProperty("java.io.tmpdir")), "run-bg", "tool-bg", "session-bg"));
        assertThat(started.pid()).isPositive();
        assertThat(runner.cancelRunDetailed("run-bg").activeCount()).isZero();
        var cancellation = runner.cancelSessionBackground("session-bg");
        assertThat(cancellation.activeCount()).isEqualTo(1);
        assertThat(cancellation.allTerminated()).isTrue();
    }

    @Test
    void concurrentCancellationWaitsForTheSingleCleanupOwner() throws Exception {
        CountDownLatch cleanupStarted = new CountDownLatch(1);
        CountDownLatch allowCleanup = new CountDownLatch(1);
        AtomicInteger cleanupCalls = new AtomicInteger();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var execution = executor.submit(() -> runner.run(new ManagedProcessRunner.Request(
                    List.of("bash", "-c", "sleep 30"), Path.of(System.getProperty("java.io.tmpdir")),
                    Duration.ofSeconds(30), "run-hook", "tool-hook", deadline -> {
                        cleanupCalls.incrementAndGet();
                        cleanupStarted.countDown();
                        return allowCleanup.await(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())),
                                TimeUnit.MILLISECONDS);
                    })));
            Thread.sleep(100);
            var first = executor.submit(() -> runner.cancel("run-hook", "tool-hook"));
            var second = executor.submit(() -> runner.cancel("run-hook", "tool-hook"));
            assertThat(cleanupStarted.await(1, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(75);
            assertThat(first.isDone() && second.isDone()).isFalse();
            allowCleanup.countDown();
            assertThat(first.get()).isTrue();
            assertThat(second.get()).isTrue();
            execution.get();
            assertThat(cleanupCalls).hasValue(1);
        }
    }
}
