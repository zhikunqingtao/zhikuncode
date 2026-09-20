package com.aicodeassistant.tool.process;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ManagedProcessRunnerTest {
    private final ManagedProcessRunner runner = new ManagedProcessRunner();

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
            runner.startBackground(new ManagedProcessRunner.BackgroundRequest(List.of("bash", "-c", "sleep 30"),
                    Path.of(System.getProperty("java.io.tmpdir")), "run-test", "tool-test", "session-merge-test"));
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
