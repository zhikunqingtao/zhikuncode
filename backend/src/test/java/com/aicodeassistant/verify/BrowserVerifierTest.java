package com.aicodeassistant.verify;

import com.aicodeassistant.service.PythonCapabilityAwareClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * BrowserVerifier 单元测试 — 验证 BROWSER_AUTOMATION 能力域调用与 session_id 前缀策略。
 */
class BrowserVerifierTest {

    private static final String CAPABILITY = "BROWSER_AUTOMATION";
    private static final String ENDPOINT = "/api/browser/journey/run";
    private static final Duration TIMEOUT = Duration.ofSeconds(130);

    private PythonCapabilityAwareClient pythonClient;
    private SimpMessagingTemplate messagingTemplate;
    private BrowserVerifier verifier;

    @BeforeEach
    void setUp() {
        pythonClient = mock(PythonCapabilityAwareClient.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        verifier = new BrowserVerifier(pythonClient, messagingTemplate);
    }

    private JourneyRequest sampleRequest() {
        return new JourneyRequest(
            "abc123",
            "http://localhost:5173",
            List.of(Map.of("action", "click", "selector", "#login")),
            Map.of("video", false)
        );
    }

    private JourneyResponse passedResponse() {
        return new JourneyResponse(
            true,
            List.of(new JourneyResponse.JourneyStepResponse(
                0, "click", true, 120L, null, List.of(), null)),
            "rv-abc123",
            "http://localhost:5173/home",
            Map.of()
        );
    }

    @Test
    void verify_expectedRefusalPreservesItsCode() {
        when(pythonClient.isCapabilityAvailable(CAPABILITY)).thenReturn(true);
        when(pythonClient.callJourneyIfAvailable(eq(CAPABILITY), eq(ENDPOINT), any(),
                eq(JourneyResponse.class), eq(TIMEOUT)))
                .thenThrow(new PythonCapabilityAwareClient.JourneyCallException("BROWSER_CAPACITY_REACHED"));
        JourneyResult result = verifier.verify(sampleRequest(), "user1");
        assertEquals("failed", result.verdict());
        assertEquals("BROWSER_CAPACITY_REACHED", result.errorMessage());
        verifyNoInteractions(messagingTemplate);
    }

    @Test
    @DisplayName("TC-BV-01: BROWSER_AUTOMATION 能力不可用 → verdict=unavailable")
    void verify_capabilityUnavailable_returnsUnavailable() {
        when(pythonClient.isCapabilityAvailable(CAPABILITY)).thenReturn(false);

        JourneyResult result = verifier.verify(sampleRequest(), "user1");

        assertEquals("unavailable", result.verdict());
        verify(pythonClient, never()).callJourneyIfAvailable(anyString(), anyString(), any(), any(), any());
        verifyNoInteractions(messagingTemplate);
    }

    @Test
    @DisplayName("TC-BV-02: 正常执行 + Python 返回 passed=true → verdict=verified")
    void verify_pythonReturnsPassed_returnsVerified() {
        when(pythonClient.isCapabilityAvailable(CAPABILITY)).thenReturn(true);
        when(pythonClient.callJourneyIfAvailable(eq(CAPABILITY), eq(ENDPOINT), any(),
                eq(JourneyResponse.class), eq(TIMEOUT)))
            .thenReturn(Optional.of(passedResponse()));

        JourneyResult result = verifier.verify(sampleRequest(), "user1");

        assertEquals("verified", result.verdict());
        assertEquals(1, result.stepResults().size());
        assertTrue(result.stepResults().get(0).ok());
    }

    @Test
    @DisplayName("TC-BV-03: Python 调用返回 Optional.empty → verdict=failed")
    void verify_pythonReturnsEmpty_returnsFailed() {
        when(pythonClient.isCapabilityAvailable(CAPABILITY)).thenReturn(true);
        when(pythonClient.callJourneyIfAvailable(eq(CAPABILITY), eq(ENDPOINT), any(),
                eq(JourneyResponse.class), eq(TIMEOUT)))
            .thenReturn(Optional.empty());

        JourneyResult result = verifier.verify(sampleRequest(), "user1");

        assertEquals("failed", result.verdict());
        assertNotNull(result.errorMessage());
        verifyNoInteractions(messagingTemplate);
    }

    @Test
    @DisplayName("TC-BV-04: browser resource ID is invocation-owned, not the business session ID")
    @SuppressWarnings("unchecked")
    void verify_bodyHasRvPrefixAndViewportRecord() {
        when(pythonClient.isCapabilityAvailable(CAPABILITY)).thenReturn(true);
        when(pythonClient.callJourneyIfAvailable(anyString(), anyString(), any(),
                eq(JourneyResponse.class), any(Duration.class)))
            .thenReturn(Optional.of(passedResponse()));

        JourneyRequest req = sampleRequest();
        long before = System.currentTimeMillis();
        verifier.verify(req, "user1");
        long after = System.currentTimeMillis();

        ArgumentCaptor<Object> bodyCap = ArgumentCaptor.forClass(Object.class);
        verify(pythonClient).callJourneyIfAvailable(
            eq(CAPABILITY), eq(ENDPOINT), bodyCap.capture(),
            eq(JourneyResponse.class), any(Duration.class));

        Map<String, Object> body = (Map<String, Object>) bodyCap.getValue();
        assertEquals(req.browserResourceId(), body.get("session_id"));
        assertTrue(req.browserResourceId().startsWith("rv-"));
        assertNotEquals("rv-" + req.sessionId(), body.get("session_id"));
        assertNotEquals(req.browserResourceId(), sampleRequest().browserResourceId());
        assertEquals("abc123", req.sessionId(), "Business session ownership must not change");
        long deadline = ((Number) body.get("deadline_epoch_ms")).longValue();
        assertTrue(deadline >= before + 120_000 && deadline <= after + 120_000,
                "Preserve 120s work budget without depending on test scheduling speed");
        assertEquals(req.baseUrl(), body.get("base_url"));
        assertEquals(req.steps(), body.get("steps"));
        assertEquals(req.recordOptions(), body.get("record"));

        Map<String, Object> viewport = (Map<String, Object>) body.get("viewport");
        assertNotNull(viewport, "body 必须包含 viewport");
        assertEquals(1280, viewport.get("width"));
        assertEquals(800, viewport.get("height"));
    }

    @Test
    @DisplayName("TC-BV-05: 120s execution plus 10s bounded cleanup/transport headroom")
    void verify_reservesCleanupHeadroomInHttpTimeout() {
        when(pythonClient.isCapabilityAvailable(CAPABILITY)).thenReturn(true);
        when(pythonClient.callJourneyIfAvailable(anyString(), anyString(), any(),
                eq(JourneyResponse.class), any(Duration.class)))
            .thenReturn(Optional.of(passedResponse()));

        verifier.verify(sampleRequest(), "user1");

        ArgumentCaptor<Duration> timeoutCap = ArgumentCaptor.forClass(Duration.class);
        verify(pythonClient).callJourneyIfAvailable(
            eq(CAPABILITY), eq(ENDPOINT), any(),
            eq(JourneyResponse.class), timeoutCap.capture());

        assertEquals(Duration.ofSeconds(130), timeoutCap.getValue());
        assertNotEquals(Duration.ofSeconds(60), timeoutCap.getValue());
    }
}
