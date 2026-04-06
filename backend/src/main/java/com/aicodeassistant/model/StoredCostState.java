package com.aicodeassistant.model;

import java.util.Map;

/**
 * 持久化成本状态 — 跨会话存储的成本和用量统计。
 * 对应源码: src/cost-tracker.ts StoredCostState
 *
 * @see <a href="SPEC §5.1.1">成本追踪状态</a>
 */
public record StoredCostState(
        double totalCostUSD,
        long totalAPIDuration,
        long totalAPIDurationWithoutRetries,
        long totalToolDuration,
        int totalLinesAdded,
        int totalLinesRemoved,
        Long lastDuration,
        Map<String, ModelUsage> modelUsage
) {}
