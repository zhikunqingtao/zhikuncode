package com.aicodeassistant.verify;

import com.aicodeassistant.service.PythonCapabilityAwareClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

@Component("httpApiVerifier")
public class HttpApiVerifier implements Verifier {
    private static final String CAPABILITY = "HTTP_API";
    private static final String HTTP_JOURNEY_ENDPOINT = "/api/http/journey/run";
    private static final Duration HTTP_JOURNEY_TIMEOUT = Duration.ofSeconds(60);
    private static final Logger log = LoggerFactory.getLogger(HttpApiVerifier.class);

    private final PythonCapabilityAwareClient pythonClient;
    private final SimpMessagingTemplate messagingTemplate;

    public HttpApiVerifier(PythonCapabilityAwareClient pythonClient,
                           SimpMessagingTemplate messagingTemplate) {
        this.pythonClient = pythonClient;
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public JourneyResult verify(JourneyRequest req, String principal) {
        if (!pythonClient.isCapabilityAvailable(CAPABILITY)) {
            return JourneyResult.unavailable("HTTP_API not available");
        }

        Map<String, Object> body = Map.of(
            "session_id", req.sessionId(),
            "base_url", req.baseUrl(),
            "steps", req.steps()
        );

        Optional<JourneyResponse> resp = pythonClient.callIfAvailable(
            CAPABILITY, HTTP_JOURNEY_ENDPOINT, body, JourneyResponse.class, HTTP_JOURNEY_TIMEOUT);

        if (resp.isEmpty()) {
            return JourneyResult.failed("PYTHON_CALL_FAILED", "Python HTTP API service unreachable or timeout");
        }

        JourneyResponse r = resp.get();

        // STOMP 逐步进度推送 — 与 BrowserVerifier 格式完全一致
        for (int i = 0; i < r.stepResults().size(); i++) {
            var step = r.stepResults().get(i);
            try {
                messagingTemplate.convertAndSendToUser(principal, "/queue/messages",
                    Map.of("type", "verify_progress",
                           "stepIndex", i,
                           "action", step.action(),
                           "ok", step.ok(),
                           "durationMs", step.durationMs()));
            } catch (Exception e) {
                log.warn("Failed to send verify_progress for step {}: {}", i, e.getMessage());
            }
        }

        return JourneyResult.from(r);
    }
}
