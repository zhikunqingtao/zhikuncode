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
