package com.aicodeassistant.verify;

import com.aicodeassistant.service.PythonCapabilityAwareClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * 浏览器自动化验证器 — 通过 Python BROWSER_AUTOMATION 能力执行 journey 验证。
 */
@Component("browserVerifier")
public class BrowserVerifier implements Verifier {
    private static final String CAPABILITY = "BROWSER_AUTOMATION";
    private static final String JOURNEY_ENDPOINT = "/api/browser/journey/run";
    private static final Duration JOURNEY_TIMEOUT = Duration.ofSeconds(120);
    private static final Logger log = LoggerFactory.getLogger(BrowserVerifier.class);

    private final PythonCapabilityAwareClient pythonClient;
    private final SimpMessagingTemplate messagingTemplate;

    public BrowserVerifier(PythonCapabilityAwareClient pythonClient,
                           SimpMessagingTemplate messagingTemplate) {
        this.pythonClient = pythonClient;
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public JourneyResult verify(JourneyRequest req, String principal) {
        if (!pythonClient.isCapabilityAvailable(CAPABILITY)) {
            return JourneyResult.unavailable("BROWSER_AUTOMATION not available");
        }

        Map<String, Object> body = Map.of(
            "session_id", "rv-" + req.sessionId(),
            "base_url", req.baseUrl(),
            "steps", req.steps(),
            "record", req.recordOptions(),
            "viewport", Map.of("width", 1280, "height", 800)
        );

        // 使用带超时的重载（120s — journey 涉及多步浏览器操作）
        Optional<JourneyResponse> resp = pythonClient.callIfAvailable(
            CAPABILITY, JOURNEY_ENDPOINT, body, JourneyResponse.class, JOURNEY_TIMEOUT);

        if (resp.isEmpty()) {
            return JourneyResult.failed("PYTHON_CALL_FAILED", "Python service unreachable or timeout");
        }

        JourneyResponse r = resp.get();

        // 逐步推送 STOMP 进度（复用已有 verify_progress 消息类型）
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
