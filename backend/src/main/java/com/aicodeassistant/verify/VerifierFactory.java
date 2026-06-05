package com.aicodeassistant.verify;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class VerifierFactory {
    private final BrowserVerifier browserVerifier;
    private final HttpApiVerifier httpApiVerifier;

    @Autowired
    public VerifierFactory(
            @Qualifier("browserVerifier") BrowserVerifier browserVerifier,
            @Qualifier("httpApiVerifier") HttpApiVerifier httpApiVerifier) {
        this.browserVerifier = browserVerifier;
        this.httpApiVerifier = httpApiVerifier;
    }

    public Verifier selectVerifier(JourneyRequest req, String verificationMode) {
        if ("http_api".equals(verificationMode)) {
            return httpApiVerifier;
        } else if ("browser".equals(verificationMode)) {
            return browserVerifier;
        } else {
            // 自动检测：若 steps 中存在 http_ 前缀 action 则使用 HTTP API 验证器
            boolean hasHttpActions = req.steps().stream()
                .anyMatch(step -> {
                    String action = (String) step.get("action");
                    return action != null && action.startsWith("http_");
                });
            return hasHttpActions ? httpApiVerifier : browserVerifier;
        }
    }
}
