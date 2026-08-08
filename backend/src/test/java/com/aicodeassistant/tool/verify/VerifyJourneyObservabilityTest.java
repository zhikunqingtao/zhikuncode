package com.aicodeassistant.tool.verify;

import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.notify.NotificationService;
import com.aicodeassistant.observability.BestEffortObservabilityRecorder;
import com.aicodeassistant.service.ActivityRepository;
import com.aicodeassistant.service.PythonCapabilityAwareClient;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import com.aicodeassistant.verify.DevServerLauncher;
import com.aicodeassistant.verify.EvidenceStore;
import com.aicodeassistant.verify.PreviewStackDetector;
import com.aicodeassistant.verify.VerifierFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class VerifyJourneyObservabilityTest {

    @TempDir Path workspace;

    @Test
    void invalidInputStillReturnsNormallyWhenTerminalObservationFails() {
        VerifyJourneyTool tool = new VerifyJourneyTool(
                mock(PythonCapabilityAwareClient.class),
                mock(DevServerLauncher.class),
                mock(VerifierFactory.class),
                mock(PreviewStackDetector.class),
                mock(EvidenceStore.class),
                mock(SimpMessagingTemplate.class),
                mock(FeatureFlagService.class),
                mock(ActivityRepository.class),
                new ObjectMapper(),
                mock(NotificationService.class));
        BestEffortObservabilityRecorder recorder = mock(BestEffortObservabilityRecorder.class);
        List<String> attemptedEvents = new ArrayList<>();
        doAnswer(invocation -> {
            String eventType = invocation.getArgument(1);
            attemptedEvents.add(eventType);
            if ("runtime_verification_completed".equals(eventType)) {
                throw new IllegalStateException("observation unavailable");
            }
            return true;
        }).when(recorder).record(any(), any(), any(), any());
        tool.setObservabilityRecorder(recorder);

        ToolResult result = tool.call(
                ToolInput.from(Map.of("journey", List.of())),
                ToolUseContext.of(workspace.toString(), "session-1").withCurrentRunId("run-1"));

        assertThat(result.isError()).isTrue();
        assertThat(result.failureCode()).isEqualTo("VERIFY_JOURNEY_EMPTY");
        assertThat(attemptedEvents).containsExactly(
                "runtime_verification_started", "runtime_verification_completed");
    }
}
