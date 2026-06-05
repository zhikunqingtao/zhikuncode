package com.aicodeassistant.verify;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record JourneyResult(
    String verdict,         // "verified" | "failed" | "unavailable"
    String errorMessage,
    List<StepResult> stepResults,
    Map<String, String> artifacts
) {
    public static JourneyResult unavailable(String reason) {
        return new JourneyResult("unavailable", reason, List.of(), Map.of());
    }

    public static JourneyResult failed(String code, String message) {
        return new JourneyResult("failed", message, List.of(), Map.of());
    }

    public static JourneyResult from(JourneyResponse r) {
        String verdict = r.passed() ? "verified" : "failed";
        String error = null;
        if (!r.passed()) {
            var failedStep = r.stepResults().stream()
                .filter(s -> !s.ok()).findFirst().orElse(null);
            if (failedStep != null) {
                error = String.format("Step %d [%s] failed: %s. Console errors: %s. Page URL: %s",
                    failedStep.index(), failedStep.action(), failedStep.error(),
                    failedStep.consoleErrors(), r.finalUrl());
            }
        }
        List<StepResult> stepResults = r.stepResults().stream()
            .map(s -> new StepResult(s.index(), s.action(), s.ok(), s.durationMs(),
                s.error(), s.consoleErrors(), s.screenshotBase64()))
            .collect(Collectors.toList());
        return new JourneyResult(verdict, error, stepResults, r.artifacts() != null ? r.artifacts() : Map.of());
    }
}
