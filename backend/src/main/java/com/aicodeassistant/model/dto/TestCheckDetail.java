package com.aicodeassistant.model.dto;

import java.util.List;

public record TestCheckDetail(
    String status,                  // "pass" | "fail" | "skipped" | "no_tests"
    int passedCount,
    int failedCount,
    Double coveragePercent,
    List<TestFailure> failures
) {
    public static TestCheckDetail skipped() {
        return new TestCheckDetail("skipped", 0, 0, null, List.of());
    }

    public record TestFailure(String testName, String message) {}
}
