package com.aicodeassistant.model;

/**
 * 单个模型的用量统计。
 *
 * @see <a href="SPEC §5.1.1">成本追踪状态</a>
 */
public record ModelUsage(
        int inputTokens,
        int outputTokens,
        int cacheReadTokens,
        int cacheCreationTokens,
        int apiCalls,
        double costUSD
) {}
