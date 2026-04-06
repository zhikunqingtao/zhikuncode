package com.aicodeassistant.session;

import com.aicodeassistant.model.Message;
import com.aicodeassistant.model.Usage;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 完整会话数据 — 包含消息历史和元数据。
 * 用于会话加载/保存的完整数据传输对象。
 *
 * @see <a href="SPEC §3.6">会话持久化</a>
 */
public record SessionData(
        String sessionId,
        String model,
        String workingDir,
        String title,
        String status,
        List<Message> messages,
        Map<String, Object> config,
        Usage totalUsage,
        double totalCostUsd,
        String summary,
        Instant createdAt,
        Instant updatedAt
) {}
