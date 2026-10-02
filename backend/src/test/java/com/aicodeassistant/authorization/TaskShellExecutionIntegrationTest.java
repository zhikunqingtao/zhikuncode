package com.aicodeassistant.authorization;

import com.aicodeassistant.engine.AbortContext;
import com.aicodeassistant.interaction.InteractionRequest;
import com.aicodeassistant.model.PermissionMode;
import com.aicodeassistant.model.TaskStatus;
import com.aicodeassistant.run.RunControlService;
import com.aicodeassistant.run.RunExecutionRegistry;
import com.aicodeassistant.run.RunTerminationCoordinator;
import com.aicodeassistant.session.SessionExecutionGate;
import com.aicodeassistant.session.SessionManager;
import com.aicodeassistant.tool.StreamingToolExecutor;
import com.aicodeassistant.tool.ToolExecutionPipeline;
import com.aicodeassistant.tool.ToolExecutionResult;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import com.aicodeassistant.tool.bash.ShellStateManager;
import com.aicodeassistant.tool.process.ManagedProcessRunner;
import com.aicodeassistant.tool.task.TaskCoordinator;
import com.aicodeassistant.tool.task.TaskExecutionResult;
import com.aicodeassistant.tool.task.TaskShellExecutor;
import com.aicodeassistant.tool.task.TaskState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Real Task/Run/SQLite/approval lifecycle; process fixtures write only inside the temporary workspace. */
@Timeout(30)
class TaskShellExecutionIntegrationTest {
    @TempDir Path temp;

    @Test
    void stopCancelsDurableApprovalAndLateAllowCannotAffectParentOrSibling() throws Exception {
        try (Fixture f = new Fixture(temp, PermissionMode.DEFAULT)) {
            CountDownLatch siblingStarted = new CountDownLatch(1);
            CountDownLatch releaseSibling = new CountDownLatch(1);
            AtomicBoolean siblingInterrupted = new AtomicBoolean();
            ToolUseContext siblingContext = f.parent.withToolUseId("parent-sibling");
            doAnswer(invocation -> {
                siblingStarted.countDown();
                try { releaseSibling.await(); }
                catch (InterruptedException interrupted) { siblingInterrupted.set(true); }
                return ToolResult.success("sibling finished");
            }).when(f.authorization.tool).call(any(), eq(siblingContext));
            var sibling = f.streaming.newSession(siblingContext);
            try {
                sibling.addTool(f.authorization.tool, f.authorization.input,
                        siblingContext.toolUseId(), siblingContext);
                f.authorization.approve(f.authorization.deliver(f.authorization.created(siblingContext)));
                assertThat(siblingStarted.await(5, TimeUnit.SECONDS)).isTrue();

                TaskState state = f.submit("waiting-approval");
                ToolUseContext child = f.child.get(5, TimeUnit.SECONDS);
                InteractionRequest pending = f.authorization.deliver(f.authorization.created(child));
                assertThat(child.currentRunId()).isNotEqualTo(f.parent.currentRunId());
                assertThat(f.tasks.cancelTask(state.getTaskId(), f.parent.sessionId()))
                        .isEqualTo(TaskCoordinator.CancellationResult.REQUESTED);

                assertThat(f.authorization.interactions.findById(pending.interactionId()).status())
                        .isEqualTo(InteractionRequest.Status.CANCELLED);
                assertThat(f.authorization.approve(pending).status()).isEqualTo(InteractionRequest.Status.CANCELLED);
                state.getFuture().get(10, TimeUnit.SECONDS);
                assertThat(state.snapshot().status()).isEqualTo(TaskStatus.CANCELLED);
                assertThat(state.snapshot().terminationConfirmed()).isTrue();
                assertThat(f.executions.isRegistered(child.currentRunId())).isFalse();
                verify(f.authorization.tool, never()).call(any(), eq(child));
                verify(f.sessions).closeSubAgentSession(child.sessionId());
                assertThat(f.authorization.started(child)).isZero();
                assertThat(f.authorization.state(f.parent.currentRunId())).isEqualTo("running");
                assertThat(f.executions.isRegistered(f.parent.currentRunId())).isTrue();
                assertThat(f.parentAbort.isAborted()).isFalse();
                assertThat(siblingInterrupted.get()).isFalse();
                assertThat(sibling.hasUnfinishedTools()).isTrue();
            } finally {
                releaseSibling.countDown();
                awaitFinished(sibling);
            }
            assertThat(sibling.yieldCompleted().getFirst().getResult().content()).isEqualTo("sibling finished");
        }
    }

    @Test
    void childOutlivesParentCompletionAndInheritsEffectiveShellDirectory() throws Exception {
        try (Fixture f = new Fixture(temp, PermissionMode.AUTO_APPROVE)) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicBoolean interrupted = new AtomicBoolean();
            doAnswer(invocation -> {
                started.countDown();
                try { release.await(); }
                catch (InterruptedException cancelled) { interrupted.set(true); }
                return ToolResult.success("child result");
            }).when(f.authorization.tool).call(any(), any());
            TaskState state = f.submit("outlive-parent");
            try {
                ToolUseContext child = f.child.get(5, TimeUnit.SECONDS);
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(child.workingDirectory()).isEqualTo(f.effectiveCwd.toString());
                assertThat(child.sessionId()).isNotEqualTo(f.parent.sessionId());
                assertThat(child.parentSessionId()).isEqualTo(f.parent.sessionId());
                verify(f.sessions).registerSubAgentSession(child.sessionId(),
                        f.effectiveCwd.toString(), f.parent.sessionId());
                assertThat(f.authorization.runs.complete(f.parent.currentRunId(), 0, 0, 0))
                        .isEqualTo(RunControlService.TransitionResult.APPLIED);
                f.executions.unregister(f.parent.currentRunId());

                assertThat(f.executions.isRegistered(f.parent.currentRunId())).isFalse();
                assertThat(f.executions.isRegistered(child.currentRunId())).isTrue();
                assertThat(interrupted.get()).isFalse();
                assertThat(state.snapshot().status()).isEqualTo(TaskStatus.RUNNING);
                assertThat(state.getFuture().isDone()).isFalse();
                verify(f.sessions, never()).closeSubAgentSession(child.sessionId());
                f.assertParentLeaseHeld();
                release.countDown();
                state.getFuture().get(10, TimeUnit.SECONDS);

                assertThat(state.snapshot().status()).isEqualTo(TaskStatus.COMPLETED);
                assertThat(state.snapshot().output()).isEqualTo("child result");
                assertThat(f.authorization.state(child.currentRunId())).isEqualTo("completed");
                assertThat(f.executions.isRegistered(child.currentRunId())).isFalse();
                verify(f.sessions).closeSubAgentSession(child.sessionId());
                f.assertParentLeaseReleased();
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void ownedOsProcessSurvivesParentCompletionAndReleasesResourcesAfterNaturalExit() throws Exception {
        try (Fixture f = new Fixture(temp, PermissionMode.AUTO_APPROVE)) {
            CompletableFuture<ManagedProcessRunner.Result> processResult = new CompletableFuture<>();
            doAnswer(invocation -> {
                ToolUseContext context = invocation.getArgument(1);
                var result = f.processes.run(new ManagedProcessRunner.Request(List.of("bash", "-c", """
                        printf '%s\\n' "$$" > process.pid
                        printf ready > process.ready
                        while [ ! -e process.release ]; do sleep 0.02; done
                        printf 'child-process-output'
                        """), Path.of(context.workingDirectory()), Duration.ofSeconds(12),
                        context.currentRunId(), context.toolUseId()));
                processResult.complete(result);
                return result.exitCode() == 0 && !result.cancelled() && !result.timedOut()
                        ? ToolResult.success(result.stdout()) : ToolResult.internalError("FIXTURE_PROCESS_FAILED",
                                "Fixture process did not finish normally", ToolResult.EffectState.UNKNOWN);
            }).when(f.authorization.tool).call(any(), any());
            TaskState state = f.submit("real-process-outlives-parent");
            try {
                ToolUseContext child = f.child.get(5, TimeUnit.SECONDS);
                Path ready = f.effectiveCwd.resolve("process.ready");
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!Files.exists(ready) && System.nanoTime() < deadline) Thread.sleep(10);
                assertThat(ready).exists();
                long pid = Long.parseLong(Files.readString(f.effectiveCwd.resolve("process.pid")).trim());
                ProcessHandle owned = ProcessHandle.of(pid).orElseThrow();
                assertThat(owned.isAlive()).isTrue();
                assertThat(f.processes.currentTermination(child.currentRunId(), child.toolUseId()).activeCount())
                        .isEqualTo(1);

                assertThat(f.authorization.runs.complete(f.parent.currentRunId(), 0, 0, 0))
                        .isEqualTo(RunControlService.TransitionResult.APPLIED);
                f.executions.unregister(f.parent.currentRunId());

                assertThat(owned.isAlive()).isTrue();
                assertThat(state.getFuture().isDone()).isFalse();
                assertThat(f.executions.isRegistered(child.currentRunId())).isTrue();
                assertThat(f.processes.currentTermination(child.currentRunId(), child.toolUseId()).activeCount())
                        .isEqualTo(1);
                verify(f.sessions, never()).closeSubAgentSession(child.sessionId());
                f.assertParentLeaseHeld();
                Files.writeString(f.effectiveCwd.resolve("process.release"), "release");
                state.getFuture().get(10, TimeUnit.SECONDS);

                var completed = processResult.get(1, TimeUnit.SECONDS);
                assertThat(completed.exitCode()).isZero();
                assertThat(completed.cancelled()).isFalse();
                assertThat(completed.timedOut()).isFalse();
                assertThat(completed.terminationConfirmed()).isTrue();
                assertThat(state.snapshot().status()).isEqualTo(TaskStatus.COMPLETED);
                assertThat(state.snapshot().output()).isEqualTo("child-process-output");
                assertThat(owned.isAlive()).isFalse();
                assertThat(f.processes.currentRunTermination(child.currentRunId()).allTerminated()).isTrue();
                assertThat(f.executions.isRegistered(child.currentRunId())).isFalse();
                verify(f.sessions).closeSubAgentSession(child.sessionId());
                f.assertParentLeaseReleased();
            } finally {
                // Only release/cancel the process belonging to this isolated fixture.
                Files.writeString(f.effectiveCwd.resolve("process.release"), "cleanup");
                ToolUseContext child = f.child.getNow(null);
                if (child != null) f.processes.cancel(child.currentRunId(), child.toolUseId());
            }
        }
    }

    @Test
    void failedRunTerminationDoesNotCloseSessionOrReleaseTaskUntilWorkerActuallyExits() throws Exception {
        try (Fixture f = new Fixture(temp, PermissionMode.AUTO_APPROVE)) {
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch interrupted = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            doAnswer(invocation -> {
                started.countDown();
                awaitIgnoringInterrupts(release, interrupted);
                return ToolResult.success("worker actually exited");
            }).when(f.authorization.tool).call(any(), any());
            TaskState state = f.submit("slow-exit");
            try {
                ToolUseContext child = f.child.get(5, TimeUnit.SECONDS);
                assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(f.tasks.cancelTask(state.getTaskId(), f.parent.sessionId()))
                        .isEqualTo(TaskCoordinator.CancellationResult.REQUESTED);
                assertThat(interrupted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(f.authorization.state(child.currentRunId())).isEqualTo("failed");
                assertThat(state.snapshot().cancellationRequested()).isTrue();
                assertThat(state.snapshot().terminationConfirmed()).isFalse();
                assertThat(state.snapshot().status()).isEqualTo(TaskStatus.RUNNING);
                assertThat(state.getFuture().isDone()).isFalse();
                assertThat(f.executions.isRegistered(child.currentRunId())).isTrue();
                verify(f.sessions, never()).closeSubAgentSession(child.sessionId());
                f.assertParentLeaseHeld();

                release.countDown();
                state.getFuture().get(10, TimeUnit.SECONDS);
                assertThat(state.snapshot().status()).isEqualTo(TaskStatus.CANCELLED);
                assertThat(state.snapshot().terminationConfirmed()).isTrue();
                assertThat(f.executions.isRegistered(child.currentRunId())).isFalse();
                verify(f.sessions).closeSubAgentSession(child.sessionId());
                f.assertParentLeaseReleased();
                assertThat(f.parentAbort.isAborted()).isFalse();
            } finally {
                release.countDown();
            }
        }
    }

    private static void awaitIgnoringInterrupts(CountDownLatch release, CountDownLatch interrupted) {
        boolean restoreInterrupt = false;
        for (;;) {
            try { release.await(); break; }
            catch (InterruptedException cancellation) { restoreInterrupt = true; interrupted.countDown(); }
        }
        if (restoreInterrupt) Thread.currentThread().interrupt();
    }

    private static void awaitFinished(StreamingToolExecutor.ExecutionSession session) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (session.hasUnfinishedTools() && System.nanoTime() < deadline) {
            session.awaitAnyCompletion(50, TimeUnit.MILLISECONDS);
        }
        assertThat(session.hasUnfinishedTools()).isFalse();
    }

    private static final class Fixture implements AutoCloseable {
        final TaskRunAuthorizationCancellationTest.Fixture authorization;
        final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        final TaskCoordinator tasks = new TaskCoordinator(mock(SimpMessagingTemplate.class));
        final RunExecutionRegistry executions = new RunExecutionRegistry();
        final AbortContext parentAbort = new AbortContext();
        final SessionExecutionGate sessionGate = new SessionExecutionGate();
        final SessionManager sessions = mock(SessionManager.class);
        final ToolUseContext parent;
        final Path effectiveCwd;
        final ManagedProcessRunner processes;
        final StreamingToolExecutor streaming;
        final TaskShellExecutor shell;
        final CompletableFuture<ToolUseContext> child = new CompletableFuture<>();
        final List<TaskState> states = new ArrayList<>();

        Fixture(Path temp, PermissionMode mode) throws Exception {
            authorization = new TaskRunAuthorizationCancellationTest.Fixture(temp, mode);
            parent = ToolUseContext.of(temp.toString(), authorization.parent.sessionId())
                    .withCurrentRunId(authorization.parent.id()).withParentModel("test");
            executions.register(parent.currentRunId(), parent.sessionId(), parentAbort);
            processes = new ManagedProcessRunner(executions);
            ToolExecutionPipeline pipeline = mock(ToolExecutionPipeline.class);
            streaming = new StreamingToolExecutor(pipeline, meters, processes, executions);
            RunTerminationCoordinator termination = new RunTerminationCoordinator(authorization.runs,
                    authorization.interactions, processes, executions, streaming);
            ShellStateManager shellState = mock(ShellStateManager.class);
            effectiveCwd = Files.createDirectory(temp.resolve("effective-cwd"));
            when(shellState.resolveWorkingDirectory(parent.sessionId(), parent.workingDirectory()))
                    .thenReturn(effectiveCwd.toString());
            when(sessions.acquireBackgroundLease(parent.sessionId())).thenAnswer(invocation ->
                    sessionGate.acquireBackground(authorization.jdbc.getDataSource(), parent.sessionId()));
            shell = new TaskShellExecutor(tasks, streaming, authorization.runs, executions,
                    termination, processes, sessions, shellState);
            when(authorization.tool.getMaxExecutionTimeMs()).thenReturn(60_000L);
            when(pipeline.execute(eq(authorization.tool), any(), any(), any())).thenAnswer(invocation -> {
                ToolUseContext context = invocation.getArgument(2);
                if (!parent.currentRunId().equals(context.currentRunId())) child.complete(context);
                return ToolExecutionResult.of(authorization.gateway.execute(authorization.tool,
                        authorization.authorize(context), context));
            });
        }

        TaskState submit(String taskId) {
            TaskState state = tasks.submitResult(taskId, parent.sessionId(), "isolated test", () -> {
                ToolResult result = shell.execute(authorization.tool, authorization.input, taskId, parent).result();
                return switch (result.executionStatus()) {
                    case SUCCEEDED -> TaskExecutionResult.completed(result.content());
                    case CANCELLED -> TaskExecutionResult.cancelled(result.content(), result.failureCode());
                    default -> TaskExecutionResult.failed(result.content(), result.failureCode());
                };
            });
            states.add(state);
            return state;
        }

        void assertParentLeaseHeld() {
            assertThat(sessionGate.tryAcquireMerge(authorization.jdbc.getDataSource(), parent.sessionId(), "test-merge"))
                    .isNull();
        }

        void assertParentLeaseReleased() {
            try (var lease = sessionGate.tryAcquireMerge(authorization.jdbc.getDataSource(), parent.sessionId(), "test-merge")) {
                assertThat(lease).isNotNull();
            }
        }

        @Override public void close() throws Exception {
            try {
                tasks.cleanup();
                for (TaskState state : states) state.getFuture().get(10, TimeUnit.SECONDS);
                executions.unregister(parent.currentRunId());
            } finally {
                meters.close();
                authorization.close();
            }
        }
    }
}
