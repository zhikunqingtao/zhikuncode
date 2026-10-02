package com.aicodeassistant.tool.agent;

import com.aicodeassistant.config.AgentTimeoutConfig;
import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.coordinator.CoordinatorService;
import com.aicodeassistant.coordinator.TaskNotificationFormatter;
import com.aicodeassistant.engine.*;
import com.aicodeassistant.llm.LlmProviderRegistry;
import com.aicodeassistant.run.RunExecutionRegistry;
import com.aicodeassistant.service.FileStateCache;
import com.aicodeassistant.session.SessionExecutionGate;
import com.aicodeassistant.session.SessionManager;
import com.aicodeassistant.tool.ToolRegistry;
import com.aicodeassistant.tool.ToolUseContext;
import com.aicodeassistant.tool.process.ManagedProcessRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** No model, process or database execution: real lifecycle ownership with controlled worker exits. */
class SubAgentTaskLifecycleTest {
    private final String agent = "task-" + UUID.randomUUID();
    private final String child = "subagent-" + agent;
    private final String childRun = "run-" + UUID.randomUUID();
    private final List<CountDownLatch> releases = new ArrayList<>();
    private final List<Thread> callers = new ArrayList<>();
    private final List<RunExecutionRegistry.WorkLease> retainedLeases = new ArrayList<>();
    private final AtomicBoolean foregroundQuiet = new AtomicBoolean(true);
    private final AtomicBoolean backgroundQuiet = new AtomicBoolean(true);
    private QueryEngine engine;
    private ManagedProcessRunner processes;
    private RunExecutionRegistry runs;
    private SessionManager sessions;
    private AgentConcurrencyController slots;
    private SubAgentExecutor executor;
    private AgentTimeoutConfig timeouts;
    private SessionExecutionGate gate;
    private DataSource dataSource;
    private ToolUseContext parent;
    private AbortContext abort;

    @BeforeEach
    void setup() {
        parent = ToolUseContext.of("/tmp/task-lifecycle-fixture", "parent")
                .withCurrentRunId("parent-run").withParentModel("fixture-model");
        engine = mock(QueryEngine.class);
        abort = new AbortContext();
        when(engine.getOrCreateAbortContext(child)).thenReturn(abort);
        runs = new RunExecutionRegistry();
        runs.register("parent-run", "parent", new AbortContext());
        doAnswer(call -> { runs.abortSession(call.getArgument(0), call.getArgument(1)); return null; })
                .when(engine).abort(anyString(), any());
        processes = mock(ManagedProcessRunner.class);
        when(processes.currentRunTermination(childRun)).thenAnswer(call -> summary(foregroundQuiet.get()));
        when(processes.currentSessionBackground(child)).thenAnswer(call -> summary(backgroundQuiet.get()));
        sessions = mock(SessionManager.class);
        Map<String, FileStateCache> caches = new ConcurrentHashMap<>();
        when(sessions.getFileStateCache(anyString())).thenAnswer(call ->
                caches.computeIfAbsent(call.getArgument(0), key -> new FileStateCache()));
        gate = new SessionExecutionGate();
        dataSource = mock(DataSource.class);
        when(sessions.acquireBackgroundLease(anyString())).thenAnswer(call ->
                gate.acquireBackground(dataSource, call.getArgument(0)));
        slots = new AgentConcurrencyController();
        var registry = mock(ToolRegistry.class);
        when(registry.getEnabledToolsSorted()).thenReturn(List.of());
        timeouts = new AgentTimeoutConfig();
        timeouts.setDefaultSeconds(30);
        timeouts.setMaxSeconds(30);
        executor = new SubAgentExecutor(slots, engine, registry, mock(BackgroundAgentTracker.class),
                mock(WorktreeManager.class), new TaskNotificationFormatter(), mock(FeatureFlagService.class),
                mock(CoordinatorService.class), sessions, null, mock(LlmProviderRegistry.class),
                timeouts, null, mock(CheckpointService.class), new ObjectMapper());
        executor.setWorktreeLifecycleSupport(runs, processes);
    }

    @AfterEach
    void cleanup() throws Exception {
        foregroundQuiet.set(true);
        backgroundQuiet.set(true);
        retainedLeases.forEach(RunExecutionRegistry.WorkLease::close);
        releases.forEach(CountDownLatch::countDown);
        for (Thread caller : callers) {
            caller.interrupt();
            caller.join(3000);
            assertThat(caller.isAlive()).as("fixture must not retain its caller").isFalse();
        }
        runs.unregister(childRun);
        runs.unregister("parent-run");
    }

    @Test
    void successPreservesSessionServicesAndReleasesActualOwnership() {
        when(engine.execute(any(), any(), any())).thenAnswer(call -> {
            register(call.getArgument(1));
            backgroundQuiet.set(false); // A newly detached service survives normal completion.
            runs.unregister(childRun);
            return query("end_turn");
        });

        var result = executor.executeTaskSync(request(), parent);

        assertThat(result.status()).isEqualTo("completed");
        assertThat(slots.getActiveCount()).isZero();
        verify(sessions).closeSubAgentSession(child);
        verify(processes, never()).cancelSessionBackground(anyString());
        verify(processes).currentSessionBackground(child); // Admission checks previous occupancy only.
        assertParentUnaffected();
        try (var lease = gate.tryAcquireMerge(dataSource, "parent", "after-exit")) {
            assertThat(lease).isNotNull();
        }
    }

    @Test
    void interruptWaitsForActualWorkerBeforeClosingCacheSessionAndSlot() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = release();
        when(engine.execute(any(), any(), any())).thenAnswer(call -> {
            register(call.getArgument(1));
            entered.countDown();
            release.await(); // Deliberately ignores AbortContext until the test releases it.
            runs.unregister(childRun);
            return query("end_turn");
        });
        var invocation = start();
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        invocation.thread().interrupt();
        assertThat(abort.onAbort().get(2, TimeUnit.SECONDS)).isEqualTo(AbortReason.USER_INTERRUPT);
        assertStillOwned(invocation);
        release.countDown();
        assertThat(invocation.result().get(3, TimeUnit.SECONDS).status()).isEqualTo("interrupted");
        invocation.thread().join(1000);
        assertThat(invocation.restoredInterrupt().get()).isTrue();
        verify(sessions).closeSubAgentSession(child);
        verify(processes, atLeastOnce()).cancelSessionBackground(child);
        assertParentUnaffected();
    }

    @Test
    void cancellationBeforeRunRegistrationIsStickyAndNeverTargetsParent() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), registerNow = release();
        when(engine.execute(any(), any(), any())).thenAnswer(call -> {
            entered.countDown();
            registerNow.await();
            assertThat(abort.isAborted()).isTrue();
            register(call.getArgument(1));
            runs.unregister(childRun);
            return query("cancelled");
        });
        var invocation = start();
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        invocation.thread().interrupt();
        abort.onAbort().get(2, TimeUnit.SECONDS);
        assertStillOwned(invocation);
        registerNow.countDown();
        assertThat(invocation.result().get(3, TimeUnit.SECONDS).status()).isEqualTo("interrupted");
        assertParentUnaffected();
    }

    @Test
    void finishedWorkerDoesNotReleaseSlotWhileRunLeaseIsLive() throws Exception {
        CountDownLatch workerDone = new CountDownLatch(1);
        AtomicReference<RunExecutionRegistry.WorkLease> live = new AtomicReference<>();
        when(engine.execute(any(), any(), any())).thenAnswer(call -> {
            register(call.getArgument(1));
            live.set(runs.acquireWork(childRun, "tool-session", "retained"));
            workerDone.countDown();
            return query("end_turn");
        });
        var invocation = start();
        assertThat(workerDone.await(2, TimeUnit.SECONDS)).isTrue();
        retainedLeases.add(live.get());
        assertStillOwned(invocation);
        invocation.thread().interrupt();
        abort.onAbort().get(2, TimeUnit.SECONDS);
        assertStillOwned(invocation);
        live.get().close();
        assertThat(invocation.result().get(3, TimeUnit.SECONDS).status()).isEqualTo("interrupted");
    }

    @Test
    void finishedWorkerDoesNotReleaseSlotWhileOwnedProcessRemains() throws Exception {
        foregroundQuiet.set(false);
        CountDownLatch checked = new CountDownLatch(1);
        when(processes.currentRunTermination(childRun)).thenAnswer(call -> {
            checked.countDown();
            return summary(foregroundQuiet.get());
        });
        when(engine.execute(any(), any(), any())).thenAnswer(call -> {
            register(call.getArgument(1));
            runs.unregister(childRun);
            return query("end_turn");
        });
        var invocation = start();
        assertThat(checked.await(2, TimeUnit.SECONDS)).isTrue();
        assertStillOwned(invocation);
        foregroundQuiet.set(true);
        assertThat(invocation.result().get(3, TimeUnit.SECONDS).status()).isEqualTo("completed");
    }

    @Test
    void cancelledChildServicesMustConfirmExitAfterRunClosesAdmission() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = release(), cleanupAttempted = new CountDownLatch(1);
        when(engine.execute(any(), any(), any())).thenAnswer(call -> {
            register(call.getArgument(1));
            backgroundQuiet.set(false);
            entered.countDown();
            release.await();
            runs.unregister(childRun);
            return query("end_turn");
        });
        when(processes.cancelSessionBackground(child)).thenAnswer(call -> {
            if (runs.activeRunForSession(child).isEmpty()) cleanupAttempted.countDown();
            return summary(backgroundQuiet.get());
        });
        var invocation = start();
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        invocation.thread().interrupt();
        abort.onAbort().get(2, TimeUnit.SECONDS);
        release.countDown();
        assertThat(cleanupAttempted.await(2, TimeUnit.SECONDS)).isTrue();
        assertStillOwned(invocation);
        backgroundQuiet.set(true);
        assertThat(invocation.result().get(3, TimeUnit.SECONDS).status()).isEqualTo("interrupted");
        assertParentUnaffected();
    }

    @Test
    void timeoutCannotReturnOrBecomeSuccessUntilActualWorkerExits() throws Exception {
        timeouts.setDefaultSeconds(1);
        timeouts.setMaxSeconds(1);
        CountDownLatch release = release();
        when(engine.execute(any(), any(), any())).thenAnswer(call -> {
            register(call.getArgument(1));
            release.await();
            runs.unregister(childRun);
            return query("end_turn");
        });
        var invocation = start();
        assertThat(abort.onAbort().get(3, TimeUnit.SECONDS)).isEqualTo(AbortReason.TIMEOUT);
        assertStillOwned(invocation);
        release.countDown();
        assertThat(invocation.result().get(3, TimeUnit.SECONDS).status()).isEqualTo("timeout");
        assertParentUnaffected();
    }

    @Test
    void verifiedFailureBeforeRunRegistrationCanReleaseResources() {
        when(engine.execute(any(), any(), any())).thenAnswer(call -> {
            QueryLoopState state = call.getArgument(1);
            ReflectionTestUtils.invokeMethod(state, "recordFailureBeforeRunRegistration", child);
            return new QueryEngine.QueryResult(List.of(), null, "error", "startup fixture", 0);
        });
        assertThat(executor.executeTaskSync(request(), parent).status()).isEqualTo("failed");
        assertThat(slots.getActiveCount()).isZero();
        verify(sessions).closeSubAgentSession(child);
        assertParentUnaffected();
    }

    @Test
    void failedStartupWaitsForBackgroundOccupancyWithoutClaimingItsCancellation() throws Exception {
        CountDownLatch inspected = new CountDownLatch(1);
        when(processes.currentSessionBackground(child)).thenAnswer(call -> {
            if (!backgroundQuiet.get()) inspected.countDown();
            return summary(backgroundQuiet.get());
        });
        when(engine.execute(any(), any(), any())).thenAnswer(call -> {
            QueryLoopState state = call.getArgument(1);
            ReflectionTestUtils.invokeMethod(state, "recordFailureBeforeRunRegistration", child);
            backgroundQuiet.set(false);
            return new QueryEngine.QueryResult(List.of(), null, "error", "startup fixture", 0);
        });
        var invocation = start();
        assertThat(inspected.await(2, TimeUnit.SECONDS)).isTrue();
        assertStillOwned(invocation);
        verify(processes, never()).cancelSessionBackground(anyString());
        backgroundQuiet.set(true);
        assertThat(invocation.result().get(3, TimeUnit.SECONDS).status()).isEqualTo("failed");
        assertParentUnaffected();
    }

    @Test
    void cancellationAcceptedAfterReturnStillWaitsForOnlyChildServices() throws Exception {
        when(engine.execute(any(), any(), any())).thenAnswer(call -> {
            register(call.getArgument(1));
            runs.unregister(childRun);
            return query("end_turn");
        });
        assertThat(executor.executeTaskSync(request(), parent).status()).isEqualTo("completed");
        backgroundQuiet.set(false);
        CountDownLatch attempted = new CountDownLatch(1);
        when(processes.cancelSessionBackground(child)).thenAnswer(call -> {
            attempted.countDown();
            return summary(backgroundQuiet.get());
        });
        var settled = new CompletableFuture<Void>();
        Thread caller = Thread.ofVirtual().unstarted(() -> {
            try {
                Thread.currentThread().interrupt();
                executor.finishTaskCancellation(request(), parent);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
                settled.complete(null);
            } catch (Throwable failure) { settled.completeExceptionally(failure); }
        });
        callers.add(caller);
        caller.start();
        assertThat(attempted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(settled.isDone()).isFalse();
        backgroundQuiet.set(true);
        settled.get(3, TimeUnit.SECONDS);
        assertParentUnaffected();
    }

    @Test
    void occupiedIdentityIsRejectedWithoutClosingOtherExecution() {
        runs.register(childRun, child, new AbortContext());
        assertThrows(IllegalStateException.class, () -> executor.executeTaskSync(request(), parent));
        verify(engine, never()).execute(any(), any(), any());
        verify(sessions, never()).closeSubAgentSession(child);
        verify(processes, never()).cancelSessionBackground(anyString());
    }

    @Test
    void existingChildBackgroundServiceIsNotReusedOrCancelled() {
        backgroundQuiet.set(false);
        assertThrows(IllegalStateException.class, () -> executor.executeTaskSync(request(), parent));
        verify(engine, never()).execute(any(), any(), any());
        verify(processes, never()).cancelSessionBackground(anyString());
    }

    @Test
    void lateCleanupNeverCancelsAReplacementRunEvenAfterItDisappears() {
        runs.register(childRun, child, new AbortContext());
        var identityLost = new AtomicBoolean();
        assertThat(executor.stopTaskBackground(child, null, null, identityLost)).isFalse();
        assertThat(identityLost.get()).isTrue();
        runs.unregister(childRun);
        assertThat(executor.stopTaskBackground(child, null, null, identityLost)).isFalse();
        verify(processes, never()).cancelSessionBackground(anyString());
    }

    @Test
    void changedAbortAuthorityInvalidatesOwnedCleanupBeforeAnyCancellation() {
        var replacement = new AbortContext();
        when(engine.getAbortContext(child)).thenReturn(replacement);
        var identityLost = new AtomicBoolean();
        assertThat(executor.stopTaskBackground(child, childRun, abort, identityLost)).isFalse();
        when(engine.getAbortContext(child)).thenReturn(null);
        assertThat(executor.stopTaskBackground(child, childRun, abort, identityLost)).isFalse();
        verify(processes, never()).cancelSessionBackground(anyString());
        assertThat(replacement.isAborted()).isFalse();
    }

    @Test
    void interruptionBeforeInvocationDoesNotLaunchChildWorker() throws Exception {
        var result = new CompletableFuture<SubAgentExecutor.AgentResult>();
        Thread caller = Thread.ofVirtual().unstarted(() -> {
            Thread.currentThread().interrupt();
            result.complete(executor.executeTaskSync(request(), parent));
        });
        callers.add(caller);
        caller.start();
        assertThat(result.get(2, TimeUnit.SECONDS).status()).isEqualTo("interrupted");
        verify(engine, never()).execute(any(), any(), any());
        assertThat(slots.getActiveCount()).isZero();
    }

    @Test
    void unsupportedTaskExecutionModesAreRejectedBeforeInitialization() {
        for (var request : List.of(
                new SubAgentExecutor.AgentRequest(agent, "fixture", null, null,
                        SubAgentExecutor.IsolationMode.WORKTREE, false),
                new SubAgentExecutor.AgentRequest(agent, "fixture", null, null,
                        SubAgentExecutor.IsolationMode.NONE, false, "team", false),
                new SubAgentExecutor.AgentRequest(agent, "fixture", null, null,
                        SubAgentExecutor.IsolationMode.NONE, false, null, true))) {
            assertThrows(IllegalArgumentException.class, () -> executor.executeTaskSync(request, parent));
        }
        verifyNoInteractions(sessions);
        verify(engine, never()).execute(any(), any(), any());
    }

    private void register(QueryLoopState state) {
        runs.register(childRun, child, abort);
        state.setToolUseContext(state.getToolUseContext().withCurrentRunId(childRun));
    }

    private void assertStillOwned(Invocation invocation) {
        assertThat(invocation.result().isDone()).isFalse();
        assertThat(slots.getActiveCount()).isOne();
        assertThat(gate.tryAcquireMerge(dataSource, "parent", "must-not-merge")).isNull();
        verify(sessions, never()).closeSubAgentSession(child);
        verify(sessions, never()).removeFileStateCache(child);
    }

    private void assertParentUnaffected() {
        assertThat(runs.cancellationForSession("parent").orElseThrow().isAborted()).isFalse();
        verify(engine, never()).abort(eq("parent"), any());
        verify(processes, never()).cancelRun(anyString());
        verify(processes, never()).cancelSessionBackground("parent");
    }

    private CountDownLatch release() {
        var latch = new CountDownLatch(1);
        releases.add(latch);
        return latch;
    }

    private Invocation start() {
        var result = new CompletableFuture<SubAgentExecutor.AgentResult>();
        var restored = new AtomicBoolean();
        Thread caller = Thread.ofVirtual().unstarted(() -> {
            try { result.complete(executor.executeTaskSync(request(), parent)); }
            catch (Throwable failure) { result.completeExceptionally(failure); }
            finally { restored.set(Thread.currentThread().isInterrupted()); }
        });
        callers.add(caller);
        caller.start();
        return new Invocation(caller, result, restored);
    }

    private SubAgentExecutor.AgentRequest request() {
        return new SubAgentExecutor.AgentRequest(agent, "fixture", null, null,
                SubAgentExecutor.IsolationMode.NONE, false);
    }

    private static QueryEngine.QueryResult query(String stop) {
        return new QueryEngine.QueryResult(List.of(), null, stop, null, 1);
    }

    private static ManagedProcessRunner.CancelSummary summary(boolean quiet) {
        return quiet ? new ManagedProcessRunner.CancelSummary(0, 0, 0)
                : new ManagedProcessRunner.CancelSummary(1, 0, 1);
    }

    private record Invocation(Thread thread, CompletableFuture<SubAgentExecutor.AgentResult> result,
                              AtomicBoolean restoredInterrupt) { }
}
