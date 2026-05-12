package com.aicodeassistant.model.dto;

import java.util.List;

public record VerifyCheckResponse(
    List<FileCheckResult> results,
    HeuristicAnalysis heuristic,
    String signal,                  // "auto_approve" | "review_recommended" | "manual_required" | "blocked"
    String signalReason,
    String overallStatus,           // "pass" | "partial" | "fail"
    long duration,
    String timestamp
) {}
