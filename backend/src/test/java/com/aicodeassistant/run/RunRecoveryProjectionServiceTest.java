package com.aicodeassistant.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RunRecoveryProjectionServiceTest {

    @Test
    void correlatesV2TopLevelToolUseIdAndLegacyNestedId() {
        RunEnvelopeRepository runs = mock(RunEnvelopeRepository.class);
        RunEventRepository events = mock(RunEventRepository.class);
        RunEnvelope run = RunEnvelope.start("session", null, "root", "model");
        when(runs.findBySession("session", 1)).thenReturn(List.of(run));
        when(events.getMaxSeq(run.id())).thenReturn(3);
        when(events.getEvents(run.id(), 0, 500)).thenReturn(List.of(
                event(run.id(), 1, "tool_started",
                        "{\"schemaVersion\":2,\"toolUseId\":\"top-id\",\"data\":{\"toolName\":\"Bash\"}}"),
                event(run.id(), 2, "tool_started",
                        "{\"schemaVersion\":1,\"data\":{\"toolUseId\":\"legacy-id\",\"toolName\":\"Read\"}}"),
                event(run.id(), 3, "tool_finished",
                        "{\"schemaVersion\":2,\"toolUseId\":\"top-id\",\"data\":{\"toolName\":\"Bash\",\"durationMs\":1500,\"isError\":false,\"executionStatus\":\"succeeded\",\"outputPreview\":\"done\"}}")));

        var projection = new RunRecoveryProjectionService(
                runs, events, new ObjectMapper()).latestForSession("session");

        assertThat(projection.snapshotEventSeq()).isEqualTo(3);
        assertThat(projection.activeToolCalls()).containsExactly(
                java.util.Map.of(
                        "toolUseId", "legacy-id",
                        "toolName", "Read",
                        "input", java.util.Map.of(),
                        "startedAt", 2L));
        assertThat(projection.settledToolResults()).containsExactly(
                java.util.Map.of(
                        "type", "tool_result",
                        "toolUseId", "top-id",
                        "content", "done",
                        "isError", false,
                        "metadata", java.util.Map.of(
                                "executionStatus", "succeeded",
                                "outputTruncated", false,
                                "durationMs", 1500L)));
    }

    private static RunEvent event(
            String runId, int seq, String type, String data) {
        return new RunEvent((long) seq, runId, seq, type, data, seq);
    }
}
