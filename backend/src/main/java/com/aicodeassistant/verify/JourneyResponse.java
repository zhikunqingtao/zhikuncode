package com.aicodeassistant.verify;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

public record JourneyResponse(
    boolean passed,
    @JsonProperty("step_results") List<JourneyStepResponse> stepResults,
    @JsonProperty("session_id") String sessionId,
    @JsonProperty("final_url") String finalUrl,
    Map<String, String> artifacts
) {
    public record JourneyStepResponse(
        int index,
        String action,
        boolean ok,
        @JsonProperty("duration_ms") long durationMs,
        String error,
        @JsonProperty("console_errors") List<String> consoleErrors,
        @JsonProperty("screenshot_base64") String screenshotBase64,
        String method,
        String warning,
        @JsonProperty("screenshot_error") String screenshotError
    ) {
        public JourneyStepResponse(int index, String action, boolean ok, long durationMs, String error,
                                   List<String> consoleErrors, String screenshotBase64) {
            this(index, action, ok, durationMs, error, consoleErrors, screenshotBase64, null, null, null);
        }
    }
}
