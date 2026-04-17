package com.aicodeassistant.session;

import com.aicodeassistant.model.Message;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 会话快照 — 完整的会话状态快照，用于服务重启后恢复会话。
 * <p>
 * 包含完整消息历史和元数据，序列化为 JSON 存储到 ~/.zhiku/snapshots/{sessionId}.json。
 *
 * @see SessionSnapshotService
 */
public record SessionSnapshot(
        String sessionId,
        List<Message> messages,
        String model,
        int turnCount,
        Instant createdAt,
        Map<String, Object> metadata
) {}
