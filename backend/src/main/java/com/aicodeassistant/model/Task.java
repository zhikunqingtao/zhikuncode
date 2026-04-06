package com.aicodeassistant.model;

import java.time.Instant;

/**
 * 任务模型 — 代表一个可执行任务。
 *
 * @see <a href="SPEC §5.5">任务模型</a>
 */
public record Task(
        String id,
        String description,
        TaskType type,
        TaskStatus status,
        String output,
        String error,
        double progress,
        String sessionId,
        // v1.7.0 新增字段
        String outputFile,
        long outputOffset,
        boolean notified,
        // 时间戳
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt
) {}
