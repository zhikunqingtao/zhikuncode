package com.aicodeassistant.tool.task;

import com.aicodeassistant.engine.AbortContext;
import com.aicodeassistant.run.RunControlService;
import com.aicodeassistant.run.RunEnvelope;
import com.aicodeassistant.run.RunExecutionRegistry;
import com.aicodeassistant.run.RunTerminationCoordinator;
import com.aicodeassistant.session.SessionExecutionGate;
import com.aicodeassistant.session.SessionManager;
import com.aicodeassistant.tool.StreamingToolExecutor;
import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.ToolExecutionResult;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import com.aicodeassistant.tool.bash.ShellStateManager;
import com.aicodeassistant.tool.process.ManagedProcessRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Lifecycle failures use in-memory collaborators; no process, database or service is started. */
@Timeout(10)
class TaskShellExecutorTest {
    private final TaskCoordinator tasks = mock(TaskCoordinator.class);
    private final StreamingToolExecutor tools = mock(StreamingToolExecutor.class);
    private final RunControlService runs = mock(RunControlService.class);
    private final RunExecutionRegistry executions = mock(RunExecutionRegistry.class);
    private final RunTerminationCoordinator termination = mock(RunTerminationCoordinator.class);
    private final ManagedProcessRunner processes = mock(ManagedProcessRunner.class);
    private final SessionManager sessions = mock(SessionManager.class);
    private final ShellStateManager shells = mock(ShellStateManager.class);
    private final SessionExecutionGate.BackgroundLease parentLease = mock(SessionExecutionGate.BackgroundLease.class);
    private final Tool tool = mock(Tool.class);
    private final ToolInput input = ToolInput.from(Map.of("command", "fixture command"));
    private final ToolUseContext parent = ToolUseContext.of("/workspace", "parent-session")
            .withCurrentRunId("parent-run").withToolUseId("parent-tool");
    private final AtomicReference<RunEnvelope> childRun = new AtomicReference<>();
    private final AtomicReference<ToolUseContext> childContext = new AtomicReference<>();
    private final AtomicReference<Runnable> cleanup = new AtomicReference<>();
    private final AtomicReference<Consumer<Boolean>> requestCancellation = new AtomicReference<>();
    private TaskShellExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new TaskShellExecutor(tasks, tools, runs, executions, termination, processes, sessions, shells);
        doAnswer(call -> { cleanup.set(call.getArgument(1)); return null; })
                .when(tasks).registerCancellationCleanup(eq("task-id"), any());
        doAnswer(call -> { requestCancellation.set(call.getArgument(1)); return null; })
                .when(tasks).registerCancellationRequest(eq("task-id"), any());
        when(sessions.acquireBackgroundLease("parent-session")).thenReturn(parentLease);
        when(shells.resolveWorkingDirectory("parent-session", "/workspace")).thenReturn("/workspace/sub");
        when(runs.start(anyString(), eq("parent-run"), eq("task-shell"), nullable(String.class)))
                .thenAnswer(call -> {
                    RunEnvelope run = RunEnvelope.start(call.getArgument(0), "parent-run", "task-shell", null);
                    childRun.set(run);
                    return run;
                });
        when(executions.awaitQuiescence(anyString(), eq(Duration.ZERO))).thenReturn(true);
        when(processes.currentRunTermination(anyString())).thenReturn(stopped());
        when(processes.currentSessionBackground(anyString())).thenReturn(stopped());
        when(termination.terminate(anyString(), any(), anyString())).thenReturn(terminationResult(
                RunControlService.TransitionResult.APPLIED));
        when(runs.complete(anyString(), anyInt(), anyDouble(), anyInt()))
                .thenReturn(RunControlService.TransitionResult.APPLIED);
        when(runs.fail(anyString(), any(), nullable(String.class)))
                .thenReturn(RunControlService.TransitionResult.APPLIED);
        when(runs.cancel(anyString())).thenReturn(RunControlService.TransitionResult.APPLIED);
        when(tools.executeTaskDetached(eq(tool), same(input), anyString(), any(), any()))
                .thenAnswer(call -> {
                    childContext.set(call.getArgument(3));
                    return ToolExecutionResult.of(ToolResult.success("shell output"));
                });
    }

    @Test
    void cancellationClaimIsRetriedAfterItsFinalizationThrows() throws Exception {
        when(tools.executeTaskDetached(eq(tool), same(input), anyString(), any(), any()))
                .thenReturn(ToolExecutionResult.of(ToolResult.cancelled("FIXTURE_CANCELLED", "cancelled",
                        ToolResult.EffectState.UNKNOWN)));
        // The first call has committed CANCELLING but fails before final Run persistence.
        // Retrying that already-claimed Run returns INVALID_TRANSITION, not APPLIED.
        when(termination.terminate(anyString(), any(), anyString()))
                .thenThrow(new IllegalStateException("terminal persistence unavailable after cancellation claim"))
                .thenReturn(terminationResult(RunControlService.TransitionResult.INVALID_TRANSITION));
        AsyncExecution execution = start();
        try {
            assertEquals(ToolResult.ExecutionStatus.CANCELLED,
                    execution.result.get(3, TimeUnit.SECONDS).result().executionStatus());
            String runId = childRun.get().id();
            verify(termination, atLeast(2)).terminate(eq(runId), eq(RunEnvelope.RunExitReason.USER_CANCELLED), anyString());
            verify(runs, atLeastOnce()).cancel(runId);
            verify(executions).unregister(runId);
            verify(sessions).closeSubAgentSession(childRun.get().sessionId());
            verify(parentLease).close();
        } finally {
            // Even a regression must leave no indefinitely waiting fixture thread.
            doReturn(terminationResult(RunControlService.TransitionResult.ALREADY_TERMINAL))
                    .when(termination).terminate(anyString(), any(), anyString());
            execution.thread.interrupt();
            execution.thread.join(3000);
        }
    }

    @Test
    void failedFinalCancellationWriteCannotLeakExitedRunOrSession() {
        AtomicBoolean persistenceAvailable = new AtomicBoolean();
        when(tools.executeTaskDetached(eq(tool), same(input), anyString(), any(), any()))
                .thenReturn(ToolExecutionResult.of(ToolResult.cancelled("FIXTURE_CANCELLED", "cancelled",
                        ToolResult.EffectState.UNKNOWN)));
        when(runs.cancel(anyString())).thenAnswer(call -> {
            if (!persistenceAvailable.get()) {
                throw new IllegalStateException("final cancellation persistence unavailable");
            }
            return RunControlService.TransitionResult.APPLIED;
        });

        assertThrows(IllegalStateException.class, () -> executor.execute(tool, input, "task-id", parent));

        RunEnvelope run = childRun.get();
        verify(runs, atLeast(2)).cancel(run.id());
        verify(executions).unregister(run.id());
        verify(sessions).closeSubAgentSession(run.sessionId());
        verify(parentLease).close();

        // The coordinator may retry its registered cleanup after persistence recovers.
        persistenceAvailable.set(true);
        assertDoesNotThrow(() -> cleanup.get().run());
        verify(runs, atLeast(3)).cancel(run.id());
        verify(executions).unregister(run.id());
        verify(sessions).closeSubAgentSession(run.sessionId());
        verify(parentLease).close();
        verify(termination, never()).terminate(eq("parent-run"), any(), anyString());
    }

    @Test
    void failedRunRegistrationNeverStartsToolsAndClosesOnlyItsCreatedSession() {
        doThrow(new IllegalStateException("registry rejected startup"))
                .when(executions).register(anyString(), anyString(), any(AbortContext.class));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> executor.execute(tool, input, "task-id", parent));

        assertEquals("registry rejected startup", failure.getMessage());
        RunEnvelope run = childRun.get();
        verify(runs).fail(eq(run.id()), eq(RunEnvelope.RunExitReason.INTERNAL_ERROR),
                contains("registration failed before execution"));
        verify(sessions).closeSubAgentSession(run.sessionId());
        verify(parentLease).close();
        verify(executions, never()).unregister(anyString());
        verifyNoInteractions(tools, processes, termination);
        cleanup.get().run();
        verifyNoInteractions(processes, termination);
    }

    @Test
    void cancellationAfterReturnKeepsExactChildOwnership() {
        ToolExecutionResult result = executor.execute(tool, input, "task-id", parent);
        assertEquals(ToolResult.ExecutionStatus.SUCCEEDED, result.result().executionStatus());
        RunEnvelope run = childRun.get();
        ToolUseContext context = childContext.get();
        assertEquals("/workspace/sub", context.workingDirectory());
        assertEquals(run.sessionId(), context.sessionId());
        assertEquals(run.id(), context.currentRunId());
        assertEquals("parent-session", context.parentSessionId());
        assertNotEquals(parent.sessionId(), context.sessionId());
        assertNotEquals(parent.currentRunId(), context.currentRunId());
        verify(processes, never()).cancelSessionBackground(anyString());
        when(termination.terminate(eq(run.id()), any(), anyString()))
                .thenReturn(terminationResult(RunControlService.TransitionResult.ALREADY_TERMINAL));

        cleanup.get().run();

        verify(termination).terminate(eq(run.id()), eq(RunEnvelope.RunExitReason.USER_CANCELLED), anyString());
        verify(tools).finishTaskCancellation(same(context));
        verify(processes).cancelSessionBackground(run.sessionId());
        verify(processes, never()).cancelSessionBackground("parent-session");
        verify(termination, never()).terminate(eq("parent-run"), any(), anyString());
        verify(executions, never()).beginCompletion("parent-run");
        verify(parentLease).close();
    }

    @Test
    void failedExitInspectionRetainsSessionAndParentLeaseUntilConfirmed() throws Exception {
        AtomicBoolean inspectionAvailable = new AtomicBoolean();
        CountDownLatch attemptedInspection = new CountDownLatch(1);
        when(processes.currentRunTermination(anyString())).thenAnswer(call -> {
            if (!inspectionAvailable.get()) {
                attemptedInspection.countDown();
                throw new IllegalStateException("ownership inspection unavailable");
            }
            return stopped();
        });
        AsyncExecution execution = start();
        try {
            assertTrue(attemptedInspection.await(3, TimeUnit.SECONDS));
            assertFalse(execution.result.isDone());
            verify(sessions, never()).closeSubAgentSession(anyString());
            verify(executions, never()).unregister(anyString());
            verify(parentLease, never()).close();

            inspectionAvailable.set(true);
            assertEquals(ToolResult.ExecutionStatus.SUCCEEDED,
                    execution.result.get(3, TimeUnit.SECONDS).result().executionStatus());
            verify(sessions).closeSubAgentSession(childRun.get().sessionId());
            verify(executions).unregister(childRun.get().id());
            verify(parentLease).close();
        } finally {
            inspectionAvailable.set(true);
            execution.thread.interrupt();
            execution.thread.join(3000);
        }
    }

    @Test
    void cancellationAcceptedBeforeStartupCreatesNoRunOrExecution() {
        doAnswer(call -> {
            Consumer<Boolean> request = call.getArgument(1);
            requestCancellation.set(request);
            request.accept(false);
            return null;
        }).when(tasks).registerCancellationRequest(eq("task-id"), any());

        ToolResult result = executor.execute(tool, input, "task-id", parent).result();

        assertEquals(ToolResult.ExecutionStatus.CANCELLED, result.executionStatus());
        assertEquals(ToolResult.EffectState.NOT_STARTED, result.effectState());
        verify(sessions, never()).registerSubAgentSession(anyString(), anyString(), anyString());
        verify(sessions, never()).closeSubAgentSession(anyString());
        verifyNoInteractions(runs, executions, tools, processes, termination, shells);
        verify(parentLease).close();
    }

    @Test
    void timeoutReasonSurvivesRepeatedCancellationAndCleanup() {
        when(tools.executeTaskDetached(eq(tool), same(input), anyString(), any(), any()))
                .thenAnswer(call -> {
                    requestCancellation.get().accept(true);
                    requestCancellation.get().accept(false);
                    return ToolExecutionResult.of(ToolResult.cancelled("FIXTURE_CANCELLED", "cancelled",
                            ToolResult.EffectState.UNKNOWN));
                });

        executor.execute(tool, input, "task-id", parent);

        String runId = childRun.get().id();
        verify(termination).terminate(eq(runId), eq(RunEnvelope.RunExitReason.DEADLINE_EXCEEDED), anyString());
        verify(runs, atLeastOnce()).fail(eq(runId), eq(RunEnvelope.RunExitReason.DEADLINE_EXCEEDED), anyString());
        verify(runs, never()).cancel(anyString());
        verify(parentLease).close();
    }

    private AsyncExecution start() {
        CompletableFuture<ToolExecutionResult> result = new CompletableFuture<>();
        Thread thread = Thread.ofVirtual().start(() -> {
            try { result.complete(executor.execute(tool, input, "task-id", parent)); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
        });
        return new AsyncExecution(thread, result);
    }

    private static ManagedProcessRunner.CancelSummary stopped() {
        return new ManagedProcessRunner.CancelSummary(0, 0, 0);
    }

    private static RunTerminationCoordinator.Result terminationResult(RunControlService.TransitionResult transition) {
        return new RunTerminationCoordinator.Result(transition, stopped(), true);
    }

    private record AsyncExecution(Thread thread, CompletableFuture<ToolExecutionResult> result) { }
}
