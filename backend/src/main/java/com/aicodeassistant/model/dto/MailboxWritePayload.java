package com.aicodeassistant.model.dto;

public record MailboxWritePayload(
    String from,
    String to,
    long messageSize,
    String contentType,     // "task_spec" | "code_diff" | "review_result" | "test_report"
    long timestamp
) {}
