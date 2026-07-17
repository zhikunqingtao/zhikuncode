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
