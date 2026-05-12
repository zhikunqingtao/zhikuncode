package com.aicodeassistant.model.dto;

public record AbortResponse(
    String workerId,
    String status,          // "aborted"
    String message
) {}
