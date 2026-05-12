package com.aicodeassistant.model.dto;

public record CheckIssue(
    int line,
    int column,
    String rule,
    String severity,
    String message,
    String code
) {}
