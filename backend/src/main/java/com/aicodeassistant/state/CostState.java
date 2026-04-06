package com.aicodeassistant.state;

import com.aicodeassistant.model.Usage;

import java.util.Map;

/**
 * 成本与用量状态 — Token 统计、成本追踪。
 *
 * @see <a href="SPEC §3.5.1">AppState 结构 - 成本与用量</a>
 */
public record CostState(
        Usage totalUsage,
        double totalCostUsd,
        double sessionCostUsd,
        Map<String, ModelCostBreakdown> costBreakdown
) {
    /**
     * 单模型成本明细。
     */
    public record ModelCostBreakdown(
            String modelId,
            int inputTokens,
            int outputTokens,
            int cacheReadTokens,
            int cacheCreateTokens,
            double costUsd
    ) {}

    public static CostState empty() {
        return new CostState(Usage.zero(), 0.0, 0.0, Map.of());
    }

    public CostState withTotalUsage(Usage usage) {
        return new CostState(usage, totalCostUsd, sessionCostUsd, costBreakdown);
    }

    public CostState withTotalCostUsd(double cost) {
        return new CostState(totalUsage, cost, sessionCostUsd, costBreakdown);
    }

    public CostState withSessionCostUsd(double cost) {
        return new CostState(totalUsage, totalCostUsd, cost, costBreakdown);
    }

    public CostState withCostBreakdown(Map<String, ModelCostBreakdown> breakdown) {
        return new CostState(totalUsage, totalCostUsd, sessionCostUsd, breakdown);
    }
}
