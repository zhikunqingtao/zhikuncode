package com.aicodeassistant.model;

import java.time.Instant;
import java.util.List;

/**
 * 确定性验证响应。
 */
public record RunChecksResponse(
    String operationId,
    String status,              // "all_pass" | "has_warning" | "has_error"
    List<CheckResult> results,
    long totalDuration,
    String timestamp,
    // Signal 计算结果
    String signal,              // "auto_approve" | "review_recommended" | "manual_required" | "blocked"
    String signalReason
) {
    public record CheckResult(
        String check,
        boolean passed,
        List<CheckIssue> errors,
        List<CheckIssue> warnings,
        long duration
    ) {}

    public record CheckIssue(
        String file,
        int line,
        int column,
        String message,
        String rule
    ) {}

    public static RunChecksResponse create(String operationId, List<CheckResult> results, String signal, String signalReason) {
        String status = results.stream().anyMatch(r -> !r.passed()) ? "has_error"
                       : results.stream().anyMatch(r -> r.warnings() != null && !r.warnings().isEmpty()) ? "has_warning"
                       : "all_pass";
        long totalDuration = results.stream().mapToLong(CheckResult::duration).sum();
        return new RunChecksResponse(operationId, status, results, totalDuration, Instant.now().toString(), signal, signalReason);
    }
}
