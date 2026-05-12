package com.aicodeassistant.model.dto;

import java.util.List;

public record CheckDetail(
    String status,                  // "pass" | "fail" | "skipped"
    int errorCount,
    int warningCount,
    List<CheckIssue> issues
) {
    public static CheckDetail skipped() {
        return new CheckDetail("skipped", 0, 0, List.of());
    }
}
