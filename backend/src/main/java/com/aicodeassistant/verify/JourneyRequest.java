package com.aicodeassistant.verify;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record JourneyRequest(
    String sessionId,
    String baseUrl,
    List<Map<String, Object>> steps,
    Map<String, Object> recordOptions,
    String browserResourceId
) {
    /** Business session ownership is separate from this invocation's browser resource. */
    public JourneyRequest(String sessionId, String baseUrl, List<Map<String, Object>> steps,
                          Map<String, Object> recordOptions) {
        this(sessionId, baseUrl, steps, recordOptions, "rv-" + UUID.randomUUID());
    }
}
