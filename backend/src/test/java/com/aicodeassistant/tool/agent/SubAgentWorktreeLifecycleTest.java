package com.aicodeassistant.tool.agent;

import com.aicodeassistant.authorization.AuthorizationSubject;
import com.aicodeassistant.authorization.AuthorizationSubjectResolver;
import com.aicodeassistant.config.AgentTimeoutConfig;
import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.coordinator.CoordinatorService;
import com.aicodeassistant.coordinator.TaskNotificationFormatter;
import com.aicodeassistant.engine.*;
import com.aicodeassistant.llm.LlmProviderRegistry;
import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.aicodeassistant.run.RunExecutionRegistry;
import com.aicodeassistant.run.RunTracker;
import com.aicodeassistant.service.FileStateCache;
import com.aicodeassistant.service.GitService;
import com.aicodeassistant.session.SessionExecutionGate;
import com.aicodeassistant.session.SessionManager;
import com.aicodeassistant.tool.*;
import com.aicodeassistant.tool.process.ManagedProcessRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Real executor, Run admission/leases and session gate. Most cases control the Git boundary;
 * realGitDeliveryAcrossExecutorManagerAndRunner additionally runs the production manager and runner.
 * QueryEngine is a no-model stub; session persistence/transport and authorization identity are fixtures.
 */
class SubAgentWorktreeLifecycleTest {
    @TempDir Path workspace;
    private final String agentId = "lifecycle-" + UUID.randomUUID();
    private final String child = "subagent-" + agentId;
    private final String childRun = "run-" + UUID.randomUUID();
    private QueryEngine engine;
    private WorktreeManager manager;
    private ManagedProcessRunner processes;
    private RunExecutionRegistry runs;
    private SessionManager sessions;
    private AgentConcurrencyController slots;
    private SubAgentExecutor executor;
    private BackgroundAgentTracker tracker;
    private CoordinatorService coordinator;
    private AgentTimeoutConfig timeouts;
    private SessionExecutionGate gate;
    private DataSource dataSource;
    private Map<String, FileStateCache> caches;
    private ToolUseContext parent;
    private Path tree;
    private AbortContext abort;

    @BeforeEach
    void setUp() throws Exception {
        tree = Files.createDirectories(workspace.resolve("isolated/backend"));
        parent = ToolUseContext.of(workspace.toString(), "parent-session")
                .withCurrentRunId("parent-run").withParentModel("fixture-model");
        engine = mock(QueryEngine.class);
        abort = new AbortContext();
        when(engine.getOrCreateAbortContext(child)).thenReturn(abort);
        manager = mock(WorktreeManager.class);
        when(manager.createWorktree(eq(agentId), any(), eq(false))).thenReturn(
                new WorktreeManager.ManagedWorktree(tree.getParent(), tree, "agent-branch", "HEAD snapshot"));
        when(manager.retain(any(), anyString())).thenAnswer(call ->
                "Delivery: preserved — " + call.getArgument(1)
                        + "\nRecovery worktree: " + tree.getParent() + "\nRecovery branch: agent-branch");
        when(manager.finishWorktree(any(), eq(true))).thenReturn(
                new WorktreeManager.DeliveryResult(true, true, "Delivery: delivered; no rerun needed", null));
        when(manager.inspectPendingDelivery(any())).thenReturn(WorktreeManager.PendingDelivery.NONE);
        doAnswer(call -> { ((Runnable) call.getArgument(1)).run(); return null; })
                .when(manager).whenTargetSettled(any(), any());
        processes = mock(ManagedProcessRunner.class);
        when(processes.currentRunTermination(anyString())).thenReturn(new ManagedProcessRunner.CancelSummary(0, 0, 0));
        when(processes.currentSessionBackground(anyString())).thenReturn(new ManagedProcessRunner.CancelSummary(0, 0, 0));
        runs = spy(new RunExecutionRegistry());
        sessions = mock(SessionManager.class);
        caches = new ConcurrentHashMap<>();
        when(sessions.getFileStateCache(anyString())).thenAnswer(call ->
                caches.computeIfAbsent(call.getArgument(0), ignored -> new FileStateCache()));
        doAnswer(call -> { caches.remove(call.getArgument(0)); return null; })
                .when(sessions).removeFileStateCache(anyString());
        gate = new SessionExecutionGate();
        dataSource = mock(DataSource.class);
        when(sessions.acquireBackgroundLease(anyString())).thenAnswer(call ->
                gate.acquireBackground(dataSource, "parent-session"));
        slots = new AgentConcurrencyController();
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getEnabledToolsSorted()).thenReturn(List.of());
        tracker = spy(new BackgroundAgentTracker(mock(SimpMessagingTemplate.class)));
        coordinator = mock(CoordinatorService.class);
        timeouts = new AgentTimeoutConfig();
        timeouts.setDefaultSeconds(1);
        timeouts.setMaxSeconds(1);
        timeouts.setGracefulShutdownSeconds(0);
        executor = new SubAgentExecutor(slots, engine, registry, tracker, manager,
                new TaskNotificationFormatter(), mock(FeatureFlagService.class), coordinator, sessions,
                null, mock(LlmProviderRegistry.class), timeouts, null, mock(CheckpointService.class), new ObjectMapper());
        executor.setWorktreeLifecycleSupport(runs, processes);
    }

    @Test
    void usesActualCwdAndEmptyCacheThenInvalidatesParentAfterDelivery() throws Exception {
        Path parentFile = Files.writeString(workspace.resolve("parent-file"), "old");
        FileStateCache oldParentCache = sessions.getFileStateCache(parent.sessionId());
        oldParentCache.markRead(parentFile.toString(), "old", null, null, false);
        FileStateCache preDeliverySnapshot = oldParentCache.cloneCache();
        assertThat(oldParentCache.hasBeenRead(parentFile.toString())).isTrue();
        assertThat(oldParentCache.isStale(parentFile.toString())).isFalse();
        when(engine.execute(any(), any(), any())).thenAnswer(call -> {
            QueryConfig config = call.getArgument(0);
            QueryLoopState state = call.getArgument(1);
            register(state);
            assertThat(config.systemPrompt()).contains("工作目录：" + tree);
            assertThat(state.getToolUseContext().workingDirectory()).isEqualTo(tree.toString());
            assertThat(sessions.getFileStateCache(child).toMap()).isEmpty();
            sessions.getFileStateCache(child).markRead(tree.resolve("child.txt").toString(), "new", null, null, false);
            runs.unregister(childRun);
            return query("end_turn", null);
        });

        var result = executor.executeSync(request(), parent);

        assertThat(result.status()).isEqualTo("completed");
        assertThat(result.result()).startsWith("Delivery: delivered");
        verify(manager).finishWorktree(tree.getParent(), true);
        verify(sessions, timeout(2000)).closeSubAgentSession(child);
        assertThat(sessions.getFileStateCache(parent.sessionId()).toMap()).isEmpty();
        assertThat(oldParentCache.toMap()).isEmpty();
        assertThat(oldParentCache.hasBeenRead(parentFile.toString())).isFalse();
        assertThat(oldParentCache.isStale(parentFile.toString())).isTrue();
        // A preexisting ordinary/fork child may still merge into its captured object. It cannot
        // register that obsolete snapshot back as the parent's current cache.
        oldParentCache.merge(preDeliverySnapshot);
        assertThat(sessions.getFileStateCache(parent.sessionId())).isNotSameAs(oldParentCache);
        assertThat(sessions.getFileStateCache(parent.sessionId()).toMap()).isEmpty();
        assertThat(slots.getActiveCount()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"error,failed", "timeout,timeout", "max_turns,max_turns", "cancelled,interrupted"})
    void unsuccessfulResultsNeverDeliverOrDelete(String stop, String status) {
        when(engine.execute(any(), any(), any())).thenAnswer(call -> {
            register(call.getArgument(1));
            runs.unregister(childRun);
            return query(stop, "failure fixture");
        });
        var result = executor.executeSync(request(), parent);
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.result()).contains("Recovery worktree:", "failure fixture");
        verify(manager, never()).finishWorktree(any(), anyBoolean());
    }

    @Test
    void timeoutKeepsActualSlotLeaseAndResourcesUntilWorkerExitsAndNeverLateDelivers() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(engine.execute(any(), any(), any())).thenAnswer(call -> {
            register(call.getArgument(1));
            entered.countDown();
            release.await(); // Cancellation is intentionally ignored by this fixture.
            runs.unregister(childRun);
            return query("end_turn", null);
        });
        try {
            CompletableFuture<SubAgentExecutor.AgentResult> pending = CompletableFuture.supplyAsync(
                    () -> executor.executeSync(request(), parent));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            var result = pending.get(4, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo("timeout");
            assertThat(slots.getActiveCount()).isOne();
            assertThat(gate.tryAcquireMerge(dataSource, parent.sessionId(), "merge")).isNull();
            verify(sessions, never()).closeSubAgentSession(child);
            assertThat(executor.executeSync(request(), parent).result()).contains("still owns");
            release.countDown();
            verify(sessions, timeout(2000)).closeSubAgentSession(child);
            assertThat(slots.getActiveCount()).isZero();
            verify(manager, never()).finishWorktree(any(), anyBoolean());
            var merge = gate.tryAcquireMerge(dataSource, parent.sessionId(), "after-exit");
            assertThat(merge).isNotNull();
            merge.close();
        } finally {
            release.countDown();
        }
    }

    @Test
    void successDuringGraceCannotReverseTimeout() {
        timeouts.setGracefulShutdownSeconds(2);
        when(engine.execute(any(), any(), any())).thenAnswer(call -> {
            register(call.getArgument(1));
            abort.onAbort().get(3, TimeUnit.SECONDS);
            runs.unregister(childRun);
            return query("end_turn", null);
        });
        var result = executor.executeSync(request(), parent);
        assertThat(result.status()).isEqualTo("timeout");
        verify(manager, never()).finishWorktree(any(), anyBoolean());
    }

    @Test
    void interruptRequestsCancellationButDoesNotPrematurelyReleaseWorker() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<SubAgentExecutor.AgentResult> result = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        when(engine.execute(any(), any(), any())).thenAnswer(call -> {
            register(call.getArgument(1));
            entered.countDown();
            release.await();
            runs.unregister(childRun);
            return query("end_turn", null);
        });
        Thread caller = Thread.ofVirtual().start(() -> {
            result.set(executor.executeSync(request(), parent));
            interrupted.set(Thread.currentThread().isInterrupted());
        });
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            caller.interrupt();
            caller.join(2000);
            assertThat(result.get().status()).isEqualTo("interrupted");
            assertThat(interrupted.get()).isTrue();
            assertThat(slots.getActiveCount()).isOne();
            verify(engine).abort(child, AbortReason.USER_INTERRUPT);
            verify(runs, never()).beginCompletion("parent-run");
        } finally {
            release.countDown();
        }
        verify(sessions, timeout(2000)).closeSubAgentSession(child);
    }

    @Test
    void cancellationBeforeRunRegistrationIsRemembered() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), allowRegistration = new CountDownLatch(1);
        when(engine.execute(any(), any(), any())).thenAnswer(call -> {
            entered.countDown();
            allowRegistration.await();
            assertThat(abort.isAborted()).isTrue();
            register(call.getArgument(1));
            runs.unregister(childRun);
            return query("end_turn", null);
        });
        try {
            var pending = CompletableFuture.supplyAsync(() -> executor.executeSync(request(), parent));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(pending.get(4, TimeUnit.SECONDS).status()).isEqualTo("timeout");
            allowRegistration.countDown();
            verify(sessions, timeout(2000)).closeSubAgentSession(child);
            verify(manager, never()).finishWorktree(any(), anyBoolean());
        } finally {
            allowRegistration.countDown();
        }
    }

    @Test
    void finishedWorkerWithOutstandingRunWorkKeepsLeaseAndDoesNotDeliver() throws Exception {
        AtomicReference<RunExecutionRegistry.WorkLease> work = new AtomicReference<>();
        when(engine.execute(any(), any(), any())).thenAnswer(call -> {
            register(call.getArgument(1));
            work.set(runs.acquireWork(childRun, "test", "still-running"));
            return query("end_turn", null);
        });
        try {
            assertThat(executor.executeSync(request(), parent).status()).isEqualTo("failed");
            assertThat(slots.getActiveCount()).isZero();
            assertThat(gate.tryAcquireMerge(dataSource, parent.sessionId(), "busy")).isNull();
            verify(sessions, never()).closeSubAgentSession(child);
            work.get().close();
            verify(sessions, timeout(2000)).closeSubAgentSession(child);
            verify(manager, never()).finishWorktree(any(), anyBoolean());
        } finally {
            if (work.get() != null) work.get().close();
        }
    }

    @ParameterizedTest
    @CsvSource({"NONE,completed", "PENDING,failed", "UNKNOWN,failed"})
    void liveBackgroundServicePreservesWorkspaceWithoutMisclassifyingNoDeliveryTasks(
            WorktreeManager.PendingDelivery pending, String status) throws Exception {
        AtomicBoolean backgroundRunning = new AtomicBoolean(true);
        when(processes.currentSessionBackground(child)).thenAnswer(ignored ->
                new ManagedProcessRunner.CancelSummary(backgroundRunning.get() ? 1 : 0, 0,
                        backgroundRunning.get() ? 1 : 0));
        when(manager.inspectPendingDelivery(any())).thenReturn(pending);
        when(engine.execute(any(), any(), any())).thenAnswer(call -> {
            register(call.getArgument(1)); runs.unregister(childRun); return query("end_turn", null);
        });
        try {
            var result = executor.executeSync(request(), parent);
            assertThat(result.status()).isEqualTo(status);
            assertThat(result.result()).contains("background service", "Recovery worktree:");
            assertThat(slots.getActiveCount()).isZero();
            verify(sessions, never()).closeSubAgentSession(child);
            verify(manager, never()).finishWorktree(any(), anyBoolean());
        } finally {
            backgroundRunning.set(false);
        }
        verify(sessions, timeout(2000)).closeSubAgentSession(child);
    }

    @Test
    void initializationFailurePreservesCreatedTreeAndReleasesUnstartedResources() {
        doThrow(new IllegalStateException("session fixture failed")).when(sessions)
                .registerSubAgentSession(eq(child), anyString(), anyString());
        var result = executor.executeSync(request(), parent);
        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.result()).contains("Recovery worktree:", "session fixture failed");
        assertThat(slots.getActiveCount()).isZero();
        verify(manager).setWorkerActive(tree.getParent(), false);
        verify(engine, never()).execute(any(), any(), any());
    }

    @Test
    void rejectedSubmissionRetainsCreatedTreeAndReleasesUnstartedResourcesExactlyOnce() {
        List<SessionExecutionGate.BackgroundLease> acquiredLeases = new ArrayList<>();
        doAnswer(call -> {
            var lease = spy(gate.acquireBackground(dataSource, parent.sessionId()));
            acquiredLeases.add(lease);
            return lease;
        }).when(sessions).acquireBackgroundLease(parent.sessionId());
        executor = spy(executor);
        doThrow(new RejectedExecutionException("submission fixture rejected"))
                .when(executor).submitWorktreeWorker(any(Runnable.class));

        var result = executor.executeSync(request(), parent);

        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.result()).contains("Recovery worktree:", "submission fixture rejected");
        assertThat(tree).exists();
        verify(manager).createWorktree(agentId, parent, false);
        verify(manager).retain(eq(tree.getParent()), contains("submission fixture rejected"));
        verify(manager).setWorkerActive(tree.getParent(), false);
        verify(manager, never()).finishWorktree(any(), anyBoolean());
        verify(executor).submitWorktreeWorker(any(Runnable.class));
        verify(engine, never()).execute(any(), any(), any());
        verify(sessions).closeSubAgentSession(child);
        verify(engine).removeAbortContext(child);
        assertThat(caches).doesNotContainKey(child);
        assertThat(slots.getActiveCount()).isZero();
        assertThat(slots.getSessionActiveCount(parent.sessionId())).isZero();
        assertThat(acquiredLeases).hasSize(2); // Outer finalization and internal actual-worker leases.
        acquiredLeases.forEach(lease -> verify(lease).close());
        var merge = gate.tryAcquireMerge(dataSource, parent.sessionId(), "after-rejected-submit");
        assertThat(merge).isNotNull();
        merge.close();
        assertThat((Map<?, ?>) ReflectionTestUtils.getField(executor, "worktreeExecutions")).isEmpty();
    }

    @Test
    void actualEngineStartupFailureReleasesOnlyItsOwnLeasesAndRetainsFailedTree() {
        List<SessionExecutionGate.BackgroundLease> acquiredLeases = new CopyOnWriteArrayList<>();
        doAnswer(call -> {
            var lease = spy(gate.acquireBackground(dataSource, parent.sessionId()));
            acquiredLeases.add(lease);
            return lease;
        }).when(sessions).acquireBackgroundLease(parent.sessionId());
        useActualStartupFailure(state -> assertThat(state.getRunStartupFailure().sessionId()).isEqualTo(child));
        try (var unrelated = gate.acquireBackground(dataSource, parent.sessionId())) {
            var result = executor.executeSync(request(), parent);

            assertThat(result.status()).isEqualTo("failed");
            assertThat(result.result()).contains("RUN_EXECUTION_REGISTRATION_FAILED", "Recovery worktree:");
            verify(sessions, timeout(2000)).closeSubAgentSession(child);
            verify(engine, timeout(2000)).removeAbortContext(child);
            verify(manager).setWorkerActive(tree.getParent(), false);
            verify(manager, never()).finishWorktree(any(), anyBoolean());
            assertThat(tree).exists();
            assertThat(slots.getActiveCount()).isZero();
            assertThat(runs.activeRunForSession(child)).isEmpty();
            assertThat(acquiredLeases).hasSize(2);
            acquiredLeases.forEach(lease -> verify(lease, times(1)).close());
            assertThat(gate.tryAcquireMerge(dataSource, parent.sessionId(), "unrelated-owner")).isNull();
        }
        var merge = gate.tryAcquireMerge(dataSource, parent.sessionId(), "after-startup-failure");
        assertThat(merge).isNotNull();
        merge.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {"active", "null", "exception"})
    void startupFailureDoesNotReleaseLeaseWhileBackgroundIsActiveOrUnknown(String occupancy) {
        AtomicBoolean blocked = new AtomicBoolean(true);
        when(processes.currentSessionBackground(child)).thenAnswer(call -> {
            if (!blocked.get()) return new ManagedProcessRunner.CancelSummary(0, 0, 0);
            if ("null".equals(occupancy)) return null;
            if ("exception".equals(occupancy)) throw new IllegalStateException("inspection unavailable");
            return new ManagedProcessRunner.CancelSummary(1, 0, 1);
        });
        useActualStartupFailure(state -> { });
        try {
            var result = executor.executeSync(request(), parent);
            assertThat(result.status()).isEqualTo("failed");
            assertThat(result.result()).contains("RUN_EXECUTION_REGISTRATION_FAILED", "Recovery worktree:");
            assertThat(slots.getActiveCount()).isZero();
            assertThat(gate.tryAcquireMerge(dataSource, parent.sessionId(), "still-occupied")).isNull();
            verify(sessions, never()).closeSubAgentSession(child);
            verify(manager, never()).setWorkerActive(any(), eq(false));
            verify(manager, never()).finishWorktree(any(), anyBoolean());
        } finally {
            blocked.set(false);
        }
        if ("active".equals(occupancy)) verify(sessions, timeout(2000)).closeSubAgentSession(child);
    }

    @Test
    void startupFailureDoesNotReclaimAConflictingSessionRun() {
        RunTracker tracker = useActualStartupFailure(state -> { });
        doAnswer(call -> {
            runs.register("foreign-run", child, new AbortContext());
            throw new IllegalStateException("startup failed after session identity was reused");
        }).when(tracker).startRun(anyString(), anyString(), anyString(), anyString());
        try {
            var result = executor.executeSync(request(), parent);
            assertThat(result.status()).isEqualTo("failed");
            assertThat(result.result()).contains("RUN_EXECUTION_REGISTRATION_FAILED");
            assertThat(gate.tryAcquireMerge(dataSource, parent.sessionId(), "foreign-run-still-active")).isNull();
            verify(runs, never()).beginCompletion("foreign-run");
            verify(runs, never()).unregister("foreign-run");
            verify(sessions, never()).closeSubAgentSession(child);
            verify(manager, never()).setWorkerActive(any(), eq(false));
        } finally {
            runs.unregister("foreign-run");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"end_turn", "error"})
    void missingChildIdentityNeverCancelsOrClosesTheParentRun(String reason) {
        when(engine.execute(any(), any(), any())).thenReturn(query(reason,
                "error".equals(reason) ? "RUN_EXECUTION_REGISTRATION_FAILED" : null));
        var result = executor.executeSync(request(), parent);
        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.result()).contains("error".equals(reason)
                ? "RUN_EXECUTION_REGISTRATION_FAILED" : "termination is unconfirmed");
        assertThat(gate.tryAcquireMerge(dataSource, parent.sessionId(), "unknown-run")).isNull();
        verify(runs, never()).beginCompletion("parent-run");
        verify(sessions, never()).closeSubAgentSession(child);
        verify(manager, never()).setWorkerActive(any(), eq(false));
        verify(manager, never()).finishWorktree(any(), anyBoolean());
        assertThat(slots.getActiveCount()).isZero();
    }

    @Test
    void mismatchedPublishedRunIsNeverClosedEvenAfterActualWorkerExit() {
        when(engine.execute(any(), any(), any())).thenAnswer(call -> {
            QueryLoopState state = call.getArgument(1);
            register(state);
            runs.register("unrelated-run", "unrelated-session", new AbortContext());
            state.setToolUseContext(state.getToolUseContext().withCurrentRunId("unrelated-run"));
            return query("end_turn", null);
        });
        try {
            assertThat(executor.executeSync(request(), parent).status()).isEqualTo("failed");
            verify(runs, never()).beginCompletion("unrelated-run");
            verify(runs, never()).beginCompletion("parent-run");
            verify(manager, never()).finishWorktree(any(), anyBoolean());
        } finally {
            runs.unregister(childRun);
            runs.unregister("unrelated-run");
        }
    }

    @Test
    void deliveredResultStaysCompletedWhenOnlyCleanupFails() {
        when(engine.execute(any(), any(), any())).thenAnswer(call -> {
            register(call.getArgument(1)); runs.unregister(childRun); return query("end_turn", null);
        });
        when(manager.finishWorktree(any(), eq(true))).thenReturn(new WorktreeManager.DeliveryResult(
                true, true, "Delivery: delivered. Cleanup: warning — branch retained; do not rerun.", "branch retained"));
        var result = executor.executeSync(request(), parent);
        assertThat(result.status()).isEqualTo("completed");
        assertThat(result.result()).contains("Cleanup: warning", "do not rerun");
        verify(manager, times(1)).finishWorktree(any(), eq(true));
    }

    @Test
    void deliveryFailureAndCleanupWarningKeepTheirDistinctResultStatesAndInvalidateCache() {
        when(engine.execute(any(), any(), any())).thenAnswer(call -> {
            register(call.getArgument(1)); runs.unregister(childRun); return query("end_turn", null);
        });
        when(manager.finishWorktree(any(), eq(true))).thenReturn(
                new WorktreeManager.DeliveryResult(false, true,
                        "Delivery: unknown\nRecovery worktree: " + tree.getParent(), null));
        var result = executor.executeSync(request(), parent);
        assertThat(result.status()).isEqualTo("failed");
        verify(sessions, atLeastOnce()).removeFileStateCache(parent.sessionId());
        assertThat(result.result()).startsWith("Delivery: unknown");
    }

    @Test
    void unconfirmedGitInvalidatesAgainWhenTheOperationActuallySettles() throws Exception {
        AtomicReference<Runnable> settled = new AtomicReference<>();
        doAnswer(call -> { settled.set(call.getArgument(1)); return null; })
                .when(manager).whenTargetSettled(any(), any());
        when(engine.execute(any(), any(), any())).thenAnswer(call -> {
            register(call.getArgument(1)); runs.unregister(childRun); return query("end_turn", null);
        });
        when(manager.finishWorktree(any(), eq(true))).thenReturn(new WorktreeManager.DeliveryResult(
                false, true, "Git termination unconfirmed; Recovery worktree: " + tree.getParent(), null));
        assertThat(executor.executeSync(request(), parent).status()).isEqualTo("failed");
        FileStateCache readWhileGitWasUnsettled = sessions.getFileStateCache(parent.sessionId());
        Path transientFile = Files.writeString(workspace.resolve("target"), "transient");
        readWhileGitWasUnsettled.markRead(transientFile.toString(), "transient", null, null, false);
        assertThat(readWhileGitWasUnsettled.isStale(transientFile.toString())).isFalse();
        settled.get().run();
        assertThat(readWhileGitWasUnsettled.toMap()).isEmpty();
        assertThat(readWhileGitWasUnsettled.hasBeenRead(transientFile.toString())).isFalse();
        assertThat(readWhileGitWasUnsettled.isStale(transientFile.toString())).isTrue();
        assertThat(sessions.getFileStateCache(parent.sessionId())).isNotSameAs(readWhileGitWasUnsettled);
        assertThat(sessions.getFileStateCache(parent.sessionId()).toMap()).isEmpty();
    }

    @Test
    void outputFileFailurePreservesRecoveryFactsInActualParentFormatter() throws Exception {
        Path output = Path.of(System.getProperty("java.io.tmpdir"), "agent-" + agentId + "-output.txt");
        Files.createDirectory(output); // Deterministic write failure, outside the project.
        when(coordinator.isCoordinatorMode()).thenReturn(true);
        when(manager.finishWorktree(any(), eq(true))).thenReturn(new WorktreeManager.DeliveryResult(
                false, false, "Recovery worktree: " + tree.getParent() + "\nRecovery branch: <agent&branch>\nDelivery: preserved", null));
        when(engine.execute(any(), any(), any())).thenAnswer(call -> {
            QueryLoopState state = call.getArgument(1);
            register(state);
            Message.AssistantMessage answer = new Message.AssistantMessage("long-answer", Instant.now(),
                    List.of(new ContentBlock.TextBlock("x".repeat(110_000))), "end_turn", null);
            state.recordCurrentRunAssistant(answer);
            state.setCurrentRunFinalMessageId(answer.uuid());
            runs.unregister(childRun);
            return new QueryEngine.QueryResult(List.of(answer), null, "end_turn", null, 1);
        });
        CountDownLatch terminalPublished = new CountDownLatch(1);
        doAnswer(call -> {
            Object returned = call.callRealMethod();
            terminalPublished.countDown();
            return returned;
        }).when(tracker).markFailed(eq(agentId), anyString());
        try {
            executor.executeAsync(request(), parent);
            assertThat(terminalPublished.await(3, TimeUnit.SECONDS)).isTrue();
            var status = tracker.getStatus(agentId);
            assertThat(status.status()).isEqualTo("failed");
            String rendered = ReflectionTestUtils.invokeMethod(engine, "formatAgentResults", List.of(status));
            assertThat(rendered).contains("Recovery worktree:", tree.getParent().toString(),
                    "Delivery: preserved", "&lt;agent&amp;branch&gt;", "Do not rerun or merge");
        } finally {
            Files.deleteIfExists(output);
        }
    }

    @Test
    void ordinaryAgentTimeoutDoesNotClaimTerminationConfirmed() {
        SubAgentExecutor ordinary = mock(SubAgentExecutor.class);
        when(ordinary.executeSync(any(), any())).thenReturn(new SubAgentExecutor.AgentResult(
                "timeout", "Execution termination unconfirmed", "task", null));
        AgentTool tool = new AgentTool(ordinary, mock(LlmProviderRegistry.class));
        ToolResult result = tool.call(ToolInput.from(Map.of("prompt", "task")), parent);
        assertThat(result.metadata()).containsEntry("terminationConfirmed", false);
    }

    @ParameterizedTest(name = "actual Git delivery, successful query={0}")
    @ValueSource(booleans = {true, false})
    void realGitDeliveryAcrossExecutorManagerAndRunner(boolean success) throws Exception {
        timeouts.setDefaultSeconds(10);
        timeouts.setMaxSeconds(10);
        Path repository = Files.createDirectory(workspace.resolve("real-repository")).toRealPath();
        Path sourceDirectory = Files.createDirectory(repository.resolve("backend"));
        Files.writeString(sourceDirectory.resolve("base.txt"), "initial\n");
        fixtureGit(repository, "init", "-q");
        fixtureGit(repository, "config", "user.name", "Worktree Integration Fixture");
        fixtureGit(repository, "config", "user.email", "worktree-fixture@example.invalid");
        fixtureGit(repository, "config", "commit.gpgsign", "false");
        fixtureGit(repository, "config", "core.hooksPath", repository.resolve(".git/hooks").toString());
        fixtureGit(repository, "add", ".");
        fixtureGit(repository, "commit", "-qm", "fixture baseline");
        String before = fixtureGit(repository, "rev-parse", "HEAD").strip();
        parent = parent.withWorkingDirectory(sourceDirectory.toString());
        FileStateCache heldParentCache = sessions.getFileStateCache(parent.sessionId());
        heldParentCache.markRead(sourceDirectory.resolve("base.txt").toString(), "initial\n", null, null, false);

        AuthorizationSubjectResolver identities = mock(AuthorizationSubjectResolver.class);
        when(identities.resolve(parent.currentRunId())).thenReturn(new AuthorizationSubject(
                parent.sessionId(), parent.currentRunId(), parent.currentRunId(), "fixture-workspace", repository));
        ManagedProcessRunner realRunner = spy(new ManagedProcessRunner(runs));
        AtomicBoolean observedPostRunFinalization = new AtomicBoolean();
        AtomicBoolean childReturned = new AtomicBoolean();
        doAnswer(call -> {
            ManagedProcessRunner.Request gitRequest = call.getArgument(0);
            assertThat(gitRequest.ownership()).isEqualTo(ManagedProcessRunner.Ownership.SERVICE);
            assertThat(gitRequest.runId()).isNotIn(parent.currentRunId(), childRun);
            // The execution lease may already be releasable; the outer session lease must still
            // exclude session merge throughout real Git finalization after QueryEngine returns.
            assertThat(gate.tryAcquireMerge(dataSource, parent.sessionId(), "during-real-git")).isNull();
            if (childReturned.get()) {
                assertThat(runs.isRegistered(childRun)).isFalse();
                observedPostRunFinalization.set(true);
            }
            return call.callRealMethod(); // No Git command result or process state is mocked.
        }).when(realRunner).runRawGit(any());
        WorktreeManager realManager = new WorktreeManager(identities, new GitService(), realRunner);
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getEnabledToolsSorted()).thenReturn(List.of());
        SubAgentExecutor integrated = new SubAgentExecutor(slots, engine, registry, tracker, realManager,
                new TaskNotificationFormatter(), mock(FeatureFlagService.class), coordinator, sessions,
                null, mock(LlmProviderRegistry.class), timeouts, null, mock(CheckpointService.class), new ObjectMapper());
        integrated.setWorktreeLifecycleSupport(runs, realRunner);
        AtomicReference<Path> actualTree = new AtomicReference<>();
        AtomicReference<String> agentBranch = new AtomicReference<>();
        when(engine.execute(any(), any(), any())).thenAnswer(call -> {
            QueryLoopState state = call.getArgument(1);
            QueryConfig config = call.getArgument(0);
            Path cwd = Path.of(state.getToolUseContext().workingDirectory());
            actualTree.set(cwd.getParent());
            assertThat(cwd).isNotEqualTo(sourceDirectory);
            assertThat(cwd.getFileName().toString()).isEqualTo("backend");
            assertThat(config.systemPrompt()).contains("工作目录：" + cwd);
            assertThat(sessions.getFileStateCache(child).toMap()).isEmpty();
            assertThat(Files.readString(cwd.resolve("base.txt"))).isEqualTo("initial\n");
            register(state);
            Files.writeString(cwd.resolve("base.txt"), "child change\n");
            Files.writeString(cwd.resolve("result.txt"), "agent output\n");
            sessions.getFileStateCache(child).markRead(cwd.resolve("base.txt").toString(),
                    "child change\n", null, null, false);
            agentBranch.set(fixtureGit(cwd, "branch", "--show-current").strip());
            runs.beginCompletion(childRun);
            assertThat(runs.awaitQuiescence(childRun, java.time.Duration.ZERO)).isTrue();
            runs.unregister(childRun);
            childReturned.set(true);
            return query(success ? "end_turn" : "error", success ? null : "controlled query failure");
        });
        try {
            var result = integrated.executeSync(request(), parent);
            assertThat(result.status()).as(result.result()).isEqualTo(success ? "completed" : "failed");
            assertThat(actualTree.get()).isNotNull();
            verify(sessions, timeout(2000)).closeSubAgentSession(child);
            assertThat(slots.getActiveCount()).isZero();
            var leaseCheck = gate.tryAcquireMerge(dataSource, parent.sessionId(), "after-real-finalization");
            assertThat(leaseCheck).isNotNull();
            leaseCheck.close();
            assertThat(realRunner.currentRunTermination(childRun).allTerminated()).isTrue();
            assertThat(realRunner.currentSessionBackground(child).allTerminated()).isTrue();
            if (success) {
                assertThat(Files.readString(sourceDirectory.resolve("base.txt"))).isEqualTo("child change\n");
                assertThat(Files.readString(sourceDirectory.resolve("result.txt"))).isEqualTo("agent output\n");
                assertThat(fixtureGit(repository, "rev-parse", "HEAD").strip()).isNotEqualTo(before);
                assertThat(fixtureGit(repository, "status", "--porcelain")).isEmpty();
                assertThat(actualTree.get()).doesNotExist();
                assertThat(fixtureGit(repository, "branch", "--list", agentBranch.get())).isBlank();
                assertThat(realManager.getActiveCount()).isZero();
                assertThat(heldParentCache.toMap()).isEmpty();
                assertThat(heldParentCache.hasBeenRead(sourceDirectory.resolve("base.txt").toString())).isFalse();
                assertThat(heldParentCache.isStale(sourceDirectory.resolve("base.txt").toString())).isTrue();
                assertThat(sessions.getFileStateCache(parent.sessionId()).toMap()).isEmpty();
                assertThat(observedPostRunFinalization.get()).isTrue();
            } else {
                assertThat(Files.readString(sourceDirectory.resolve("base.txt"))).isEqualTo("initial\n");
                assertThat(sourceDirectory.resolve("result.txt")).doesNotExist();
                assertThat(fixtureGit(repository, "rev-parse", "HEAD").strip()).isEqualTo(before);
                assertThat(actualTree.get()).exists();
                assertThat(Files.readString(actualTree.get().resolve("backend/result.txt"))).isEqualTo("agent output\n");
                assertThat(fixtureGit(repository, "branch", "--list", agentBranch.get())).isNotBlank();
                assertThat(realManager.getActiveCount()).isOne();
                assertThat(result.result()).contains(actualTree.get().toString(), agentBranch.get(), "controlled query failure");
                assertThat(sessions.getFileStateCache(parent.sessionId()).hasBeenRead(
                        sourceDirectory.resolve("base.txt").toString())).isTrue();
                assertThat(observedPostRunFinalization.get()).isFalse();
            }
        } finally {
            // The entire repository is this test's fixture. Remove only its retained linked trees;
            // never invoke the application stop script or clean any development checkout.
            for (String line : fixtureGit(repository, "worktree", "list", "--porcelain").lines().toList()) {
                if (line.startsWith("worktree ")) {
                    Path remaining = Path.of(line.substring("worktree ".length()));
                    if (!remaining.equals(repository) && Files.exists(remaining)) {
                        fixtureGit(repository, "worktree", "remove", "--force", remaining.toString());
                    }
                }
            }
        }
    }

    private static String fixtureGit(Path cwd, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true);
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process process = builder.start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Fixture Git timed out: " + command);
        }
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.exitValue()).as("%s: %s", command, output).isZero();
        return output;
    }

    /** Real startup/return path; model/tool execution and every post-start dependency are unused. */
    private RunTracker useActualStartupFailure(java.util.function.Consumer<QueryLoopState> afterFailure) {
        RunTracker tracker = mock(RunTracker.class);
        doThrow(new IllegalStateException("startup persistence unavailable")).when(tracker)
                .startRun(anyString(), anyString(), anyString(), anyString());
        LlmProviderRegistry providers = mock(LlmProviderRegistry.class);
        StreamingToolExecutor toolExecutor = mock(StreamingToolExecutor.class);
        QueryEngine realEngine = new QueryEngine(providers, null, null, null,
                new ObjectMapper(), toolExecutor, null, null, null, null,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, timeouts,
                null, null, tracker, runs, null);
        when(engine.execute(any(), any(), any())).thenAnswer(call -> {
            QueryLoopState state = call.getArgument(1);
            var result = realEngine.execute(call.getArgument(0), state, call.getArgument(2));
            assertThat(result.error()).isEqualTo("RUN_EXECUTION_REGISTRATION_FAILED");
            verifyNoInteractions(providers, toolExecutor);
            afterFailure.accept(state);
            return result;
        });
        return tracker;
    }

    private void register(QueryLoopState state) {
        runs.register(childRun, child, abort);
        state.setToolUseContext(state.getToolUseContext().withCurrentRunId(childRun));
    }

    private SubAgentExecutor.AgentRequest request() {
        return new SubAgentExecutor.AgentRequest(agentId, "Inspect the isolated fixture", "general-purpose",
                null, SubAgentExecutor.IsolationMode.WORKTREE, false);
    }

    private static QueryEngine.QueryResult query(String reason, String error) {
        return new QueryEngine.QueryResult(List.of(), null, reason, error, 1);
    }
}
