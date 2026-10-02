package com.aicodeassistant.tool.task;

import com.aicodeassistant.model.TaskStatus;
import com.aicodeassistant.tool.*;
import com.aicodeassistant.tool.agent.SubAgentExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Deterministic, in-memory regressions; never starts a server, shell or real agent. */
class TaskLifecycleTest {
    private final ToolUseContext owner = ToolUseContext.of("/tmp", "owner");
    private TaskCoordinator coordinator;
    private SimpMessagingTemplate messaging;
    private ScheduledExecutorService scheduler;
    private final List<Runnable> timeouts = new CopyOnWriteArrayList<>();

    @BeforeEach void setUp() {
        messaging = mock(SimpMessagingTemplate.class);
        scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenAnswer(call -> {
                    timeouts.add(call.getArgument(0));
                    return mock(ScheduledFuture.class);
                });
        coordinator = new TaskCoordinator(messaging, Executors.newVirtualThreadPerTaskExecutor(),
                scheduler, Duration.ofMinutes(30));
    }

    @AfterEach void cleanup() { coordinator.cleanup(); }

    private ManualExecutor useManualExecutor() {
        coordinator.cleanup();
        ManualExecutor executor = new ManualExecutor();
        coordinator = new TaskCoordinator(messaging, executor, scheduler, Duration.ofMinutes(30));
        return executor;
    }

    private static void await(TaskState state) throws Exception { state.getFuture().get(5, TimeUnit.SECONDS); }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try { latch.await(); break; }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    @ParameterizedTest
    @ValueSource(strings = {"agent", "shell", "local_workflow", "monitor_mcp", "dream"})
    void everyTaskTypePublishesItsActualOutput(String type) throws Exception {
        SubAgentExecutor agents = mock(SubAgentExecutor.class);
        TaskShellExecutor tools = mock(TaskShellExecutor.class);
        ToolRegistry registry = mock(ToolRegistry.class);
        when(agents.executeTaskSync(any(), any())).thenReturn(
                new SubAgentExecutor.AgentResult("completed", "final output", "prompt", null));
        when(registry.findByNameOptional("Bash")).thenReturn(Optional.of(mock(Tool.class)));
        when(tools.execute(any(), any(), anyString(), any()))
                .thenReturn(ToolExecutionResult.of(ToolResult.success("final output")));
        TaskState state = create(type, agents, tools, registry);
        await(state);
        assertEquals(TaskStatus.COMPLETED, state.getStatus());
        assertEquals("final output", state.getOutput());
        assertNull(state.getError());
        verify(agents, never()).executeSync(any(), any());
        if ("shell".equals(type)) {
            verify(tools).execute(any(), any(), eq(state.getTaskId()), same(owner));
        } else {
            verifyNoInteractions(tools);
        }
    }

    @ParameterizedTest
    @CsvSource({"completed,COMPLETED", "failed,FAILED", "timeout,FAILED", "max_turns,FAILED",
            "interrupted,CANCELLED", "async_launched,FAILED", "unexpected,FAILED"})
    void agentStatusesAreNotMistakenForSuccess(String agentStatus, TaskStatus expected) throws Exception {
        SubAgentExecutor agents = mock(SubAgentExecutor.class);
        when(agents.executeTaskSync(any(), any())).thenReturn(
                new SubAgentExecutor.AgentResult(agentStatus, "partial or final output", "prompt", null));
        TaskState state = create("agent", agents, mock(TaskShellExecutor.class), mock(ToolRegistry.class));
        await(state);
        assertEquals(expected, state.getStatus());
        assertEquals("partial or final output", state.getOutput());
        if (expected == TaskStatus.COMPLETED) assertNull(state.getError());
        else assertTrue(state.getError().contains(agentStatus));
    }

    @Test void missingAgentResultAndThrownExceptionFail() throws Exception {
        SubAgentExecutor agents = mock(SubAgentExecutor.class);
        TaskState missing = create("agent", agents, mock(TaskShellExecutor.class), mock(ToolRegistry.class));
        await(missing);
        assertEquals(TaskStatus.FAILED, missing.getStatus());
        assertTrue(missing.getError().contains("AGENT_RESULT_MISSING"));
        when(agents.executeTaskSync(any(), any())).thenThrow(new IllegalStateException("agent broke"));
        TaskState thrown = create("agent", agents, mock(TaskShellExecutor.class), mock(ToolRegistry.class));
        await(thrown);
        assertEquals(TaskStatus.FAILED, thrown.getStatus());
        assertTrue(thrown.getError().contains("agent broke"));
    }

    @ParameterizedTest
    @CsvSource(value = {"interval=30 do work|do work", "interval=bad do work|interval=bad do work",
            "interval=30|interval=30", "do work|do work"}, delimiter = '|')
    void monitorCompatibilityPreservesIntervalPrefixHandling(String prompt, String expected) {
        ManualExecutor executor = useManualExecutor();
        SubAgentExecutor agents = mock(SubAgentExecutor.class);
        when(agents.executeTaskSync(any(), any())).thenReturn(
                new SubAgentExecutor.AgentResult("completed", "done", prompt, null));
        ToolResult result = new TaskCreateTool(coordinator, agents, mock(ToolRegistry.class),
                mock(TaskShellExecutor.class)).call(ToolInput.from(Map.of(
                        "description", "test", "prompt", prompt, "taskType", "monitor_mcp")), owner);
        assertFalse(result.isError());
        executor.runNext();
        verify(agents).executeTaskSync(argThat(request -> expected.equals(request.prompt())
                && "monitor".equals(request.agentType())), same(owner));
    }

    @Test void nullAgentStatusIsFailure() {
        TaskExecutionResult result = TaskCreateTool.fromAgentResult(
                new SubAgentExecutor.AgentResult(null, "partial", "prompt", null));
        assertEquals(TaskStatus.FAILED, result.status());
        assertEquals("partial", result.output());
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "failed", "timeout", "cancelled", "nullWrapper", "nullResult", "missingBash", "exception"})
    void shellOutcomesArePreserved(String scenario) throws Exception {
        TaskShellExecutor tools = mock(TaskShellExecutor.class);
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.findByNameOptional("Bash")).thenReturn("missingBash".equals(scenario)
                ? Optional.empty() : Optional.of(mock(Tool.class)));
        ToolResult outcome = switch (scenario) {
            case "success" -> ToolResult.success("shell output");
            case "failed" -> ToolResult.process("shell output", 7, Map.of());
            case "timeout" -> ToolResult.timedOut("TEST_TIMEOUT", "shell output", 143, true);
            case "cancelled" -> ToolResult.cancelled("TEST_CANCELLED", "shell output", ToolResult.EffectState.UNKNOWN);
            default -> null;
        };
        if ("exception".equals(scenario)) {
            when(tools.execute(any(), any(), anyString(), any())).thenThrow(new IllegalStateException("shell broke"));
        } else {
            when(tools.execute(any(), any(), anyString(), any())).thenReturn(
                    "nullWrapper".equals(scenario) ? null : ToolExecutionResult.of(outcome));
        }
        TaskState state = create("shell", mock(SubAgentExecutor.class), tools, registry);
        await(state);
        TaskStatus expected = "success".equals(scenario) ? TaskStatus.COMPLETED
                : "cancelled".equals(scenario) ? TaskStatus.CANCELLED : TaskStatus.FAILED;
        assertEquals(expected, state.getStatus());
        if (outcome != null) assertEquals("shell output", state.getOutput());
        if (expected != TaskStatus.COMPLETED) assertNotNull(state.getError());
        if ("missingBash".equals(scenario)) {
            verify(tools, never()).execute(any(), any(), anyString(), any());
        } else {
            verify(tools).execute(any(), any(), eq(state.getTaskId()), same(owner));
        }
    }

    private TaskState create(String type, SubAgentExecutor agents, TaskShellExecutor tools,
                             ToolRegistry registry) {
        ToolResult acknowledgment = new TaskCreateTool(coordinator, agents, registry, tools).call(
                ToolInput.from(Map.of("description", "test", "prompt", "prompt", "taskType", type)), owner);
        assertFalse(acknowledgment.isError(), acknowledgment::content);
        String taskId = acknowledgment.content().split("#", 2)[1].split(" ", 2)[0];
        return coordinator.getTask(taskId).orElseThrow();
    }

    @Test void wrongSessionCannotReadChangeStopOrReportOutput() {
        useManualExecutor();
        TaskState task = coordinator.submit("secret", "owner", "private description", () -> {});
        ToolUseContext foreign = ToolUseContext.of("/tmp", "other").withCurrentTaskId("secret");
        List<ToolResult> attempts = List.of(
                new TaskGetTool(coordinator).call(ToolInput.from(Map.of("taskId", "secret")), foreign),
                new TaskUpdateTool(coordinator).call(ToolInput.from(Map.of("taskId", "secret", "output", "overwrite", "status", "COMPLETED")), foreign),
                new TaskStopTool(coordinator).call(ToolInput.from(Map.of("taskId", "secret")), foreign),
                new TaskOutputTool(coordinator).call(ToolInput.from(Map.of("output", "overwrite", "isError", true)), foreign));
        attempts.forEach(result -> {
            assertEquals("TASK_NOT_FOUND", result.failureCode());
            assertFalse(result.content().contains("private description"));
        });
        assertEquals("No tasks found.", new TaskListTool(coordinator).call(ToolInput.from(Map.of()), foreign).content());
        assertTrue(coordinator.getTask("secret", null).isEmpty());
        assertEquals(TaskStatus.PENDING, task.getStatus());
        assertFalse(task.isCancellationRequested());
        assertNull(task.getOutput());
        assertNull(task.getError());
    }

    @ParameterizedTest @NullSource @ValueSource(strings = {"COMPLETED", "CANCELLED", "invalid"})
    void statusUpdateIsRejectedWithoutPartialOutputMutation(String status) {
        useManualExecutor();
        TaskState task = coordinator.submit("task", "owner", "test", () -> {});
        coordinator.updateOutput("task", "owner", "before", false);
        Map<String, Object> values = new HashMap<>();
        values.put("taskId", "task"); values.put("status", status); values.put("output", "after");
        ToolResult result = new TaskUpdateTool(coordinator).call(ToolInput.from(values), owner);
        assertEquals("TASK_STATUS_READ_ONLY", result.failureCode());
        assertEquals(ToolResult.ToolFailureType.VALIDATION, result.failureType());
        assertEquals(ToolResult.EffectState.NOT_STARTED, result.effectState());
        assertEquals("before", task.getOutput());
        assertEquals(TaskStatus.PENDING, task.getStatus());
        assertEquals(TaskCoordinator.CancellationResult.REQUESTED, coordinator.cancelTask("task", "owner"));
    }

    @Test void finalOutputWinsAndTerminalManualReplacementDoesNotChangeFailure() throws Exception {
        ManualExecutor executor = useManualExecutor();
        TaskState task = coordinator.submitResult("task", "owner", "test",
                () -> TaskExecutionResult.failed("final", "actual error"));
        TaskUpdateTool update = new TaskUpdateTool(coordinator);
        update.call(ToolInput.from(Map.of("taskId", "task", "output", "progress")), owner);
        executor.runNext();
        await(task);
        assertEquals("final", task.getOutput());
        update.call(ToolInput.from(Map.of("taskId", "task", "output", "display replacement")), owner);
        assertEquals("display replacement", task.getOutput());
        assertEquals(TaskStatus.FAILED, task.getStatus());
        assertEquals("actual error", task.getError());
        update.call(ToolInput.from(Map.of("taskId", "task", "output", "")), owner);
        assertEquals("", task.getOutput());
    }

    @Test void nullFinalOutputKeepsProgressAndSuccessClearsTemporaryError() throws Exception {
        ManualExecutor executor = useManualExecutor();
        TaskState task = coordinator.submitResult("task", "owner", "test", () -> TaskExecutionResult.completed(null));
        coordinator.updateOutput("task", "owner", "progress", true);
        executor.runNext();
        await(task);
        assertEquals("progress", task.getOutput());
        assertNull(task.getError());
    }

    @Test void everyOutputEntryPointAndFinalErrorsAreBounded() throws Exception {
        ManualExecutor executor = useManualExecutor();
        String large = "x".repeat(TaskCoordinator.MAX_OUTPUT_SIZE + 500);
        TaskState task = coordinator.submitResult("task", "owner", "test", () -> TaskExecutionResult.failed(large, large));
        new TaskUpdateTool(coordinator).call(ToolInput.from(Map.of("taskId", "task", "output", large)), owner);
        assertEquals(TaskCoordinator.truncateOutput(large), task.getOutput());
        new TaskOutputTool(coordinator).call(ToolInput.from(Map.of("output", large, "isError", true)), owner.withCurrentTaskId("task"));
        assertEquals(TaskCoordinator.truncateOutput(large), task.getError());
        executor.runNext();
        await(task);
        assertEquals(TaskCoordinator.truncateOutput(large), task.getOutput());
        assertEquals(TaskCoordinator.truncateOutput(large), task.getError());
    }

    @Test void statusParsingIsLocaleIndependentAndDoesNotTrim() {
        useManualExecutor();
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            TaskListTool tool = new TaskListTool(coordinator);
            for (String valid : List.of("running", "in_progress", "PeNdInG")) {
                assertFalse(tool.call(ToolInput.from(Map.of("status", valid)), owner).isError());
            }
            for (String invalid : List.of("invalid", "", " running ")) {
                ToolResult result = tool.call(ToolInput.from(Map.of("status", invalid)), owner);
                assertEquals("TASK_STATUS_INVALID", result.failureCode());
                assertEquals(ToolResult.EffectState.NOT_STARTED, result.effectState());
            }
        } finally { Locale.setDefault(previous); }
    }

    @Test void pendingCancellationNeverRunsBodyAndReturnsCapacity() throws Exception {
        ManualExecutor executor = useManualExecutor();
        AtomicInteger calls = new AtomicInteger();
        TaskState task = coordinator.submit("pending", "owner", "test", calls::incrementAndGet);
        assertTrue(coordinator.cancelTask("pending"));
        await(task);
        executor.runNext();
        assertEquals(0, calls.get());
        assertEquals(TaskStatus.CANCELLED, task.getStatus());
        for (int i = 0; i < 10; i++) coordinator.submit("next-" + i, "owner", "test", () -> {});
    }

    @Test void cancellationKeepsRunningStateAndCapacityUntilBodyAndCleanupExit() throws Exception {
        CountDownLatch bodyReady = new CountDownLatch(1);
        CountDownLatch returnBody = new CountDownLatch(1);
        CountDownLatch cleanupEntered = new CountDownLatch(1);
        CountDownLatch releaseCleanup = new CountDownLatch(1);
        CountDownLatch releaseOthers = new CountDownLatch(1);
        try {
            TaskState task = coordinator.submitResult("cancel", "owner", "test", () -> {
                coordinator.registerCancellationCleanup("cancel", () -> {
                    cleanupEntered.countDown();
                    awaitUninterruptibly(releaseCleanup);
                });
                bodyReady.countDown();
                awaitUninterruptibly(returnBody);
                return TaskExecutionResult.completed("late output");
            });
            assertTrue(bodyReady.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 9; i++) coordinator.submit("other-" + i, "owner", "test", () -> awaitUninterruptibly(releaseOthers));
            assertTrue(coordinator.cancelTask("cancel"));
            assertEquals(TaskStatus.RUNNING, task.getStatus());
            assertFalse(task.getFuture().isDone());
            assertFalse(task.snapshot().terminationConfirmed());
            returnBody.countDown();
            assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS));
            assertThrows(TaskCoordinator.SubmissionException.class,
                    () -> coordinator.submit("overflow", "owner", "test", () -> {}));
            assertTrue(new TaskGetTool(coordinator).call(ToolInput.from(Map.of("taskId", "cancel")), owner)
                    .content().contains("Cancellation requested: true"));
            assertFalse(task.getFuture().isDone());
            assertTrue(new TaskGetTool(coordinator).call(ToolInput.from(Map.of("taskId", "cancel")), owner)
                    .content().contains("Termination confirmed: false"));
            assertTrue(new TaskListTool(coordinator).call(ToolInput.from(Map.of()), owner)
                    .content().contains("termination confirmed: false"));
            releaseCleanup.countDown();
            await(task);
            assertEquals(TaskStatus.CANCELLED, task.getStatus());
            assertTrue(task.snapshot().terminationConfirmed());
            assertTrue(new TaskListTool(coordinator).call(ToolInput.from(Map.of()), owner)
                    .content().contains("termination confirmed: true"));
            assertEquals("late output", task.getOutput());
            coordinator.submit("replacement", "owner", "test", () -> {});
            assertEquals(TaskCoordinator.CancellationResult.ALREADY_CANCELLED, coordinator.cancelTask("cancel", "owner"));
        } finally { returnBody.countDown(); releaseCleanup.countDown(); releaseOthers.countDown(); }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void firstAcceptedCancellationReasonWinsWhileAwaitingExit(boolean timeoutFirst) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            TaskState task = coordinator.submitResult("timeout", "owner", "test", () -> {
                started.countDown(); awaitUninterruptibly(release); return TaskExecutionResult.completed("partial");
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            if (timeoutFirst) timeouts.getFirst().run();
            else assertTrue(coordinator.cancelTask("timeout"));
            assertEquals(TaskStatus.RUNNING, task.getStatus());
            assertFalse(task.getFuture().isDone());
            if (timeoutFirst) assertTrue(coordinator.cancelTask("timeout"));
            else timeouts.getFirst().run();
            release.countDown(); await(task);
            assertEquals(timeoutFirst ? TaskStatus.FAILED : TaskStatus.CANCELLED, task.getStatus());
            if (timeoutFirst) assertTrue(task.getError().contains("timed out"));
            else assertNull(task.getError());
            assertEquals("partial", task.getOutput());
        } finally { release.countDown(); }
    }

    @Test void concurrentAdmissionNeverExceedsTenAndDuplicateIdDoesNotReplaceTask() throws Exception {
        useManualExecutor();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService submitters = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Future<Boolean>> submissions = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                String id = "parallel-" + i;
                submissions.add(submitters.submit(() -> {
                    start.await();
                    try { coordinator.submit(id, "owner", "test", () -> {}); return true; }
                    catch (TaskCoordinator.SubmissionException failure) {
                        assertEquals("TASK_CAPACITY_REACHED", failure.code()); return false;
                    }
                }));
            }
            start.countDown();
            int accepted = 0;
            for (Future<Boolean> submission : submissions) if (submission.get(5, TimeUnit.SECONDS)) accepted++;
            assertEquals(10, accepted);
            assertEquals(10, coordinator.listTaskSnapshots("owner", null).size());
        } finally { submitters.shutdownNow(); }
        coordinator.cleanup();
        useManualExecutor();
        TaskState original = coordinator.submit("duplicate", "owner", "original", () -> {});
        TaskCoordinator.SubmissionException error = assertThrows(TaskCoordinator.SubmissionException.class,
                () -> coordinator.submit("duplicate", "other", "replacement", () -> {}));
        assertEquals("TASK_ID_CONFLICT", error.code());
        assertSame(original, coordinator.getTask("duplicate").orElseThrow());
        for (int i = 0; i < 9; i++) coordinator.submit("remaining-" + i, "owner", "test", () -> {});
    }

    @Test void executorRejectionLeavesNoRecordNotificationOrCapacityLeak() {
        coordinator.cleanup();
        ExecutorService rejecting = mock(ExecutorService.class);
        doThrow(new RejectedExecutionException()).when(rejecting).execute(any());
        coordinator = new TaskCoordinator(messaging, rejecting, scheduler, Duration.ofMinutes(30));
        TaskCoordinator.SubmissionException error = assertThrows(TaskCoordinator.SubmissionException.class,
                () -> coordinator.submit("rejected", "owner", "test", () -> fail("must not execute")));
        assertEquals("TASK_SCHEDULER_UNAVAILABLE", error.code());
        assertTrue(coordinator.getTask("rejected").isEmpty());
        verifyNoInteractions(messaging);
        // Repeating more than the limit must still report scheduling, not capacity failure.
        for (int i = 0; i < 12; i++) {
            assertEquals("TASK_SCHEDULER_UNAVAILABLE", assertThrows(TaskCoordinator.SubmissionException.class,
                    () -> coordinator.submit("rejected", "owner", "test", () -> {})).code());
        }
    }

    @Test void watchdogRejectionDoesNotExecuteOrLeakCapacity() {
        ManualExecutor executor = useManualExecutor();
        when(scheduler.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenThrow(new RejectedExecutionException());
        for (int i = 0; i < 12; i++) {
            assertEquals("TASK_SCHEDULER_UNAVAILABLE", assertThrows(TaskCoordinator.SubmissionException.class,
                    () -> coordinator.submit("rejected", "owner", "test", () -> {})).code());
        }
        assertEquals(0, executor.queued.size());
        assertTrue(coordinator.listTaskSnapshots("owner", null).isEmpty());
        verifyNoInteractions(messaging);
    }

    @Test void capacityAcknowledgmentAccuratelyReportsNotStarted() {
        useManualExecutor();
        for (int i = 0; i < 10; i++) coordinator.submit("full-" + i, "owner", "test", () -> {});
        SubAgentExecutor agents = mock(SubAgentExecutor.class);
        ToolResult result = new TaskCreateTool(coordinator, agents, mock(ToolRegistry.class), mock(TaskShellExecutor.class))
                .call(ToolInput.from(Map.of("description", "test", "prompt", "test")), owner);
        assertEquals("TASK_CAPACITY_REACHED", result.failureCode());
        assertEquals(ToolResult.EffectState.NOT_STARTED, result.effectState());
        verifyNoInteractions(agents);
    }

    @Test void stopAcknowledgesAppliedRequestAndEventsReportExitConfirmation() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            TaskState state = coordinator.submit("stop", "owner", "test", () -> {
                ready.countDown(); awaitUninterruptibly(release);
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            ToolResult result = new TaskStopTool(coordinator).call(ToolInput.from(Map.of("taskId", "stop")), owner);
            assertFalse(result.isError());
            assertEquals(ToolResult.EffectState.APPLIED, result.effectState());
            assertEquals(true, result.metadata().get("cancellationRequested"));
            ToolResult repeatedPending = new TaskStopTool(coordinator).call(ToolInput.from(Map.of("taskId", "stop")), owner);
            assertFalse(repeatedPending.isError());
            assertEquals(TaskStatus.RUNNING, state.getStatus());
            verify(messaging, atLeastOnce()).convertAndSend(eq("/topic/session/owner"), (Object) argThat(value ->
                    value instanceof Map<?, ?> message && Boolean.TRUE.equals(message.get("cancellationRequested"))
                            && Boolean.FALSE.equals(message.get("terminationConfirmed"))));
            release.countDown(); await(state);
            ToolResult repeatedFinal = new TaskStopTool(coordinator).call(ToolInput.from(Map.of("taskId", "stop")), owner);
            assertFalse(repeatedFinal.isError());
            assertEquals(ToolResult.EffectState.NONE, repeatedFinal.effectState());
            assertEquals(true, repeatedFinal.metadata().get("terminationConfirmed"));
            assertEquals(TaskStatus.CANCELLED, state.getStatus());
            // Completion is recorded before delivery; explicitly wait for the final event assertion.
            verify(messaging, timeout(1000).atLeastOnce()).convertAndSend(eq("/topic/session/owner"), (Object) argThat(value ->
                    value instanceof Map<?, ?> message && "CANCELLED".equals(message.get("status"))
                            && Boolean.TRUE.equals(message.get("terminationConfirmed"))));
        } finally { release.countDown(); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void terminalOutputReportCannotRewriteFinalError(boolean failed) throws Exception {
        ManualExecutor executor = useManualExecutor();
        TaskState state = coordinator.submitResult("final", "owner", "test", () -> failed
                ? TaskExecutionResult.failed("final output", "final error") : TaskExecutionResult.completed("final output"));
        executor.runNext(); await(state);
        ToolResult report = new TaskOutputTool(coordinator).call(
                ToolInput.from(Map.of("output", "later note", "isError", true)), owner.withCurrentTaskId("final"));
        assertFalse(report.isError());
        assertTrue(report.content().contains("Final execution status and error were preserved"));
        assertEquals("later note", state.getOutput());
        assertEquals(failed ? "final error" : null, state.getError());
        assertEquals(failed ? TaskStatus.FAILED : TaskStatus.COMPLETED, state.getStatus());
        ToolResult stop = new TaskStopTool(coordinator).call(ToolInput.from(Map.of("taskId", "final")), owner);
        assertEquals("TASK_ALREADY_TERMINAL", stop.failureCode());
    }

    @Test
    void acceptedCancellationAfterAgentAdapterReturnRunsItsOwnedCleanup() throws Exception {
        SubAgentExecutor agents = mock(SubAgentExecutor.class);
        when(agents.executeTaskSync(any(), any())).thenAnswer(call -> {
            coordinator.cancelTask(coordinator.listTasks("owner", null).getFirst().getTaskId());
            return new SubAgentExecutor.AgentResult("completed", "late result", "prompt", null);
        });
        TaskState state = create("agent", agents, mock(TaskShellExecutor.class), mock(ToolRegistry.class));
        await(state);
        assertEquals(TaskStatus.CANCELLED, state.getStatus());
        verify(agents).finishTaskCancellation(
                argThat(request -> request.agentId().equals("task-" + state.getTaskId())), same(owner));
    }

    @Test void failedAgentIdentityDoesNotCleanAnotherExecution() throws Exception {
        SubAgentExecutor agents = mock(SubAgentExecutor.class);
        when(agents.executeTaskSync(any(), any())).thenAnswer(call -> {
            coordinator.cancelTask(coordinator.listTasks("owner", null).getFirst().getTaskId());
            throw new IllegalStateException("identity already owned");
        });
        TaskState state = create("agent", agents, mock(TaskShellExecutor.class), mock(ToolRegistry.class));
        await(state);
        verify(agents, never()).finishTaskCancellation(any(), any());
    }

    @Test void failedCleanupCannotPublishTerminalStateBeforeRetryConfirmsExit() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch retryEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            TaskState state = coordinator.submitResult("cleanup", "owner", "test", () -> {
                coordinator.registerCancellationCleanup("cleanup", () -> {
                    if (attempts.incrementAndGet() == 1) throw new IllegalStateException("not yet confirmed");
                    retryEntered.countDown(); awaitUninterruptibly(release);
                });
                ready.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                return TaskExecutionResult.completed("partial");
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            coordinator.cancelTask("cleanup");
            assertTrue(retryEntered.await(5, TimeUnit.SECONDS));
            assertEquals(TaskStatus.RUNNING, state.getStatus());
            assertFalse(state.getFuture().isDone());
            release.countDown(); await(state);
            assertEquals(2, attempts.get());
            assertEquals(TaskStatus.CANCELLED, state.getStatus());
        } finally { release.countDown(); }
    }

    private static final class ManualExecutor extends AbstractExecutorService {
        private final Queue<Runnable> queued = new ConcurrentLinkedQueue<>();
        private boolean closed;
        @Override public void execute(Runnable command) {
            if (closed) throw new RejectedExecutionException();
            queued.add(command);
        }
        void runNext() { Objects.requireNonNull(queued.poll()).run(); }
        @Override public void shutdown() { closed = true; }
        @Override public List<Runnable> shutdownNow() { closed = true; return List.copyOf(queued); }
        @Override public boolean isShutdown() { return closed; }
        @Override public boolean isTerminated() { return closed; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return closed; }
    }
}
