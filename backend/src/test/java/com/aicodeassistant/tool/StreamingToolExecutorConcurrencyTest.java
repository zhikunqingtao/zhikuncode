package com.aicodeassistant.tool;

import com.aicodeassistant.run.RunExecutionRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Timeout(15)
class StreamingToolExecutorConcurrencyTest {
    @Test
    void aClaimCannotReuseTheHeadThatAnotherWorkerAlreadyRemoved() throws Exception {
        var pipeline = mock(ToolExecutionPipeline.class);
        var executor = new StreamingToolExecutor(pipeline, new SimpleMeterRegistry());
        var first = tool("First", false, false);
        var second = tool("Second", true, false);
        var firstStarted = new CountDownLatch(1);
        var finishFirst = new CountDownLatch(1);
        var checkingSecond = new CountDownLatch(1);
        var finishCheck = new CountDownLatch(1);
        var firstWorker = new AtomicReference<Thread>();
        var submitter = new AtomicReference<Thread>();
        var blockedOnce = new AtomicBoolean();
        var secondExecutions = new AtomicInteger();
        var submitted = new AtomicReference<Throwable>();
        when(first.isConcurrencySafe(any())).thenReturn(false);
        when(second.isConcurrencySafe(any())).thenAnswer(inv -> {
            if (Thread.currentThread() == submitter.get() && blockedOnce.compareAndSet(false, true)) {
                checkingSecond.countDown();
                await(finishCheck);
            }
            return true;
        });
        when(pipeline.execute(any(), any(), any(), any())).thenAnswer(inv -> {
            if (inv.getArgument(0) == first) {
                firstWorker.set(Thread.currentThread());
                firstStarted.countDown();
                await(finishFirst);
            } else {
                secondExecutions.incrementAndGet();
            }
            return ToolExecutionResult.of(ToolResult.success("done"));
        });
        var context = ToolUseContext.of("/tmp", "claim-race");
        var session = executor.newSession(context);
        Thread producer = Thread.ofVirtual().unstarted(() -> {
            submitter.set(Thread.currentThread());
            try {
                session.addTool(second, input(), "second", context);
            } catch (Throwable failure) {
                submitted.set(failure);
            }
        });
        try {
            session.addTool(first, input(), "first", context);
            await(firstStarted);
            producer.start();
            await(checkingSecond);
            finishFirst.countDown();
            // Old code lets the first worker remove/execute "second" while the producer
            // still holds the old peek. The fixed code blocks that worker on the state lock.
            awaitCondition(() -> !firstWorker.get().isAlive() || blockedInExecutor(firstWorker.get()));
            finishCheck.countDown();
            join(producer);
            assertNull(submitted.get());
            awaitQuiescent(session);
            assertEquals(1, secondExecutions.get(), "The same queue head must not execute twice");
            assertEquals(List.of("first", "second"), ids(session.yieldCompleted()));
            assertTrue(session.yieldCompleted().isEmpty());
        } finally {
            finishFirst.countDown();
            finishCheck.countDown();
            session.discard();
            join(producer);
        }
    }

    @Test
    void safeToolsOverlapButAnExclusiveToolSeparatesTheFollowingSafeTool() throws Exception {
        var pipeline = mock(ToolExecutionPipeline.class);
        var executor = new StreamingToolExecutor(pipeline, new SimpleMeterRegistry());
        var safe = tool("Read", true, false);
        var exclusive = tool("Write", false, false);
        var safeStarted = new CountDownLatch(2);
        var releaseSafe = new CountDownLatch(1);
        var exclusiveStarted = new CountDownLatch(1);
        var releaseExclusive = new CountDownLatch(1);
        var safeActive = new AtomicInteger();
        var exclusiveActive = new AtomicBoolean();
        Queue<String> violations = new ConcurrentLinkedQueue<>();
        when(pipeline.execute(any(), any(), any(), any())).thenAnswer(inv -> {
            ToolUseContext context = inv.getArgument(2);
            if (inv.getArgument(0) == exclusive) {
                exclusiveActive.set(true);
                if (safeActive.get() != 0) violations.add("exclusive overlapped a safe tool");
                exclusiveStarted.countDown();
                await(releaseExclusive);
                exclusiveActive.set(false);
            } else {
                safeActive.incrementAndGet();
                if (exclusiveActive.get()) violations.add("safe tool overlapped exclusive");
                if (!context.toolUseId().equals("last")) {
                    safeStarted.countDown();
                    await(releaseSafe);
                }
                safeActive.decrementAndGet();
            }
            return ToolExecutionResult.of(ToolResult.success("done"));
        });
        var context = ToolUseContext.of("/tmp", "concurrency-contract");
        var session = executor.newSession(context);
        try {
            session.addTool(safe, input(), "first", context);
            session.addTool(safe, input(), "second", context);
            await(safeStarted); // Cannot pass if the fix serializes actual tool execution.
            session.addTool(exclusive, input(), "exclusive", context);
            session.addTool(safe, input(), "last", context);
            releaseSafe.countDown();
            await(exclusiveStarted);
            releaseExclusive.countDown();
            awaitQuiescent(session);
            assertTrue(violations.isEmpty(), violations.toString());
            assertEquals(List.of("first", "second", "exclusive", "last"), ids(session.yieldCompleted()));
            verify(pipeline, times(4)).execute(any(), any(), any(), any());
        } finally {
            releaseSafe.countDown();
            releaseExclusive.countDown();
            session.discard();
        }
    }

    @Test
    void discardInterruptsRegisteredWorkerAndSettlesQueuedAndLaterToolsOnce() throws Exception {
        var pipeline = mock(ToolExecutionPipeline.class);
        var meters = new SimpleMeterRegistry();
        var executor = new StreamingToolExecutor(pipeline, meters);
        var tool = tool("Blocking", false, false);
        var started = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(pipeline.execute(any(), any(), any(), any())).thenAnswer(inv -> {
            started.countDown();
            try {
                await(release);
                return ToolExecutionResult.of(ToolResult.success("released"));
            } catch (InterruptedException cancelled) {
                interrupted.countDown();
                return ToolExecutionResult.of(ToolResult.cancelled(
                        "INTERRUPTED", "interrupted", ToolResult.EffectState.UNKNOWN));
            }
        });
        var context = ToolUseContext.of("/tmp", "cancel-running");
        var session = executor.newSession(context);
        try {
            session.addTool(tool, input(), "running", context);
            await(started);
            session.addTool(tool, input(), "queued", context);
            session.discard();
            session.addTool(tool, input(), "late", context);
            await(interrupted);
            awaitQuiescent(session);
            var results = session.yieldCompleted();
            assertEquals(List.of("running", "queued", "late"), ids(results));
            assertEquals("INTERRUPTED", results.get(0).getResult().failureCode());
            assertEquals("TOOL_NOT_STARTED", results.get(1).getResult().failureCode());
            assertTrue(results.get(2).getResult().isError());
            assertEquals(ToolResult.EffectState.NOT_STARTED, results.get(2).getResult().effectState());
            assertTrue(session.yieldCompleted().isEmpty());
            verify(pipeline, times(1)).execute(any(), any(), any(), any());
            assertEquals(0, meters.get("zhiku.tool.virtual_threads.active").gauge().value());
        } finally {
            release.countDown();
            session.discard();
        }
    }

    @Test
    void discardBetweenClaimAndWorkerRegistrationPreventsPipelineExecution() throws Exception {
        var pipeline = mock(ToolExecutionPipeline.class);
        var runs = mock(RunExecutionRegistry.class);
        var lease = mock(RunExecutionRegistry.WorkLease.class);
        when(runs.acquireWork(eq("run"), eq("tool-session"), anyString())).thenReturn(lease);
        var meters = new SimpleMeterRegistry();
        var executor = new StreamingToolExecutor(pipeline, meters, null, runs);
        var tool = tool("NotStarted", false, false);
        var context = ToolUseContext.of("/tmp", "cancel-before-registration").withCurrentRunId("run");
        var session = executor.newSession(context);
        Field stateLockField = session.getClass().getDeclaredField("stateLock");
        stateLockField.setAccessible(true);
        Object stateLock = stateLockField.get(session);
        Field trackedField = session.getClass().getDeclaredField("tracked");
        trackedField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<StreamingToolExecutor.TrackedTool> tracked = (List<StreamingToolExecutor.TrackedTool>) trackedField.get(session);
        var claimed = new CountDownLatch(1);
        var allowStart = new CountDownLatch(1);
        var pauseOnce = new AtomicBoolean();
        var submitter = new AtomicReference<Thread>();
        var failure = new AtomicReference<Throwable>();
        when(tool.getName()).thenAnswer(inv -> {
            // Naming/logging the claimed tool happens outside stateLock, before Thread.start.
            // Hold that boundary so discard cannot rely on an already registered worker.
            if (Thread.currentThread() == submitter.get() && !Thread.holdsLock(stateLock)
                    && !tracked.isEmpty() && tracked.getFirst().getState() == StreamingToolExecutor.ToolState.EXECUTING
                    && pauseOnce.compareAndSet(false, true)) {
                claimed.countDown();
                await(allowStart);
            }
            return "NotStarted";
        });
        Thread producer = Thread.ofVirtual().start(() -> {
            submitter.set(Thread.currentThread());
            try {
                session.addTool(tool, input(), "call", context);
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });
        try {
            await(claimed);
            session.discard();
            assertFalse(session.isAllCompleted(), "A claimed worker remains owned until it actually exits");
            assertTrue(session.yieldCompleted().isEmpty());
            verify(lease, never()).close();
            allowStart.countDown();
            join(producer);
            assertNull(failure.get());
            awaitQuiescent(session);
            var results = session.yieldCompleted();
            assertEquals(List.of("call"), ids(results));
            assertEquals("TOOL_DISCARDED", results.getFirst().getResult().failureCode());
            assertEquals(ToolResult.EffectState.NOT_STARTED, results.getFirst().getResult().effectState());
            assertTrue(session.yieldCompleted().isEmpty());
            verifyNoInteractions(pipeline);
            verify(lease, times(1)).close();
            assertEquals(0, meters.get("zhiku.tool.virtual_threads.active").gauge().value());
        } finally {
            allowStart.countDown();
            session.discard();
            join(producer);
        }
    }

    private static Tool tool(String name, boolean safe, boolean highRisk) {
        var tool = mock(Tool.class);
        when(tool.getName()).thenReturn(name);
        when(tool.getMaxExecutionTimeMs()).thenReturn(60_000L);
        when(tool.isConcurrencySafe(any())).thenReturn(safe);
        when(tool.isHighRisk()).thenReturn(highRisk);
        return tool;
    }

    private static ToolInput input() { return ToolInput.from(Map.of()); }

    private static List<String> ids(List<StreamingToolExecutor.TrackedTool> tools) {
        return tools.stream().map(StreamingToolExecutor.TrackedTool::getToolUseId).toList();
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for the controlled interleaving");
    }

    private static void join(Thread thread) throws InterruptedException {
        thread.join(5000);
        assertFalse(thread.isAlive(), "Worker or caller did not exit");
    }

    private static boolean blockedInExecutor(Thread thread) {
        if (thread.getState() != Thread.State.BLOCKED) return false;
        StackTraceElement[] stack = thread.getStackTrace();
        return stack.length > 0 && stack[0].getClassName().startsWith(StreamingToolExecutor.class.getName());
    }

    private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            // Poll a concrete thread state; the test gates above, rather than a sleep,
            // establish the interleaving under test.
            TimeUnit.MILLISECONDS.sleep(1);
        }
        assertTrue(condition.getAsBoolean(), "Expected worker exit or contention on the executor state lock");
    }

    private static void awaitQuiescent(StreamingToolExecutor.ExecutionSession session) throws Exception {
        Field activeField = session.getClass().getDeclaredField("active");
        activeField.setAccessible(true);
        AtomicInteger active = (AtomicInteger) activeField.get(session);
        Field trackedField = session.getClass().getDeclaredField("tracked");
        trackedField.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<StreamingToolExecutor.TrackedTool> tracked = (List<StreamingToolExecutor.TrackedTool>) trackedField.get(session);
        Field exited = StreamingToolExecutor.TrackedTool.class.getDeclaredField("executionExited");
        exited.setAccessible(true);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            boolean allExited = true;
            for (var tool : tracked) allExited &= exited.getBoolean(tool);
            if (session.isAllCompleted() && active.get() == 0 && allExited) return;
            session.awaitAnyCompletion(10, TimeUnit.MILLISECONDS);
        }
        fail("Tool session did not become quiescent; pending=" + session.getPendingToolIds());
    }
}
