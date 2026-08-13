package com.aicodeassistant.model;

import java.time.Instant;

/**
 * 会话摘要 — 用于列表展示的轻量级会话信息。
 *
 */
public record SessionSummary(
        String id,
        String title,
        String goalPreview,
        String model,
        String workingDirectory,
        int messageCount,
        double costUsd,
        Instant createdAt,
        Instant updatedAt
) {}
