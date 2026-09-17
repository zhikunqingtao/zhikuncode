package com.aicodeassistant.tool.agent;

import com.aicodeassistant.engine.AbortContext;
import com.aicodeassistant.engine.AbortReason;
import com.aicodeassistant.tool.ToolUseContext;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BackgroundAgentCompletionTest {
    private final BackgroundAgentTracker tracker = new BackgroundAgentTracker(mock(SimpMessagingTemplate.class));

    @Test void backgroundWaitingIsEnabledInPackagedDefaults() {
        var yaml = new org.springframework.beans.factory.config.YamlPropertiesFactoryBean();
        yaml.setResources(new org.springframework.core.io.ClassPathResource("application.yml"));
        assertEquals("true", yaml.getObject().getProperty("features.flags.BACKGROUND_AGENT_WAIT"));
    }

    @Test void cleanupKeepsUndeliveredFilesWhileParentRunIsActive() throws Exception {
        var file = Files.createTempFile("background-retention", ".txt");
        try {
            tracker.retainRun("run");
            tracker.register("done", "session", "run", "review", file.toString());
            tracker.markCompleted("done", new SubAgentExecutor.AgentResult("completed", "evidence", "review", null));
            var later = java.time.Instant.now().plus(Duration.ofHours(1));
            try (var clock = mockStatic(java.time.Instant.class, CALLS_REAL_METHODS)) {
                clock.when(java.time.Instant::now).thenReturn(later);
                tracker.cleanup();
                assertNotNull(tracker.getStatus("done"));
                assertTrue(Files.exists(file));
                tracker.releaseRun("run");
                tracker.cleanup();
                assertNull(tracker.getStatus("done"));
                assertFalse(Files.exists(file));
            }
        } finally { Files.deleteIfExists(file); }
    }

    @Test void completionBeforeWaitingIsCollectedAndOtherRunsAreIsolated() {
        tracker.register("old", "session", "old-run", "old", null);
        tracker.register("done", "session", "run", "new", null);
        tracker.markCompleted("done", new SubAgentExecutor.AgentResult("completed", "done", "new", null));
        assertEquals(BackgroundAgentTracker.WaitResult.COMPLETED,
                tracker.awaitRun("session", "run", Duration.ZERO, new AbortContext()));
        assertEquals(java.util.List.of("done"), tracker.listForRun("session", "run").stream().map(BackgroundAgentTracker.AgentStatus::agentId).toList());
        assertTrue(tracker.listForRun("session", null).isEmpty());
    }

    @Test void returnedFailureStatusesAreNotRewrittenAsCompleted() {
        for (String status : java.util.List.of("failed", "timeout", "interrupted", "max_turns")) {
            tracker.register(status, "session", "run", "work", null);
            tracker.markCompleted(status, new SubAgentExecutor.AgentResult(status, "partial evidence", "work", null));
            assertEquals(status, tracker.getStatus(status).status());
            assertEquals("partial evidence", tracker.getStatus(status).error());
            tracker.markCompleted(status, new SubAgentExecutor.AgentResult("completed", "late", "work", null));
            assertEquals(status, tracker.getStatus(status).status());
        }
    }

    @Test void cancellationWakesSleepingWaiterWithoutAnotherAgentCompletion() throws Exception {
        tracker.register("pending", "session", "run", "work", null);
        var registered = new CountDownLatch(1);
        AbortContext signal = new AbortContext() {
            @Override public com.aicodeassistant.llm.CancellationSignal.Registration register(Runnable callback) {
                var registration = super.register(callback);
                registered.countDown();
                return registration;
            }
        };
        var future = CompletableFuture.supplyAsync(() -> tracker.awaitRun("session", "run", Duration.ofMinutes(10), signal));
        assertTrue(registered.await(2, TimeUnit.SECONDS));
        signal.abort(AbortReason.USER_INTERRUPT);
        assertEquals(BackgroundAgentTracker.WaitResult.CANCELLED, future.get(2, TimeUnit.SECONDS));
        assertEquals("running", tracker.getStatus("pending").status());
    }

    @Test void lateCompletionWakesWaiterAndTimeoutDoesNotDeclareChildStopped() throws Exception {
        tracker.register("pending", "session", "run", "work", null);
        assertEquals(BackgroundAgentTracker.WaitResult.TIMED_OUT,
                tracker.awaitRun("session", "run", Duration.ZERO, new AbortContext()));
        assertEquals("running", tracker.getStatus("pending").status());
        var future = CompletableFuture.supplyAsync(() -> tracker.awaitRun("session", "run", Duration.ofSeconds(2), new AbortContext()));
        tracker.markFailed("pending", "failed");
        assertEquals(BackgroundAgentTracker.WaitResult.COMPLETED, future.get(2, TimeUnit.SECONDS));
    }

    @Test void asyncWorkerSeesRegistrationBeforeExecutionAndPreservesTimeout() throws Exception {
        var executor = spy(new SubAgentExecutor(null, null, null, tracker, null, null, null, null,
                mock(com.aicodeassistant.session.SessionManager.class), null, null, null, null, null, null));
        String id = "test-" + java.util.UUID.randomUUID();
        var request = new SubAgentExecutor.AgentRequest(id, "task", "worker", null, SubAgentExecutor.IsolationMode.NONE, true);
        var context = ToolUseContext.of("/tmp", "session").withCurrentRunId("run");
        doAnswer(inv -> {
            assertNotNull(tracker.getStatus(id));
            assertEquals("run", tracker.getStatus(id).parentRunId());
            return new SubAgentExecutor.AgentResult("timeout", "partial evidence", "task", null);
        }).when(executor).executeSync(request, context);
        var launched = executor.executeAsync(request, context);
        try {
            assertEquals("async_launched", launched.status());
            assertEquals(BackgroundAgentTracker.WaitResult.COMPLETED,
                    tracker.awaitRun("session", "run", Duration.ofSeconds(3), new AbortContext()));
            assertEquals("timeout", tracker.getStatus(id).status());
            assertEquals("partial evidence", Files.readString(Path.of(launched.outputFile())));
        } finally {
            Files.deleteIfExists(Path.of(launched.outputFile()));
        }
    }
}
