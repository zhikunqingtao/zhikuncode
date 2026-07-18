package com.aicodeassistant.tool;

import com.aicodeassistant.engine.AbortContext;
import com.aicodeassistant.run.RunExecutionRegistry;
import com.aicodeassistant.tool.process.ManagedProcessRunner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class StreamingToolExecutorLeaseTest {

    @Test
    void emptyTurnDoesNotAcquireRunWorkLease() {
        RunExecutionRegistry runs = new RunExecutionRegistry();
        runs.register("run", "session", new AbortContext());
        StreamingToolExecutor executor = new StreamingToolExecutor(
                mock(ToolExecutionPipeline.class), new SimpleMeterRegistry(),
                mock(ManagedProcessRunner.class), runs);

        executor.newSession(ToolUseContext.of("/workspace", "session").withCurrentRunId("run"));

        assertThat(runs.beginCompletion("run")).isTrue();
        assertThat(runs.awaitQuiescence("run", java.time.Duration.ZERO)).isTrue();
    }
}
