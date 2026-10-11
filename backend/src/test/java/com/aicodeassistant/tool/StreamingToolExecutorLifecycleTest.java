package com.aicodeassistant.tool;

import com.aicodeassistant.engine.AbortContext;
import com.aicodeassistant.run.RunExecutionRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Timeout(20)
class StreamingToolExecutorLifecycleTest {
    @Test
    @ResourceLock("LOG4J_CONFIGURATION")
    void discardAfterWorkerRegistrationStillPreventsPipelineAdmission() throws Exception {
        var registered = new CountDownLatch(1);
        var releaseStartup = new CountDownLatch(1);
        var worker = new AtomicReference<Thread>();
        String toolUseId = "startup-cancellation-" + UUID.randomUUID();
        var appender = new AbstractAppender("startup-barrier-" + UUID.randomUUID(), null, null, true, null) {
            @Override public void append(LogEvent event) {
                if (!StreamingToolExecutor.class.getName().equals(event.getLoggerName())
                        || !toolUseId.equals(event.getContextData().getValue("toolUseId"))
                        || !event.getMessage().getFormattedMessage().startsWith("virtual thread started:")) return;
                worker.set(Thread.currentThread());
                registered.countDown();
                // A synchronous logging operation need not be interruptible. Keep
                // the interrupt set when this controlled startup boundary returns.
                awaitPreservingInterrupt(releaseStartup);
            }
        };
        var pipeline = mock(ToolExecutionPipeline.class);
        when(pipeline.execute(any(), any(), any(), any()))
                .thenReturn(ToolExecutionResult.of(ToolResult.success("unexpected execution")));
        var meters = new SimpleMeterRegistry();
        var executor = new StreamingToolExecutor(pipeline, meters);
        var context = ToolUseContext.of("/tmp", "startup-cancellation");
        var session = executor.newSession(context);
        try (var logging = new ExecutorLoggerScope(appender)) {
            try {
                session.addTool(tool("LifecycleStartup"), input(), toolUseId, context);
                await(registered);
                session.discard();
                releaseStartup.countDown();
                join(worker.get());

                verifyNoInteractions(pipeline);
                var results = session.yieldCompleted();
                assertEquals(1, results.size());
                assertEquals("TOOL_DISCARDED", results.getFirst().getResult().failureCode());
                assertEquals(ToolResult.EffectState.NOT_STARTED, results.getFirst().getResult().effectState());
                assertTrue(session.yieldCompleted().isEmpty());
                assertEquals(0, meters.get("zhiku.tool.virtual_threads.active").gauge().value());
            } finally {
                releaseStartup.countDown();
                session.discard();
                join(worker.get());
                meters.close();
            }
        }
    }

    @Test
    void aLaterToolInTheSameSessionRemainsOwnedAndCancellableAfterTheFirstWorkerExits() throws Exception {
        var pipeline = mock(ToolExecutionPipeline.class);
        var meters = new SimpleMeterRegistry();
        var runs = new RunExecutionRegistry();
        String runId = "lifecycle-run";
        String sessionId = "lifecycle-session";
        runs.register(runId, sessionId, new AbortContext());
        var executor = new StreamingToolExecutor(pipeline, meters, null, runs);
        var tool = tool("LifecycleOwned");
        var firstWorker = new AtomicReference<Thread>();
        var secondWorker = new AtomicReference<Thread>();
        var firstStarted = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var releaseSecond = new CountDownLatch(1);
        var secondInterrupted = new AtomicBoolean();
        when(pipeline.execute(any(), any(), any(), any())).thenAnswer(invocation -> {
            ToolUseContext executionContext = invocation.getArgument(2);
            if ("first".equals(executionContext.toolUseId())) {
                firstWorker.set(Thread.currentThread());
                firstStarted.countDown();
                return ToolExecutionResult.of(ToolResult.success("first complete"));
            }
            secondWorker.set(Thread.currentThread());
            secondStarted.countDown();
            try {
                assertTrue(releaseSecond.await(10, TimeUnit.SECONDS), "Second worker gate timed out");
                return ToolExecutionResult.of(ToolResult.success("second released"));
            } catch (InterruptedException cancellation) {
                secondInterrupted.set(true);
                Thread.currentThread().interrupt();
                return ToolExecutionResult.of(ToolResult.cancelled(
                        "TEST_INTERRUPTED", "second interrupted", ToolResult.EffectState.UNKNOWN));
            }
        });
        var context = ToolUseContext.of("/tmp", sessionId).withCurrentRunId(runId);
        var session = executor.newSession(context);
        try {
            session.addTool(tool, input(), "first", context);
            await(firstStarted);
            join(firstWorker.get());
            assertEquals(1, session.yieldCompleted().size());

            session.addTool(tool, input(), "second", context);
            await(secondStarted);
            boolean prematurelyQuiescent = runs.awaitQuiescence(runId, Duration.ZERO);
            var cancellation = executor.cancelRunDetailed(runId);
            assertAll(
                    () -> assertFalse(prematurelyQuiescent,
                            "The Run must retain a work lease while its second tool is executing"),
                    () -> assertEquals(1, cancellation.foundSessions()),
                    () -> assertEquals(1, cancellation.confirmedSessions()),
                    () -> assertTrue(secondInterrupted.get(), "Cancellation must reach the second worker"));
            join(secondWorker.get());
            assertTrue(runs.awaitQuiescence(runId, Duration.ZERO));
            assertEquals("TEST_INTERRUPTED", session.yieldCompleted().getFirst().getResult().failureCode());
            assertTrue(session.yieldCompleted().isEmpty());
        } finally {
            releaseSecond.countDown();
            session.discard();
            join(firstWorker.get());
            join(secondWorker.get());
            runs.unregister(runId);
            meters.close();
        }
    }

    @Test
    void aPublishedResultAlreadyExposesItsUpdatedSessionContext() throws Exception {
        var metricReached = new CountDownLatch(1);
        var releaseMetric = new CountDownLatch(1);
        var meters = blockingTotalCounterRegistry(metricReached, releaseMetric);
        var pipeline = mock(ToolExecutionPipeline.class);
        var executor = new StreamingToolExecutor(pipeline, meters);
        var original = ToolUseContext.of("/tmp/original", "context-publication");
        var updated = original.withWorkingDirectory("/tmp/updated");
        var worker = new AtomicReference<Thread>();
        when(pipeline.execute(any(), any(), any(), any())).thenAnswer(invocation -> {
            worker.set(Thread.currentThread());
            return ToolExecutionResult.of(ToolResult.success("context changed"), updated);
        });
        var session = executor.newSession(original);
        try {
            session.addTool(tool("LifecycleContext"), input(), "context", original);
            await(metricReached);
            boolean completed = session.isAllCompleted();
            var published = session.yieldCompleted();
            assertTrue(completed, "The controlled metric must be reached after result publication");
            assertEquals(1, published.size());
            assertEquals(updated, session.getCurrentContext(),
                    "A consumer observing a terminal result must also observe the updated context");
            releaseMetric.countDown();
            join(worker.get());
            assertTrue(session.isAllCompleted());
            assertEquals(updated, session.getCurrentContext());
            assertEquals(1, published.size() + session.yieldCompleted().size());
        } finally {
            releaseMetric.countDown();
            session.discard();
            join(worker.get());
            meters.close();
        }
    }

    @Test
    void anAlreadyQueuedExclusiveSuccessorUsesThePredecessorsUpdatedContext() throws Exception {
        var pipeline = mock(ToolExecutionPipeline.class);
        var meters = new SimpleMeterRegistry();
        var executor = new StreamingToolExecutor(pipeline, meters);
        var context = ToolUseContext.of("/tmp/original", "queued-context");
        var updated = context.withWorkingDirectory("/tmp/updated");
        var firstStarted = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var firstWorker = new AtomicReference<Thread>();
        var secondWorker = new AtomicReference<Thread>();
        var successorContext = new AtomicReference<ToolUseContext>();
        when(pipeline.execute(any(), any(), any(), any())).thenAnswer(invocation -> {
            ToolUseContext executionContext = invocation.getArgument(2);
            if ("first".equals(executionContext.toolUseId())) {
                firstWorker.set(Thread.currentThread());
                firstStarted.countDown();
                await(releaseFirst);
                return ToolExecutionResult.of(ToolResult.success("context changed"), updated);
            }
            secondWorker.set(Thread.currentThread());
            successorContext.set(executionContext);
            secondStarted.countDown();
            return ToolExecutionResult.of(ToolResult.success("successor complete"));
        });
        var session = executor.newSession(context);
        try {
            session.addTool(tool("LifecyclePredecessor"), input(), "first", context);
            await(firstStarted);
            session.addTool(tool("LifecycleSuccessor"), input(), "second", context);
            releaseFirst.countDown();
            await(secondStarted);
            join(firstWorker.get());
            join(secondWorker.get());
            assertEquals(updated.workingDirectory(), successorContext.get().workingDirectory());
            assertEquals("second", successorContext.get().toolUseId());
            assertEquals(updated, session.getCurrentContext());
            assertEquals(List.of("first", "second"), session.yieldCompleted().stream()
                    .map(StreamingToolExecutor.TrackedTool::getToolUseId).toList());
            verify(pipeline, times(2)).execute(any(), any(), any(), any());
        } finally {
            releaseFirst.countDown();
            session.discard();
            join(firstWorker.get());
            join(secondWorker.get());
            meters.close();
        }
    }

    @Test
    void aConcurrencySafeToolsUpdatedContextIsIgnoredByTheSessionAndItsSuccessor() throws Exception {
        var pipeline = mock(ToolExecutionPipeline.class);
        var meters = new SimpleMeterRegistry();
        var executor = new StreamingToolExecutor(pipeline, meters);
        var original = ToolUseContext.of("/tmp/original", "ignored-concurrent-context");
        var forbiddenUpdate = original.withWorkingDirectory("/tmp/unexpected")
                .withCurrentTaskId("unexpected-task");
        var safe = tool("LifecycleSafeContext");
        when(safe.isConcurrencySafe(any())).thenReturn(true);
        var firstStarted = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondStarted = new CountDownLatch(1);
        var firstWorker = new AtomicReference<Thread>();
        var secondWorker = new AtomicReference<Thread>();
        var successorContext = new AtomicReference<ToolUseContext>();
        when(pipeline.execute(any(), any(), any(), any())).thenAnswer(invocation -> {
            ToolUseContext executionContext = invocation.getArgument(2);
            if ("safe".equals(executionContext.toolUseId())) {
                firstWorker.set(Thread.currentThread());
                firstStarted.countDown();
                await(releaseFirst);
                return ToolExecutionResult.of(ToolResult.success("safe tool complete"), forbiddenUpdate);
            }
            secondWorker.set(Thread.currentThread());
            successorContext.set(executionContext);
            secondStarted.countDown();
            return ToolExecutionResult.of(ToolResult.success("successor complete"));
        });
        var session = executor.newSession(original);
        try {
            session.addTool(safe, input(), "safe", original);
            await(firstStarted);
            // This exclusive successor must wait for the safe tool to publish its
            // result; it cannot accidentally observe the old context by racing it.
            session.addTool(tool("LifecycleAfterSafeContext"), input(), "successor", original);
            releaseFirst.countDown();
            await(secondStarted);
            join(firstWorker.get());
            join(secondWorker.get());

            assertEquals(original, session.getCurrentContext());
            assertEquals(original.withToolUseId("successor"), successorContext.get());
            var results = session.yieldCompleted();
            assertEquals(List.of("safe", "successor"), results.stream()
                    .map(StreamingToolExecutor.TrackedTool::getToolUseId).toList());
            assertTrue(results.stream().noneMatch(result -> result.getResult().isError()));
            verify(pipeline, times(2)).execute(any(), any(), any(), any());
        } finally {
            releaseFirst.countDown();
            session.discard();
            join(firstWorker.get());
            join(secondWorker.get());
            meters.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @ResourceLock("LOG4J_CONFIGURATION")
    void workerStartFailureSettlesOnceAndOnlyNonfatalFailureContinuesTheQueue(boolean fatal) throws Exception {
        var failNextName = new AtomicBoolean();
        Throwable startFailure = fatal
                ? new NoClassDefFoundError("controlled/MissingWorkerDependency")
                : new IllegalStateException("controlled failure before worker start");
        var uncaught = new AtomicReference<Throwable>();
        String failingId = "controlled-start-failure-" + UUID.randomUUID();
        String followingId = "following-" + UUID.randomUUID();
        var followingLaunches = new AtomicInteger();
        var appender = new AbstractAppender("start-failure-" + UUID.randomUUID(), null, null, true, null) {
            @Override public void append(LogEvent event) {
                String message = event.getMessage().getFormattedMessage();
                if (StreamingToolExecutor.class.getName().equals(event.getLoggerName())
                        && message.startsWith("processQueue: launching virtual thread")) {
                    if (message.contains(failingId)) {
                        // The next getName belongs to the worker name expression, after
                        // queue ownership was claimed and before Thread.start is called.
                        failNextName.set(true);
                    } else if (message.contains(followingId)) {
                        followingLaunches.incrementAndGet();
                    }
                }
            }
        };
        var failing = tool("LifecycleStartFailure");
        when(failing.getName()).thenAnswer(invocation -> {
            if (failNextName.compareAndSet(true, false)) {
                throw startFailure;
            }
            return "LifecycleStartFailure";
        });
        var first = tool("LifecycleStartPredecessor");
        var following = tool("LifecycleAfterStartFailure");
        var pipeline = mock(ToolExecutionPipeline.class);
        var meters = new SimpleMeterRegistry();
        var runs = new RunExecutionRegistry();
        String runId = "start-failure-run";
        String sessionId = "start-failure-session";
        runs.register(runId, sessionId, new AbortContext());
        var executor = new StreamingToolExecutor(pipeline, meters, null, runs);
        var firstStarted = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var followingStarted = new CountDownLatch(1);
        var firstWorker = new AtomicReference<Thread>();
        var followingWorker = new AtomicReference<Thread>();
        when(pipeline.execute(any(), any(), any(), any())).thenAnswer(invocation -> {
            if (invocation.getArgument(0) == first) {
                firstWorker.set(Thread.currentThread());
                Thread.currentThread().setUncaughtExceptionHandler((thread, failure) -> uncaught.set(failure));
                firstStarted.countDown();
                await(releaseFirst);
            } else {
                assertSame(following, invocation.getArgument(0), "The failed worker must never enter the pipeline");
                followingWorker.set(Thread.currentThread());
                followingStarted.countDown();
            }
            return ToolExecutionResult.of(ToolResult.success("complete"));
        });
        var context = ToolUseContext.of("/tmp", sessionId).withCurrentRunId(runId);
        var session = executor.newSession(context);
        try (var logging = new ExecutorLoggerScope(appender)) {
            try {
                session.addTool(first, input(), "first", context);
                await(firstStarted);
                session.addTool(failing, input(), failingId, context);
                session.addTool(following, input(), followingId, context);
                releaseFirst.countDown();
                join(firstWorker.get());
                if (!fatal) {
                    await(followingStarted);
                    join(followingWorker.get());
                }

                var results = session.yieldCompleted();
                assertEquals(List.of("first", failingId, followingId), results.stream()
                        .map(StreamingToolExecutor.TrackedTool::getToolUseId).toList());
                assertEquals("TOOL_EXECUTION_START_FAILED", results.get(1).getResult().failureCode());
                assertEquals(ToolResult.EffectState.NOT_STARTED, results.get(1).getResult().effectState());
                if (fatal) {
                    assertSame(startFailure, uncaught.get(), "The original Error must propagate unchanged");
                    assertEquals(ToolResult.ExecutionStatus.CANCELLED, results.getLast().getResult().executionStatus());
                    assertEquals(ToolResult.EffectState.NOT_STARTED, results.getLast().getResult().effectState());
                    assertNull(followingWorker.get(), "A fatal start failure must not launch another worker");
                } else {
                    assertNull(uncaught.get());
                    assertFalse(results.getLast().getResult().isError());
                }
                assertTrue(session.yieldCompleted().isEmpty());
                assertEquals(fatal ? 0 : 1, followingLaunches.get(),
                        "A fatal start failure must settle the queue without attempting another worker launch");
                assertEquals(0, meters.get("zhiku.tool.virtual_threads.active").gauge().value());
                assertTrue(runs.awaitQuiescence(runId, Duration.ZERO));
                verify(pipeline, times(fatal ? 1 : 2)).execute(any(), any(), any(), any());
            } finally {
                releaseFirst.countDown();
                session.discard();
                join(firstWorker.get());
                join(followingWorker.get());
                runs.unregister(runId);
                meters.close();
            }
        }
    }

    @Test
    void cancellationInvokedSynchronouslyDuringLeaseRegistrationSettlesAndClosesOnce() throws Exception {
        var pipeline = mock(ToolExecutionPipeline.class);
        var meters = new SimpleMeterRegistry();
        var runs = mock(RunExecutionRegistry.class);
        var lease = mock(RunExecutionRegistry.WorkLease.class);
        when(runs.acquireWork(any(), any(), any())).thenReturn(lease);
        doAnswer(invocation -> {
            Runnable cancellation = invocation.getArgument(0);
            cancellation.run();
            return null;
        }).when(lease).onCancel(any());
        var executor = new StreamingToolExecutor(pipeline, meters, null, runs);
        var context = ToolUseContext.of("/tmp", "synchronous-cancellation").withCurrentRunId("run");
        var session = executor.newSession(context);
        try {
            session.addTool(tool("LifecycleImmediateCancellation"), input(), "cancelled", context);
            awaitCompleted(session);
            verify(lease, timeout(5000).times(1)).close();
            assertTrue(session.isDiscarded());
            var results = session.yieldCompleted();
            assertEquals(1, results.size());
            assertTrue(results.getFirst().getResult().isError());
            assertEquals(ToolResult.EffectState.NOT_STARTED, results.getFirst().getResult().effectState());
            assertTrue(session.yieldCompleted().isEmpty());
            verifyNoInteractions(pipeline);
            verify(runs, times(1)).acquireWork(any(), any(), any());
        } finally {
            session.discard();
            meters.close();
        }
        verify(lease, times(1)).close();
    }

    @Test
    void publishedResultsDoNotRemoveCancellationOwnershipBeforeTheWorkerExits() throws Exception {
        var metricReached = new CountDownLatch(1);
        var releaseMetric = new CountDownLatch(1);
        var meters = blockingTotalCounterRegistry(metricReached, releaseMetric);
        var pipeline = mock(ToolExecutionPipeline.class);
        var runs = new RunExecutionRegistry();
        String runId = "published-but-running";
        String sessionId = "published-but-running-session";
        runs.register(runId, sessionId, new AbortContext());
        var executor = new StreamingToolExecutor(pipeline, meters, null, runs);
        var worker = new AtomicReference<Thread>();
        when(pipeline.execute(any(), any(), any(), any())).thenAnswer(invocation -> {
            worker.set(Thread.currentThread());
            return ToolExecutionResult.of(ToolResult.success("published"));
        });
        var context = ToolUseContext.of("/tmp", sessionId).withCurrentRunId(runId);
        var session = executor.newSession(context);
        try {
            session.addTool(tool("LifecyclePostPublication"), input(), "completed", context);
            await(metricReached);
            assertTrue(session.isAllCompleted());
            assertEquals(1, session.yieldCompleted().size());
            assertFalse(runs.awaitQuiescence(runId, Duration.ZERO));

            // The controlled metric keeps the worker alive beyond the bounded
            // cancellation wait. Result availability must not erase ownership.
            var cancellation = executor.cancelRunDetailed(runId);
            assertEquals(1, cancellation.foundSessions());
            assertEquals(1, cancellation.unconfirmedSessions());
            var retriedCancellation = executor.cancelRunDetailed(runId);
            assertEquals(1, retriedCancellation.foundSessions(),
                    "An unconfirmed cancellation must retain its session for subsequent attempts");
            assertEquals(1, retriedCancellation.unconfirmedSessions());
            assertTrue(worker.get().isAlive());
            assertFalse(runs.awaitQuiescence(runId, Duration.ZERO));
            releaseMetric.countDown();
            join(worker.get());
            assertTrue(runs.awaitQuiescence(runId, Duration.ZERO));
        } finally {
            releaseMetric.countDown();
            session.discard();
            join(worker.get());
            runs.unregister(runId);
            meters.close();
        }
    }

    @Test
    void aBlockedPreparationRetainsItsLeaseAndAcceptsCancellationBeforePipelineAdmission() throws Exception {
        var pipeline = mock(ToolExecutionPipeline.class);
        var meters = new SimpleMeterRegistry();
        var runs = new RunExecutionRegistry();
        String runId = "blocked-preparation-run";
        String sessionId = "blocked-preparation-session";
        runs.register(runId, sessionId, new AbortContext());
        var executor = new StreamingToolExecutor(pipeline, meters, null, runs);
        var preparing = tool("LifecyclePreparing");
        var preparationEntered = new CountDownLatch(1);
        var releasePreparation = new CountDownLatch(1);
        when(preparing.isConcurrencySafe(any())).thenAnswer(invocation -> {
            preparationEntered.countDown();
            awaitPreservingInterrupt(releasePreparation);
            return false;
        });
        var context = ToolUseContext.of("/tmp", sessionId).withCurrentRunId(runId);
        var session = executor.newSession(context);
        var submissionFailure = new AtomicReference<Throwable>();
        Thread submitter = Thread.ofVirtual().start(() -> {
            try {
                session.addTool(preparing, input(), "preparing", context);
            } catch (Throwable failure) {
                submissionFailure.set(failure);
            }
        });
        try {
            await(preparationEntered);
            assertFalse(runs.awaitQuiescence(runId, Duration.ZERO),
                    "Admission preparation must already be covered by a Run work lease");
            var cancellation = executor.cancelRunDetailed(runId);
            assertEquals(1, cancellation.foundSessions());
            assertEquals(1, cancellation.unconfirmedSessions(),
                    "Blocked preparation remains owned work until the submitting thread exits it");
            assertTrue(session.isDiscarded());
            assertTrue(submitter.isAlive());
            assertFalse(runs.awaitQuiescence(runId, Duration.ZERO));

            releasePreparation.countDown();
            join(submitter);
            assertNull(submissionFailure.get());
            assertTrue(session.isAllCompleted());
            var results = session.yieldCompleted();
            assertEquals(1, results.size());
            assertEquals(ToolResult.ExecutionStatus.CANCELLED, results.getFirst().getResult().executionStatus());
            assertEquals(ToolResult.EffectState.NOT_STARTED, results.getFirst().getResult().effectState());
            assertTrue(session.yieldCompleted().isEmpty());
            assertTrue(runs.awaitQuiescence(runId, Duration.ZERO));
            verifyNoInteractions(pipeline);
        } finally {
            releasePreparation.countDown();
            session.discard();
            join(submitter);
            runs.unregister(runId);
            meters.close();
        }
    }

    @Test
    @ResourceLock("LOG4J_CONFIGURATION")
    void aPreparationErrorSettlesWithoutStartingThePipelineAndPropagatesFromTheOwnedWorker() throws Exception {
        var pipeline = mock(ToolExecutionPipeline.class);
        var meters = new SimpleMeterRegistry();
        var runs = new RunExecutionRegistry();
        String runId = "preparation-error-run";
        String sessionId = "preparation-error-session";
        String toolUseId = "preparation-error-" + UUID.randomUUID();
        runs.register(runId, sessionId, new AbortContext());
        var executor = new StreamingToolExecutor(pipeline, meters, null, runs);
        var tool = tool("LifecyclePreparationError");
        var failure = new NoClassDefFoundError("controlled/MissingRiskMetadataDependency");
        when(tool.isHighRisk()).thenThrow(failure);
        var worker = new AtomicReference<Thread>();
        var uncaught = new AtomicReference<Throwable>();
        var workerRegistered = new CountDownLatch(1);
        var appender = new AbstractAppender("preparation-error-" + UUID.randomUUID(), null, null, true, null) {
            @Override public void append(LogEvent event) {
                if (!StreamingToolExecutor.class.getName().equals(event.getLoggerName())
                        || !toolUseId.equals(event.getContextData().getValue("toolUseId"))
                        || !event.getMessage().getFormattedMessage().startsWith("virtual thread started:")) return;
                Thread ownedWorker = Thread.currentThread();
                ownedWorker.setUncaughtExceptionHandler((thread, error) -> uncaught.set(error));
                worker.set(ownedWorker);
                workerRegistered.countDown();
            }
        };
        var context = ToolUseContext.of("/tmp", sessionId).withCurrentRunId(runId);
        var session = executor.newSession(context);
        try (var logging = new ExecutorLoggerScope(appender)) {
            try {
                assertDoesNotThrow(() -> session.addTool(tool, input(), toolUseId, context));
                await(workerRegistered);
                join(worker.get());
                assertSame(failure, uncaught.get());
                assertTrue(session.isAllCompleted());
                var results = session.yieldCompleted();
                assertEquals(1, results.size());
                assertEquals("TOOL_EXECUTION_TERMINATED_WITHOUT_RESULT", results.getFirst().getResult().failureCode());
                assertEquals(ToolResult.EffectState.NOT_STARTED, results.getFirst().getResult().effectState());
                assertTrue(session.yieldCompleted().isEmpty());
                assertEquals(0, meters.get("zhiku.tool.virtual_threads.active").gauge().value());
                assertTrue(runs.awaitQuiescence(runId, Duration.ZERO));
                verifyNoInteractions(pipeline);
            } finally {
                session.discard();
                join(worker.get());
                runs.unregister(runId);
                meters.close();
            }
        }
    }

    private static Tool tool(String name) {
        Tool tool = mock(Tool.class);
        when(tool.getName()).thenReturn(name);
        when(tool.getMaxExecutionTimeMs()).thenReturn(60_000L);
        when(tool.isConcurrencySafe(any())).thenReturn(false);
        return tool;
    }

    private static ToolInput input() { return ToolInput.from(Map.of()); }

    private static SimpleMeterRegistry blockingTotalCounterRegistry(
            CountDownLatch metricReached, CountDownLatch releaseMetric) {
        return new SimpleMeterRegistry() {
            @Override protected Counter newCounter(Meter.Id id) {
                Counter delegate = super.newCounter(id);
                if (!"zhiku.tool.executions.total".equals(id.getName())) return delegate;
                return new Counter() {
                    @Override public void increment(double amount) {
                        metricReached.countDown();
                        awaitPreservingInterrupt(releaseMetric);
                        delegate.increment(amount);
                    }
                    @Override public double count() { return delegate.count(); }
                    @Override public Meter.Id getId() { return delegate.getId(); }
                };
            }
        };
    }

    private static void awaitCompleted(StreamingToolExecutor.ExecutionSession session) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!session.isAllCompleted() && System.nanoTime() < deadline) {
            session.awaitAnyCompletion(10, TimeUnit.MILLISECONDS);
        }
        assertTrue(session.isAllCompleted(), "The session did not publish terminal results");
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Controlled lifecycle boundary was not reached");
    }

    private static void awaitPreservingInterrupt(CountDownLatch latch) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            for (;;) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new AssertionError("Controlled lifecycle boundary timed out");
                try {
                    if (!latch.await(remaining, TimeUnit.NANOSECONDS)) {
                        throw new AssertionError("Controlled lifecycle boundary timed out");
                    }
                    return;
                } catch (InterruptedException cancellation) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static void join(Thread worker) throws InterruptedException {
        if (worker == null) return;
        worker.join(5000);
        assertFalse(worker.isAlive(), "Test-owned worker did not exit");
    }

    private static final class ExecutorLoggerScope implements AutoCloseable {
        private final LoggerContext context = (LoggerContext) LogManager.getContext(false);
        private final String loggerName = StreamingToolExecutor.class.getName();
        private final LoggerConfig previous;
        private final AbstractAppender appender;

        private ExecutorLoggerScope(AbstractAppender appender) {
            this.appender = appender;
            var configuration = context.getConfiguration();
            previous = configuration.getLoggers().get(loggerName);
            var isolated = new LoggerConfig(loggerName, Level.DEBUG, true);
            appender.start();
            isolated.addAppender(appender, null, null);
            configuration.removeLogger(loggerName);
            configuration.addLogger(loggerName, isolated);
            context.updateLoggers();
        }

        @Override public void close() {
            var configuration = context.getConfiguration();
            configuration.removeLogger(loggerName);
            if (previous != null) configuration.addLogger(loggerName, previous);
            context.updateLoggers();
            appender.stop();
        }
    }
}
