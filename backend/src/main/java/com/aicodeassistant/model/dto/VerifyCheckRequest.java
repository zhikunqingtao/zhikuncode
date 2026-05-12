package com.aicodeassistant.model.dto;

import java.util.List;

public record VerifyCheckRequest(
    String sessionId,
    List<String> filePaths,
    List<String> checks,           // ["typescript", "eslint", "vitest"]
    String workingDirectory,
    boolean vitestCoverage
) {
    public VerifyCheckRequest {
        if (checks == null || checks.isEmpty()) {
            checks = List.of("typescript", "eslint", "vitest");
        }
    }
}
