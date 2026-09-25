package com.aicodeassistant.tool.process;

import com.aicodeassistant.engine.AbortContext;
import com.aicodeassistant.interaction.DurableInteractionService;
import com.aicodeassistant.run.*;
import com.aicodeassistant.tool.StreamingToolExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;

class LifecycleRecoveryRegressionTest {
    @Test void cancellationDuringLaunchUsesFreshCleanupConfirmation() throws Exception {
        var registry = spy(new RunExecutionRegistry());
        registry.register("window", "window-session", new AbortContext());
        var runner = new ManagedProcessRunner(registry);
        var paused = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        OwnedProcess process = mock(OwnedProcess.class);
        when(process.getInputStream()).thenReturn(InputStream.nullInputStream());
        when(process.getErrorStream()).thenReturn(InputStream.nullInputStream());
        when(process.getOutputStream()).thenReturn(OutputStream.nullOutputStream());
        when(process.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(process.terminate(anyLong(), anyLong(), eq(false))).thenReturn(true);
        var runs = mock(RunControlService.class);
        var interactions = mock(DurableInteractionService.class);
        var toolExecutor = mock(StreamingToolExecutor.class);
        when(interactions.beginRunTermination(anyString(), any(), anyString())).thenReturn(
            new DurableInteractionService.CancellationResult(RunControlService.TransitionResult.APPLIED, 0));
        when(toolExecutor.cancelRunDetailed("window")).thenReturn(new StreamingToolExecutor.ToolCancelSummary(0,0,0));
        when(runs.cancel("window")).thenReturn(RunControlService.TransitionResult.APPLIED);
        doAnswer(i -> { resume.countDown(); return i.callRealMethod(); }).when(registry).awaitQuiescence(eq("window"), any());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var launched = executor.submit(() -> {
                try (var starts = mockStatic(OwnedProcess.class)) {
                    starts.when(() -> OwnedProcess.start(any(ProcessBuilder.class))).thenAnswer(i -> {
                        paused.countDown(); assertThat(resume.await(3, TimeUnit.SECONDS)).isTrue(); return process;
                    });
                    return runner.run(new ManagedProcessRunner.Request(List.of("true"), Path.of(System.getProperty("java.io.tmpdir")), Duration.ofSeconds(3), "window", "tool"));
                }
            });
            assertThat(paused.await(3, TimeUnit.SECONDS)).isTrue();
            var coordinator = new RunTerminationCoordinator(runs, interactions, runner, registry, toolExecutor);
            var result = coordinator.cancelByUser("window", "user requested cancellation");
            assertThat(launched.get(3, TimeUnit.SECONDS).cancelled()).isTrue();
            assertThat(registry.awaitQuiescence("window", Duration.ZERO)).isTrue();
            assertThat(runner.cancelRunDetailed("window").activeCount()).isZero();
            assertThat(result.terminationConfirmed()).isTrue();
            assertThat(result.transition()).isEqualTo(RunControlService.TransitionResult.APPLIED);
            verify(runs).cancel("window");
            verify(runs, never()).fail(anyString(), any(), anyString());
        } finally { resume.countDown(); runner.shutdown(); }
    }

    @Test void maintenanceReleasesRetainedLeaseOnlyAfterConfirmedCleanup() throws Exception {
        var registry = new RunExecutionRegistry();
        registry.register("retained", "retained-session", new AbortContext());
        var runner = new ManagedProcessRunner(registry);
        Semaphore permits = new Semaphore(1);
        ReflectionTestUtils.setField(runner, "capacity", permits);
        AtomicBoolean recovered = new AtomicBoolean(false);
        try {
            var result = runner.run(new ManagedProcessRunner.Request(List.of("true"), Path.of(System.getProperty("java.io.tmpdir")), Duration.ofSeconds(2), "retained", "tool", d -> recovered.get()));
            assertThat(result.terminationConfirmed()).isFalse();
            registry.beginCompletion("retained");
            assertThat(registry.awaitQuiescence("retained", Duration.ZERO)).isFalse();
            registry.beginTermination("retained"); // completion -> coordinator automatic retry (one-shot callback)
            assertThat(runner.cancelRunDetailed("retained").unconfirmedCount()).isEqualTo(1); // coordinator scan retries too
            registry.unregister("retained");
            runner.retryRetainedCleanup();
            assertThat(registry.isRegistered("retained")).isTrue();
            assertThat(permits.availablePermits()).isZero();
            assertThatThrownBy(() -> registry.register("next", "retained-session", new AbortContext()))
                            .hasMessage("SESSION_EXECUTION_ALREADY_REGISTERED");
            recovered.set(true);
            runner.retryRetainedCleanup();
            runner.retryRetainedCleanup(); // Repeated recovery must not release a second permit.
            assertThat(registry.isRegistered("retained")).isFalse();
            assertThat(permits.availablePermits()).isEqualTo(1);
            registry.register("next", "retained-session", new AbortContext());
            registry.unregister("next");
        } finally { recovered.set(true); runner.shutdown(); }
    }


    @Test
    void maintenanceDoesNotCancelAnActiveForegroundCommand() throws Exception {
        var registry = new RunExecutionRegistry();
        registry.register("active", "active-session", new AbortContext());
        var runner = new ManagedProcessRunner(registry);
        var running = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        OwnedProcess process = mock(OwnedProcess.class);
        when(process.getInputStream()).thenReturn(InputStream.nullInputStream());
        when(process.getErrorStream()).thenReturn(InputStream.nullInputStream());
        when(process.getOutputStream()).thenReturn(OutputStream.nullOutputStream());
        when(process.waitFor(anyLong(), any(TimeUnit.class))).thenAnswer(inv -> {
            running.countDown();
            return finish.await(3, TimeUnit.SECONDS);
        });
        when(process.terminate(anyLong(), anyLong(), eq(false))).thenReturn(true);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var command = executor.submit(() -> {
                try (var starts = mockStatic(OwnedProcess.class)) {
                    starts.when(() -> OwnedProcess.start(any(ProcessBuilder.class))).thenReturn(process);
                    return runner.run(new ManagedProcessRunner.Request(List.of("true"),
                            Path.of(System.getProperty("java.io.tmpdir")), Duration.ofSeconds(3), "active", "tool"));
                }
            });
            try {
                assertThat(running.await(3, TimeUnit.SECONDS)).isTrue();
                runner.retryRetainedCleanup();
                assertThat(command.isDone()).isFalse();
                verify(process, never()).terminate(anyLong(), anyLong(), anyBoolean());
            } finally {
                finish.countDown();
            }
            var result = command.get(3, TimeUnit.SECONDS);
            assertThat(result.cancelled()).isFalse();
            assertThat(result.terminationConfirmed()).isTrue();
        } finally {
            finish.countDown();
            runner.shutdown();
        }
    }
}
