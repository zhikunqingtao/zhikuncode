package com.aicodeassistant.tool;

import com.aicodeassistant.run.RunExecutionRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StreamingToolExecutorErrorTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void queuedToolStillCompletesInOrderAfterExceptionOrError(boolean linkageError) throws Exception {
        var pipeline = mock(ToolExecutionPipeline.class);
        var executor = new StreamingToolExecutor(pipeline, new SimpleMeterRegistry());
        var tool = tool();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var secondWorker = new AtomicReference<Thread>();
        var failure = linkageError ? new NoClassDefFoundError("test/MissingOption") : new IllegalStateException("test failure");
        var uncaught = new AtomicReference<Throwable>();
        var firstWorker = new AtomicReference<Thread>();
        when(pipeline.execute(eq(tool), any(), any(), any())).thenAnswer(inv -> {
            ToolUseContext context = inv.getArgument(2);
            if (context.toolUseId().equals("first")) {
                firstWorker.set(Thread.currentThread());
                Thread.currentThread().setUncaughtExceptionHandler((thread, error) -> uncaught.set(error));
                started.countDown();
                release.await();
                throw failure;
            }
            secondWorker.set(Thread.currentThread());
            secondStarted.countDown();
            return ToolExecutionResult.of(ToolResult.success("second result"));
        });
        var context = ToolUseContext.of("/tmp", "session");
        var session = executor.newSession(context);
        try {
            session.addTool(tool, ToolInput.from(Map.of()), "first", context);
            assertTrue(started.await(2, TimeUnit.SECONDS));
            session.addTool(tool, ToolInput.from(Map.of()), "second", context);
            release.countDown();
            assertTrue(secondStarted.await(2, TimeUnit.SECONDS));
            firstWorker.get().join(2000);
            secondWorker.get().join(2000);
            assertTrue(session.isAllCompleted());
            var results = session.yieldCompleted();
            assertEquals(java.util.List.of("first", "second"), results.stream().map(StreamingToolExecutor.TrackedTool::getToolUseId).toList());
            assertTrue(results.getFirst().getResult().isError());
            assertEquals("second result", results.getLast().getResult().content());
            assertEquals(linkageError ? failure : null, uncaught.get());
            assertTrue(session.yieldCompleted().isEmpty());
            verify(pipeline, times(2)).execute(eq(tool), any(), any(), any());
        } finally {
            release.countDown();
            session.discard();
        }
    }

    @Test
    void linkageErrorPublishesOneFailureAndReleasesLeaseWithoutSwallowingError() throws Exception {
        var pipeline = mock(ToolExecutionPipeline.class);
        var runs = mock(RunExecutionRegistry.class);
        var lease = mock(RunExecutionRegistry.WorkLease.class);
        when(runs.acquireWork(eq("run"), eq("tool-session"), anyString())).thenReturn(lease);
        var meters = new SimpleMeterRegistry();
        var executor = new StreamingToolExecutor(pipeline, meters, null, runs);
        var tool = tool();
        var failure = new NoClassDefFoundError("test/MissingOption");
        var uncaught = new AtomicReference<Throwable>();
        var worker = new AtomicReference<Thread>();
        var started = new CountDownLatch(1);
        when(pipeline.execute(eq(tool), any(), any(), any())).thenAnswer(inv -> {
            worker.set(Thread.currentThread());
            Thread.currentThread().setUncaughtExceptionHandler((thread, error) -> uncaught.set(error));
            started.countDown();
            throw failure;
        });
        var context = ToolUseContext.of("/tmp", "session").withCurrentRunId("run");
        var session = executor.newSession(context);
        session.addTool(tool, ToolInput.from(Map.of()), "question", context);
        assertTrue(started.await(2, TimeUnit.SECONDS));
        worker.get().join(2000);
        assertFalse(worker.get().isAlive());
        assertSame(failure, uncaught.get());
        assertFalse(session.hasUnfinishedTools(), "Exited worker must not leave an EXECUTING tool");
        assertTrue(session.isAllCompleted());
        var results = session.yieldCompleted();
        assertEquals(1, results.size());
        var result = results.getFirst().getResult();
        assertEquals("TOOL_EXECUTION_TERMINATED_WITHOUT_RESULT", result.failureCode());
        assertEquals(ToolResult.EffectState.UNKNOWN, result.effectState());
        assertEquals(ToolResult.Retryability.NEVER, result.retryability());
        assertTrue(session.yieldCompleted().isEmpty());
        assertEquals(0, meters.get("zhiku.tool.virtual_threads.active").gauge().value());
        verify(lease, times(1)).close();
        verify(pipeline, times(1)).execute(eq(tool), any(), any(), any());
    }

    @Test
    void timingMetricFailureCannotLeakLeaseOrReplaceSuccessfulResult() throws Exception {
        var pipeline = mock(ToolExecutionPipeline.class);
        var runs = mock(RunExecutionRegistry.class);
        var lease = mock(RunExecutionRegistry.WorkLease.class);
        when(runs.acquireWork(eq("run"), eq("tool-session"), anyString())).thenReturn(lease);
        var meters = new SimpleMeterRegistry();
        meters.config().meterFilter(new MeterFilter() {
            @Override public Meter.Id map(Meter.Id id) {
                if (id.getName().equals("zhiku.tool.execution_time")) throw new IllegalStateException("test metric failure");
                return id;
            }
        });
        var executor = new StreamingToolExecutor(pipeline, meters, null, runs);
        var tool = tool();
        var expected = ToolResult.success("real result");
        var worker = new AtomicReference<Thread>();
        var started = new CountDownLatch(1);
        when(pipeline.execute(eq(tool), any(), any(), any())).thenAnswer(inv -> {
            worker.set(Thread.currentThread());
            Thread.currentThread().setUncaughtExceptionHandler((thread, error) -> { });
            started.countDown();
            return ToolExecutionResult.of(expected);
        });
        var context = ToolUseContext.of("/tmp", "session").withCurrentRunId("run");
        var session = executor.newSession(context);
        session.addTool(tool, ToolInput.from(Map.of()), "call", context);
        assertTrue(started.await(2, TimeUnit.SECONDS));
        worker.get().join(2000);
        assertFalse(worker.get().isAlive());
        assertSame(expected, session.yieldCompleted().getFirst().getResult());
        assertEquals(0, meters.get("zhiku.tool.virtual_threads.active").gauge().value());
        verify(lease, times(1)).close();
    }

    @Test
    void exceptionAfterAResultWasYieldedCannotReplaceOrPublishThatResultAgain() throws Exception {
        var pipeline = mock(ToolExecutionPipeline.class);
        var runs = mock(RunExecutionRegistry.class);
        var lease = mock(RunExecutionRegistry.WorkLease.class);
        when(runs.acquireWork(eq("run"), eq("tool-session"), anyString())).thenReturn(lease);
        var postProcessing = new CountDownLatch(1);
        var releasePostProcessing = new CountDownLatch(1);
        var meters = failingTotalCounterRegistry(postProcessing, releasePostProcessing);
        var executor = new StreamingToolExecutor(pipeline, meters, null, runs);
        var tool = tool();
        var expected = ToolResult.internalError("ORIGINAL_RESULT", "original result", ToolResult.EffectState.NONE);
        var worker = new AtomicReference<Thread>();
        when(pipeline.execute(eq(tool), any(), any(), any())).thenAnswer(inv -> {
            worker.set(Thread.currentThread());
            return ToolExecutionResult.of(expected);
        });
        var context = ToolUseContext.of("/tmp", "published-result").withCurrentRunId("run");
        var session = executor.newSession(context);
        try {
            session.addTool(tool, ToolInput.from(Map.of()), "call", context);
            assertTrue(postProcessing.await(5, TimeUnit.SECONDS));
            var firstYield = session.yieldCompleted();
            assertEquals(1, firstYield.size());
            assertSame(expected, firstYield.getFirst().getResult());
            assertEquals(StreamingToolExecutor.ToolState.YIELDED, firstYield.getFirst().getState());
            assertEquals(1, meters.get("zhiku.tool.virtual_threads.active").gauge().value());
            verify(lease, never()).close();

            releasePostProcessing.countDown();
            worker.get().join(5000);
            assertFalse(worker.get().isAlive());
            assertSame(expected, firstYield.getFirst().getResult(), "A published result must remain immutable");
            assertEquals(StreamingToolExecutor.ToolState.YIELDED, firstYield.getFirst().getState());
            assertTrue(session.yieldCompleted().isEmpty(), "A post-processing failure must not yield the tool twice");
            assertEquals(0, meters.get("zhiku.tool.virtual_threads.active").gauge().value());
            assertEquals(1, meters.get("zhiku.tool.executions.total").counter().count());
            assertEquals(0, meters.get("zhiku.tool.executions.errors").counter().count());
            verify(lease, times(1)).close();
            verify(pipeline, times(1)).execute(eq(tool), any(), any(), any());
        } finally {
            releasePostProcessing.countDown();
            session.discard();
        }
    }

    @Test
    void metricFailureAfterPublicationCannotSkipHighRiskFailureCascade() throws Exception {
        var pipeline = mock(ToolExecutionPipeline.class);
        var started = new CountDownLatch(1);
        var releasePipeline = new CountDownLatch(1);
        var postProcessing = new CountDownLatch(1);
        var releasePostProcessing = new CountDownLatch(1);
        var meters = failingTotalCounterRegistry(postProcessing, releasePostProcessing);
        var executor = new StreamingToolExecutor(pipeline, meters);
        var tool = tool();
        when(tool.isHighRisk()).thenReturn(true);
        var expected = ToolResult.internalError("ORIGINAL_RESULT", "original failure", ToolResult.EffectState.UNKNOWN);
        var worker = new AtomicReference<Thread>();
        when(pipeline.execute(eq(tool), any(), any(), any())).thenAnswer(inv -> {
            worker.set(Thread.currentThread());
            started.countDown();
            assertTrue(releasePipeline.await(5, TimeUnit.SECONDS));
            return ToolExecutionResult.of(expected);
        });
        var context = ToolUseContext.of("/tmp", "cascade-result");
        var session = executor.newSession(context);
        try {
            session.addTool(tool, ToolInput.from(Map.of()), "first", context);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            session.addTool(tool, ToolInput.from(Map.of()), "sibling", context);
            releasePipeline.countDown();
            assertTrue(postProcessing.await(5, TimeUnit.SECONDS));
            var first = session.yieldCompleted();
            assertEquals(1, first.size());
            assertSame(expected, first.getFirst().getResult());
            assertTrue(session.isDiscarded(), "Cascade must be committed before optional metrics run");

            releasePostProcessing.countDown();
            worker.get().join(5000);
            assertFalse(worker.get().isAlive());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((!session.isAllCompleted()
                    || meters.get("zhiku.tool.virtual_threads.active").gauge().value() != 0)
                    && System.nanoTime() < deadline) {
                session.awaitAnyCompletion(10, TimeUnit.MILLISECONDS);
            }
            assertTrue(session.isAllCompleted());
            var sibling = session.yieldCompleted();
            assertEquals(1, sibling.size());
            assertEquals("sibling", sibling.getFirst().getToolUseId());
            assertEquals(ToolResult.ExecutionStatus.CANCELLED, sibling.getFirst().getResult().executionStatus());
            assertEquals(ToolResult.EffectState.NOT_STARTED, sibling.getFirst().getResult().effectState());
            assertSame(expected, first.getFirst().getResult());
            assertTrue(session.yieldCompleted().isEmpty());
            assertEquals(0, meters.get("zhiku.tool.virtual_threads.active").gauge().value());
            assertEquals(1, meters.get("zhiku.tool.cascade_aborts").counter().count());
            verify(pipeline, times(1)).execute(any(), any(), any(), any());
        } finally {
            releasePipeline.countDown();
            releasePostProcessing.countDown();
            session.discard();
            meters.close();
        }
    }

    @Test
    void failedRiskClassificationPreventsExecutionAndDiscardsLaterTools() throws Exception {
        var pipeline = mock(ToolExecutionPipeline.class);
        var executor = new StreamingToolExecutor(pipeline, new SimpleMeterRegistry());
        var tool = tool();
        when(tool.isHighRisk()).thenThrow(new IllegalStateException("risk metadata unavailable"));
        var context = ToolUseContext.of("/tmp", "risk-classification");
        var session = executor.newSession(context);
        try {
            session.addTool(tool, ToolInput.from(Map.of()), "unclassified", context);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!session.isAllCompleted() && System.nanoTime() < deadline) {
                session.awaitAnyCompletion(10, TimeUnit.MILLISECONDS);
            }
            assertTrue(session.isAllCompleted());
            assertTrue(session.isDiscarded());
            session.addTool(tool(), ToolInput.from(Map.of()), "later", context);
            var results = session.yieldCompleted();
            assertEquals(2, results.size());
            assertEquals(ToolResult.ToolFailureType.INTERNAL, results.getFirst().getResult().failureType());
            assertEquals(ToolResult.EffectState.NOT_STARTED, results.getFirst().getResult().effectState());
            assertEquals(ToolResult.ExecutionStatus.CANCELLED, results.getLast().getResult().executionStatus());
            assertEquals(ToolResult.EffectState.NOT_STARTED, results.getLast().getResult().effectState());
            assertTrue(session.yieldCompleted().isEmpty());
            verifyNoInteractions(pipeline);
        } finally {
            session.discard();
        }
    }

    private static SimpleMeterRegistry failingTotalCounterRegistry(
            CountDownLatch postProcessing, CountDownLatch releasePostProcessing) {
        var failOnce = new AtomicBoolean(true);
        return new SimpleMeterRegistry() {
            @Override protected Counter newCounter(Meter.Id id) {
                Counter delegate = super.newCounter(id);
                if (!"zhiku.tool.executions.total".equals(id.getName())) return delegate;
                return new Counter() {
                    @Override public void increment(double amount) {
                        delegate.increment(amount);
                        if (!failOnce.compareAndSet(true, false)) return;
                        postProcessing.countDown();
                        try {
                            assertTrue(releasePostProcessing.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                        throw new IllegalStateException("post-completion metric failure");
                    }
                    @Override public double count() { return delegate.count(); }
                    @Override public Meter.Id getId() { return delegate.getId(); }
                };
            }
        };
    }

    private static Tool tool() {
        var tool = mock(Tool.class);
        when(tool.getName()).thenReturn("AskUserQuestion");
        when(tool.getMaxExecutionTimeMs()).thenReturn(60_000L);
        return tool;
    }
}
