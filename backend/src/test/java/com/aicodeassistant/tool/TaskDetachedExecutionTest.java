package com.aicodeassistant.tool;

import com.aicodeassistant.tool.process.ManagedProcessRunner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskDetachedExecutionTest {
    private final ToolExecutionPipeline pipeline = mock(ToolExecutionPipeline.class);
    private final ManagedProcessRunner processes = mock(ManagedProcessRunner.class);
    private final StreamingToolExecutor executor = new StreamingToolExecutor(
            pipeline, new SimpleMeterRegistry(), processes);
    private final ToolUseContext context = ToolUseContext.of("/tmp", "session").withCurrentRunId("parent-run");

    private Tool tool() {
        Tool tool = mock(Tool.class);
        when(tool.getName()).thenReturn("TaskFixture");
        when(tool.getMaxExecutionTimeMs()).thenReturn(60_000L);
        return tool;
    }

    @Test
    void cancellationWaitsForActualWorkerAndLeavesSiblingRunning() throws Exception {
        Tool tool = tool();
        CountDownLatch started = new CountDownLatch(2);
        CountDownLatch targetInterrupted = new CountDownLatch(1);
        CountDownLatch releaseTarget = new CountDownLatch(1);
        CountDownLatch releaseSibling = new CountDownLatch(1);
        AtomicBoolean siblingInterrupted = new AtomicBoolean();
        when(processes.currentTermination("parent-run", "target"))
                .thenReturn(new ManagedProcessRunner.CancelSummary(0, 0, 0));
        when(pipeline.execute(eq(tool), any(), any(), any())).thenAnswer(invocation -> {
            ToolUseContext owner = invocation.getArgument(2);
            started.countDown();
            if (owner.toolUseId().equals("target")) {
                awaitIgnoringInterrupts(releaseTarget, targetInterrupted);
            } else {
                try { releaseSibling.await(); }
                catch (InterruptedException interrupted) { siblingInterrupted.set(true); }
            }
            return ToolExecutionResult.of(ToolResult.success("result-" + owner.toolUseId()));
        });
        var sibling = executor.newSession(context);
        CompletableFuture<ToolExecutionResult> result = new CompletableFuture<>();
        Thread owner = Thread.ofVirtual().start(() -> complete(result, () ->
                executor.executeTaskDetached(tool, ToolInput.from(Map.of()), "target", context)));
        try {
            sibling.addTool(tool, ToolInput.from(Map.of()), "sibling", context);
            assertTrue(started.await(3, TimeUnit.SECONDS));
            owner.interrupt();
            assertTrue(targetInterrupted.await(3, TimeUnit.SECONDS));
            assertFalse(result.isDone(), "interrupt is not proof of worker exit");
            assertTrue(sibling.hasUnfinishedTools());
            assertFalse(siblingInterrupted.get());
            releaseTarget.countDown();
            ToolResult cancelled = result.get(3, TimeUnit.SECONDS).result();
            assertEquals(ToolResult.ExecutionStatus.CANCELLED, cancelled.executionStatus());
            assertEquals(true, cancelled.metadata().get("terminationConfirmed"));
            verify(processes, atLeastOnce()).cancel("parent-run", "target");
            verify(processes, never()).cancel(eq("parent-run"), eq("sibling"));
            verify(processes, never()).cancelRun(anyString());
            assertTrue(sibling.hasUnfinishedTools());
        } finally {
            releaseTarget.countDown();
            releaseSibling.countDown();
            owner.interrupt();
            owner.join(3000);
            awaitCompleted(sibling);
        }
    }

    @Test
    void lateProcessRegistrationIsCancelledAfterWorkerExit() throws Exception {
        Tool tool = tool();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        CountDownLatch retainedObserved = new CountDownLatch(1);
        AtomicBoolean registered = new AtomicBoolean();
        AtomicBoolean allowCleanup = new AtomicBoolean();
        when(processes.cancel("parent-run", "target")).thenAnswer(call -> {
            if (registered.get() && allowCleanup.get()) registered.set(false);
            return !registered.get();
        });
        when(processes.currentTermination("parent-run", "target")).thenAnswer(call -> {
            int retained = registered.get() ? 1 : 0;
            if (retained == 1) retainedObserved.countDown();
            return new ManagedProcessRunner.CancelSummary(retained, 0, retained);
        });
        when(pipeline.execute(eq(tool), any(), any(), any())).thenAnswer(call -> {
            started.countDown();
            awaitIgnoringInterrupts(releaseWorker, interrupted);
            registered.set(true); // The first cancellation scan saw no process yet.
            return ToolExecutionResult.of(ToolResult.success("worker exited"));
        });
        CompletableFuture<ToolExecutionResult> result = new CompletableFuture<>();
        Thread owner = Thread.ofVirtual().start(() -> complete(result, () ->
                executor.executeTaskDetached(tool, ToolInput.from(Map.of()), "target", context)));
        try {
            assertTrue(started.await(3, TimeUnit.SECONDS));
            owner.interrupt();
            assertTrue(interrupted.await(3, TimeUnit.SECONDS));
            releaseWorker.countDown();
            assertTrue(retainedObserved.await(3, TimeUnit.SECONDS));
            assertFalse(result.isDone(), "worker exit alone must not release retained process ownership");
            allowCleanup.set(true);
            assertEquals(ToolResult.ExecutionStatus.CANCELLED,
                    result.get(3, TimeUnit.SECONDS).result().executionStatus());
            assertFalse(registered.get());
            verify(processes, never()).cancelRun(anyString());
        } finally {
            allowCleanup.set(true);
            releaseWorker.countDown();
            owner.interrupt();
            owner.join(3000);
        }
    }

    @Test
    void successfulWorkerWaitsForRetainedOwnershipWithoutRequestingCancellation() throws Exception {
        Tool tool = tool();
        AtomicBoolean retained = new AtomicBoolean(true);
        CountDownLatch inspected = new CountDownLatch(1);
        when(pipeline.execute(eq(tool), any(), any(), any()))
                .thenReturn(ToolExecutionResult.of(ToolResult.success("saved output")));
        when(processes.currentTermination("parent-run", "target")).thenAnswer(call -> {
            inspected.countDown();
            int count = retained.get() ? 1 : 0;
            return new ManagedProcessRunner.CancelSummary(count, 0, count);
        });
        CompletableFuture<ToolExecutionResult> result = new CompletableFuture<>();
        Thread owner = Thread.ofVirtual().start(() -> complete(result, () ->
                executor.executeTaskDetached(tool, ToolInput.from(Map.of()), "target", context)));
        try {
            assertTrue(inspected.await(3, TimeUnit.SECONDS));
            assertFalse(result.isDone());
            retained.set(false);
            assertEquals("saved output", result.get(3, TimeUnit.SECONDS).result().content());
            verify(processes, never()).cancel(anyString(), anyString());
            verify(processes, never()).cancelRun(anyString());
        } finally {
            retained.set(false);
            owner.interrupt();
            owner.join(3000);
        }
    }

    @Test
    void terminationInspectionFailureCannotReleaseTaskUntilInspectionRecovers() throws Exception {
        Tool tool = tool();
        AtomicBoolean unavailable = new AtomicBoolean(true);
        CountDownLatch inspected = new CountDownLatch(1);
        when(pipeline.execute(eq(tool), any(), any(), any()))
                .thenReturn(ToolExecutionResult.of(ToolResult.success("completed")));
        when(processes.currentTermination("parent-run", "target")).thenAnswer(call -> {
            inspected.countDown();
            if (unavailable.get()) throw new IllegalStateException("inspection temporarily unavailable");
            return new ManagedProcessRunner.CancelSummary(0, 0, 0);
        });
        CompletableFuture<ToolExecutionResult> result = new CompletableFuture<>();
        Thread owner = Thread.ofVirtual().start(() -> complete(result, () ->
                executor.executeTaskDetached(tool, ToolInput.from(Map.of()), "target", context)));
        try {
            assertTrue(inspected.await(3, TimeUnit.SECONDS));
            assertFalse(result.isDone());
            unavailable.set(false);
            assertEquals(ToolResult.ExecutionStatus.SUCCEEDED,
                    result.get(3, TimeUnit.SECONDS).result().executionStatus());
            verify(processes, never()).cancelRun(anyString());
        } finally {
            unavailable.set(false);
            owner.interrupt();
            owner.join(3000);
        }
    }

    @Test
    void alreadyInterruptedOwnerDoesNotLaunchTool() throws Exception {
        CompletableFuture<ToolExecutionResult> result = new CompletableFuture<>();
        Thread owner = Thread.ofVirtual().start(() -> {
            Thread.currentThread().interrupt();
            complete(result, () -> executor.executeTaskDetached(tool(), ToolInput.from(Map.of()), "target", context));
        });
        ToolResult cancelled = result.get(3, TimeUnit.SECONDS).result();
        owner.join(3000);
        assertEquals(ToolResult.ExecutionStatus.CANCELLED, cancelled.executionStatus());
        assertEquals(ToolResult.EffectState.NOT_STARTED, cancelled.effectState());
        verifyNoInteractions(pipeline, processes);
    }

    @Test
    void missingOwnershipFailsBeforeLaunchingTool() {
        ToolResult rejected = executor.executeTaskDetached(tool(), ToolInput.from(Map.of()),
                "target", ToolUseContext.of("/tmp", "session")).result();
        assertEquals("TASK_EXECUTION_OWNERSHIP_MISSING", rejected.failureCode());
        assertEquals(ToolResult.EffectState.NOT_STARTED, rejected.effectState());
        verifyNoInteractions(pipeline, processes);
    }

    private static void awaitIgnoringInterrupts(CountDownLatch release, CountDownLatch interrupted) {
        boolean restore = false;
        for (;;) {
            try { release.await(); break; }
            catch (InterruptedException ignored) { restore = true; interrupted.countDown(); }
        }
        if (restore) Thread.currentThread().interrupt();
    }

    private static void awaitCompleted(StreamingToolExecutor.ExecutionSession session) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (session.hasUnfinishedTools() && System.nanoTime() < deadline) {
            session.awaitAnyCompletion(10, TimeUnit.MILLISECONDS);
        }
        assertFalse(session.hasUnfinishedTools());
    }

    private static void complete(CompletableFuture<ToolExecutionResult> result,
                                 java.util.concurrent.Callable<ToolExecutionResult> body) {
        try { result.complete(body.call()); }
        catch (Throwable failure) { result.completeExceptionally(failure); }
    }
}
