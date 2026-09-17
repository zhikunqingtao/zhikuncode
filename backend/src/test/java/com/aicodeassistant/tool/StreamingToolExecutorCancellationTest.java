package com.aicodeassistant.tool;

import com.aicodeassistant.tool.process.ManagedProcessRunner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StreamingToolExecutorCancellationTest {
    @Test
    void snapshotIncludesCompletedResultsBehindPendingToolWithoutConsumingThem() throws Exception {
        var pipeline = mock(ToolExecutionPipeline.class);
        var executor = new StreamingToolExecutor(pipeline, new SimpleMeterRegistry(), mock(ManagedProcessRunner.class));
        var tool = mock(Tool.class);
        when(tool.getName()).thenReturn("ConcurrentTool");
        when(tool.getMaxExecutionTimeMs()).thenReturn(60_000L);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(pipeline.execute(eq(tool), any(), any(), any())).thenAnswer(inv -> {
            started.countDown();
            release.await();
            return ToolExecutionResult.of(ToolResult.success("first result"));
        });
        var context = ToolUseContext.of("/tmp", "snapshot-session");
        var session = executor.newSession(context);
        try {
            session.addTool(tool, ToolInput.from(Map.of()), "pending", context);
            assertTrue(started.await(2, TimeUnit.SECONDS));
            session.addErrorResult("done", "completed failure");
            assertTrue(session.yieldCompleted().isEmpty());
            var snapshot = session.completedResultsSnapshot();
            assertEquals(java.util.Set.of("done"), snapshot.keySet());
            assertTrue(snapshot.get("done").isError());
            assertTrue(snapshot.get("done").content().contains("completed failure"));
            assertEquals(snapshot, session.completedResultsSnapshot());
            assertTrue(session.yieldCompleted().isEmpty());
        } finally {
            release.countDown();
            session.discard();
        }
    }

    @Test
    void cancelRunInterruptsAndConfirmsRunningNonProcessTool() throws Exception {
        ToolExecutionPipeline pipeline = mock(ToolExecutionPipeline.class);
        ManagedProcessRunner processes = mock(ManagedProcessRunner.class);
        StreamingToolExecutor executor = new StreamingToolExecutor(
                pipeline, new SimpleMeterRegistry(), processes);
        Tool tool = mock(Tool.class);
        when(tool.getName()).thenReturn("SlowTool");
        when(tool.getMaxExecutionTimeMs()).thenReturn(60_000L);
        when(tool.isConcurrencySafe(any())).thenReturn(false);
        CountDownLatch started = new CountDownLatch(1);
        when(pipeline.execute(eq(tool), any(), any(), any())).thenAnswer(invocation -> {
            started.countDown();
            Thread.sleep(30_000);
            return ToolExecutionResult.of(ToolResult.success("late"));
        });
        ToolUseContext context = ToolUseContext.of("/tmp", "session").withCurrentRunId("run");
        StreamingToolExecutor.ExecutionSession session = executor.newSession(context);
        session.addTool(tool, ToolInput.from(Map.of()), "tool-use", context);
        assertTrue(started.await(1, TimeUnit.SECONDS));

        StreamingToolExecutor.ToolCancelSummary result = executor.cancelRunDetailed("run");

        assertEquals(1, result.foundSessions());
        assertTrue(result.allTerminated());
        assertFalse(session.hasUnfinishedTools());
    }
}
