package com.aicodeassistant.session;

import java.time.Instant;

/**
 * 会话快照摘要 — 列表展示用，不含消息内容。
 *
 * @see SessionSnapshotService#listSnapshots()
 */
public record SessionSnapshotSummary(
        String sessionId,
        String model,
        int turnCount,
        int messageCount,
        Instant createdAt
) {}
