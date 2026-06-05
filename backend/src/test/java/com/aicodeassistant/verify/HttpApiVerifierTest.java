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
 * HttpApiVerifier 单元测试 — 验证 HTTP_API 能力域调用与 STOMP 进度推送。
 */
class HttpApiVerifierTest {

    private static final String CAPABILITY = "HTTP_API";
    private static final String ENDPOINT = "/api/http/journey/run";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private PythonCapabilityAwareClient pythonClient;
    private SimpMessagingTemplate messagingTemplate;
    private HttpApiVerifier verifier;

    @BeforeEach
    void setUp() {
        pythonClient = mock(PythonCapabilityAwareClient.class);
        messagingTemplate = mock(SimpMessagingTemplate.class);
        verifier = new HttpApiVerifier(pythonClient, messagingTemplate);
    }

    private JourneyRequest sampleRequest() {
        return new JourneyRequest(
            "sess-001",
            "http://localhost:8080",
            List.of(Map.of("action", "http_get", "url", "/api/health")),
            null
        );
    }

    private JourneyResponse passedResponse() {
        return new JourneyResponse(
            true,
            List.of(new JourneyResponse.JourneyStepResponse(
                0, "http_get", true, 42L, null, List.of(), null)),
            "sess-001",
            "http://localhost:8080/api/health",
            Map.of()
        );
    }

    @Test
    @DisplayName("TC-HA-01: HTTP_API 能力不可用 → verdict=unavailable")
    void verify_capabilityUnavailable_returnsUnavailable() {
        when(pythonClient.isCapabilityAvailable(CAPABILITY)).thenReturn(false);

        JourneyResult result = verifier.verify(sampleRequest(), "user1");

        assertEquals("unavailable", result.verdict());
        verify(pythonClient, never()).callIfAvailable(anyString(), anyString(), any(), any(), any());
        verifyNoInteractions(messagingTemplate);
    }

    @Test
    @DisplayName("TC-HA-02: 正常执行 + Python 返回 passed=true → verdict=verified")
    void verify_pythonReturnsPassed_returnsVerified() {
        when(pythonClient.isCapabilityAvailable(CAPABILITY)).thenReturn(true);
        when(pythonClient.callIfAvailable(eq(CAPABILITY), eq(ENDPOINT), any(),
                eq(JourneyResponse.class), eq(TIMEOUT)))
            .thenReturn(Optional.of(passedResponse()));

        JourneyResult result = verifier.verify(sampleRequest(), "user1");

        assertEquals("verified", result.verdict());
        assertNotNull(result.stepResults());
        assertEquals(1, result.stepResults().size());
        assertTrue(result.stepResults().get(0).ok());
    }

    @Test
    @DisplayName("TC-HA-03: Python 调用返回 Optional.empty → verdict=failed")
    void verify_pythonReturnsEmpty_returnsFailed() {
        when(pythonClient.isCapabilityAvailable(CAPABILITY)).thenReturn(true);
        when(pythonClient.callIfAvailable(eq(CAPABILITY), eq(ENDPOINT), any(),
                eq(JourneyResponse.class), eq(TIMEOUT)))
            .thenReturn(Optional.empty());

        JourneyResult result = verifier.verify(sampleRequest(), "user1");

        assertEquals("failed", result.verdict());
        assertNotNull(result.errorMessage());
        verifyNoInteractions(messagingTemplate);
    }

    @Test
    @DisplayName("TC-HA-04: callIfAvailable 传参正确 — endpoint/timeout=60s/body 含 session_id+base_url+steps")
    @SuppressWarnings("unchecked")
    void verify_callsPythonWithCorrectArgs() {
        when(pythonClient.isCapabilityAvailable(CAPABILITY)).thenReturn(true);
        when(pythonClient.callIfAvailable(anyString(), anyString(), any(),
                eq(JourneyResponse.class), any(Duration.class)))
            .thenReturn(Optional.of(passedResponse()));

        JourneyRequest req = sampleRequest();
        verifier.verify(req, "user1");

        ArgumentCaptor<String> capabilityCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> endpointCap = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> bodyCap = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Duration> timeoutCap = ArgumentCaptor.forClass(Duration.class);

        verify(pythonClient).callIfAvailable(
            capabilityCap.capture(),
            endpointCap.capture(),
            bodyCap.capture(),
            eq(JourneyResponse.class),
            timeoutCap.capture()
        );

        assertEquals(CAPABILITY, capabilityCap.getValue());
        assertEquals(ENDPOINT, endpointCap.getValue());
        assertEquals(Duration.ofSeconds(60), timeoutCap.getValue());

        Map<String, Object> body = (Map<String, Object>) bodyCap.getValue();
        assertEquals(req.sessionId(), body.get("session_id"),
            "HttpApiVerifier 不应给 session_id 加前缀（区别于 BrowserVerifier 的 rv- 前缀）");
        assertEquals(req.baseUrl(), body.get("base_url"));
        assertEquals(req.steps(), body.get("steps"));
    }

    @Test
    @DisplayName("TC-HA-05: STOMP 进度推送 — verify_progress 消息含 type/stepIndex/action/ok/durationMs")
    @SuppressWarnings("unchecked")
    void verify_pushesStompProgressForEachStep() {
        when(pythonClient.isCapabilityAvailable(CAPABILITY)).thenReturn(true);

        JourneyResponse resp = new JourneyResponse(
            true,
            List.of(
                new JourneyResponse.JourneyStepResponse(0, "http_get", true, 42L, null, List.of(), null),
                new JourneyResponse.JourneyStepResponse(1, "http_post", true, 87L, null, List.of(), null)
            ),
            "sess-001", "http://localhost:8080", Map.of()
        );
        when(pythonClient.callIfAvailable(eq(CAPABILITY), eq(ENDPOINT), any(),
                eq(JourneyResponse.class), eq(TIMEOUT)))
            .thenReturn(Optional.of(resp));

        verifier.verify(sampleRequest(), "user1");

        ArgumentCaptor<Object> payloadCap = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate, times(2))
            .convertAndSendToUser(eq("user1"), eq("/queue/messages"), payloadCap.capture());

        List<Object> payloads = payloadCap.getAllValues();

        Map<String, Object> first = (Map<String, Object>) payloads.get(0);
        assertEquals("verify_progress", first.get("type"));
        assertEquals(0, first.get("stepIndex"));
        assertEquals("http_get", first.get("action"));
        assertEquals(true, first.get("ok"));
        assertEquals(42L, first.get("durationMs"));

        Map<String, Object> second = (Map<String, Object>) payloads.get(1);
        assertEquals(1, second.get("stepIndex"));
        assertEquals("http_post", second.get("action"));
        assertEquals(87L, second.get("durationMs"));
    }
}
