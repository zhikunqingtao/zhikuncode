package com.aicodeassistant.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 会话模型 — 一个完整的对话会话。
 *
 * @see <a href="SPEC §5.2">会话模型</a>
 */
public record Session(
        SessionId id,
        String title,
        String model,
        String workingDirectory,
        List<Message> messages,
        SessionStatus status,
        Usage totalUsage,
        double totalCostUsd,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt
) {}
