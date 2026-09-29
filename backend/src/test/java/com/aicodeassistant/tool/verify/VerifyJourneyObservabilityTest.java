package com.aicodeassistant.tool.verify;

import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.config.database.SqliteConfig;
import com.aicodeassistant.notify.NotificationService;
import com.aicodeassistant.observability.BestEffortObservabilityRecorder;
import com.aicodeassistant.service.ActivityRepository;
import com.aicodeassistant.service.PythonCapabilityAwareClient;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import com.aicodeassistant.verify.BrowserVerifier;
import com.aicodeassistant.verify.DevServerHandle;
import com.aicodeassistant.verify.DevServerLauncher;
import com.aicodeassistant.verify.EvidenceStore;
import com.aicodeassistant.verify.JourneyResult;
import com.aicodeassistant.verify.PreviewStackDetector;
import com.aicodeassistant.verify.StackInfo;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Test
    void evidencePersistFailureIsObservedAsCompletedWithoutFailedVerdict() {
        PythonCapabilityAwareClient pythonClient = mock(PythonCapabilityAwareClient.class);
        DevServerLauncher devServerLauncher = mock(DevServerLauncher.class);
        VerifierFactory verifierFactory = mock(VerifierFactory.class);
        PreviewStackDetector previewStackDetector = mock(PreviewStackDetector.class);
        EvidenceStore evidenceStore = mock(EvidenceStore.class);
        when(pythonClient.isCapabilityAvailable("BROWSER_AUTOMATION")).thenReturn(true);
        when(previewStackDetector.detect(any()))
                .thenReturn(new StackInfo("vite", 5173, "npm run dev"));
        BrowserVerifier browser = mock(BrowserVerifier.class);
        when(verifierFactory.selectVerifier(any(), anyString())).thenReturn(browser);
        when(browser.verify(any(), anyString()))
                .thenReturn(new JourneyResult("verified", null, List.of(), Map.of()));
        when(devServerLauncher.start(any(), anyString(), anyInt(), any())).thenReturn(
                new DevServerHandle(mock(Process.class), 99999L, 5173,
                        workspace.resolve("devserver.log"), workspace.resolve("devserver.pid")));
        when(evidenceStore.save(any()))
                .thenThrow(new SqliteConfig.DatabaseWriteUnavailableException("AUTHORIZATION_STORE_BUSY"));

        VerifyJourneyTool tool = new VerifyJourneyTool(
                pythonClient, devServerLauncher, verifierFactory, previewStackDetector,
                evidenceStore, mock(SimpMessagingTemplate.class), mock(FeatureFlagService.class),
                mock(ActivityRepository.class), new ObjectMapper(), mock(NotificationService.class));

        BestEffortObservabilityRecorder recorder = mock(BestEffortObservabilityRecorder.class);
        List<String> eventTypes = new ArrayList<>();
        List<Map<String, Object>> eventData = new ArrayList<>();
        doAnswer(invocation -> {
            eventTypes.add(invocation.getArgument(1));
            eventData.add(invocation.getArgument(3));
            return true;
        }).when(recorder).record(any(), any(), any(), any());
        tool.setObservabilityRecorder(recorder);

        ToolResult result = tool.call(
                ToolInput.from(Map.of(
                        "journey", List.of(Map.of("action", "navigate", "url", "/")),
                        "verification_mode", "auto")),
                ToolUseContext.of(workspace.toString(), "session-persist").withCurrentRunId("run-persist"));

        assertThat(result.failureCode()).isEqualTo("EVIDENCE_PERSIST_FAILED");
        assertThat(result.content()).contains("verdict 'verified'");
        assertThat(result.content()).doesNotContain("AUTHORIZATION_STORE_BUSY");
        assertThat(eventTypes).containsExactly(
                "runtime_verification_started", "runtime_verification_completed");
        Map<String, Object> completed = eventData.get(1);
        assertThat(completed).containsEntry("mode", "browser");
        assertThat(completed).containsEntry("verdict", "verified");
        assertThat(completed).containsEntry("persisted", false);
        assertThat(completed).containsEntry("errorType", "EVIDENCE_PERSIST_FAILED");
        assertThat(eventData).noneMatch(data -> "failed".equals(data.get("verdict")));
    }
}
