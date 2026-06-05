package com.aicodeassistant.verify;

import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.service.ActivityRepository;
import com.aicodeassistant.service.PythonCapabilityAwareClient;
import com.aicodeassistant.tool.verify.VerifyJourneyTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * VerifyJourneyTool 单元测试 — 覆盖 Feature Flag + 能力域双重门控的 isEnabled() 短路逻辑。
 */
class VerifyJourneyToolTest {

    private PythonCapabilityAwareClient pythonClient;
    private DevServerLauncher devServerLauncher;
    private VerifierFactory verifierFactory;
    private PreviewStackDetector previewStackDetector;
    private EvidenceStore evidenceStore;
    private SimpMessagingTemplate messagingTemplate;
    private FeatureFlagService featureFlags;
    private ActivityRepository activityRepository;
    private ObjectMapper objectMapper;

    private VerifyJourneyTool tool;

    @BeforeEach
    void setUp() {
        pythonClient = mock(PythonCapabilityAwareClient.class);
        devServerLauncher = mock(DevServerLauncher.class);
        verifierFactory = mock(VerifierFactory.class);
        previewStackDetector = mock(PreviewStackDetector.class);
        evidenceStore = mock(EvidenceStore.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        featureFlags = mock(FeatureFlagService.class);
        activityRepository = mock(ActivityRepository.class);
        objectMapper = new ObjectMapper();

        tool = new VerifyJourneyTool(
                pythonClient,
                devServerLauncher,
                verifierFactory,
                previewStackDetector,
                evidenceStore,
                messagingTemplate,
                featureFlags,
                activityRepository,
                objectMapper
        );
    }

    @Test
    @DisplayName("TC-VT-01 isEnabled - Feature Flag 关闭时直接短路返回 false，不查询能力域")
    void isEnabled_featureFlagDisabled_returnsFalse() {
        when(featureFlags.isEnabled("RUNTIME_VERIFICATION")).thenReturn(false);

        assertFalse(tool.isEnabled());

        verify(featureFlags).isEnabled("RUNTIME_VERIFICATION");
        verify(pythonClient, never()).isCapabilityAvailable(anyString());
    }

    @Test
    @DisplayName("TC-VT-02 isEnabled - Feature Flag 开启 + BROWSER_AUTOMATION 可用 → true")
    void isEnabled_flagOnAndBrowserAutomationAvailable_returnsTrue() {
        when(featureFlags.isEnabled("RUNTIME_VERIFICATION")).thenReturn(true);
        when(pythonClient.isCapabilityAvailable("BROWSER_AUTOMATION")).thenReturn(true);

        assertTrue(tool.isEnabled());

        verify(pythonClient).isCapabilityAvailable("BROWSER_AUTOMATION");
        // 因为 OR 短路，BROWSER_AUTOMATION 可用时不必查 HTTP_API
        verify(pythonClient, never()).isCapabilityAvailable("HTTP_API");
    }

    @Test
    @DisplayName("TC-VT-03 isEnabled - Feature Flag 开启 + 仅 HTTP_API 可用 → true（OR 逻辑）")
    void isEnabled_flagOnAndOnlyHttpApiAvailable_returnsTrue() {
        when(featureFlags.isEnabled("RUNTIME_VERIFICATION")).thenReturn(true);
        when(pythonClient.isCapabilityAvailable("BROWSER_AUTOMATION")).thenReturn(false);
        when(pythonClient.isCapabilityAvailable("HTTP_API")).thenReturn(true);

        assertTrue(tool.isEnabled());

        verify(pythonClient).isCapabilityAvailable("BROWSER_AUTOMATION");
        verify(pythonClient).isCapabilityAvailable("HTTP_API");
    }

    @Test
    @DisplayName("TC-VT-04 isEnabled - Feature Flag 开启但两个能力域都不可用 → false")
    void isEnabled_flagOnButNoCapabilityAvailable_returnsFalse() {
        when(featureFlags.isEnabled("RUNTIME_VERIFICATION")).thenReturn(true);
        when(pythonClient.isCapabilityAvailable("BROWSER_AUTOMATION")).thenReturn(false);
        when(pythonClient.isCapabilityAvailable("HTTP_API")).thenReturn(false);

        assertFalse(tool.isEnabled());

        verify(pythonClient).isCapabilityAvailable("BROWSER_AUTOMATION");
        verify(pythonClient).isCapabilityAvailable("HTTP_API");
    }

    @Test
    @DisplayName("TC-VT-05 getName - 返回工具注册名 \"VerifyJourney\"")
    void getName_returnsRegisteredToolName() {
        // 注：源码 VerifyJourneyTool#getName() 返回 "VerifyJourney"（驼峰），
        // 测试以源码实现为准（任务描述给出的 verify_journey 与源码不符）。
        assertEquals("VerifyJourney", tool.getName());

        // 顺带校验输入 schema 至少声明了 journey 必填字段，以避免后续误改破坏契约。
        Map<String, Object> schema = tool.getInputSchema();
        assertEquals("object", schema.get("type"));
        assertTrue(((java.util.List<?>) schema.get("required")).contains("journey"));
    }
}
