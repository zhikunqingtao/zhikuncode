package com.aicodeassistant.llm;

import org.springframework.stereotype.Component;

/**
 * 思考预算计算器 — 基于上一轮对话上下文指标动态计算本轮思考 token 预算。
 * <p>
 * 三因子模型:
 * <ol>
 *   <li>工具调用密度 — 工具调用越多，任务越复杂</li>
 *   <li>输出 token 量级 — 长输出意味着复杂推理</li>
 *   <li>对话轮次深度 — 多轮对话通常更复杂</li>
 * </ol>
 *
 * @see <a href="SPEC §10.1">自适应思考预算</a>
 */
@Component
public class ThinkingBudgetCalculator {

    // ==================== 预算常量 (对齐 SPEC §3.1.2) ====================
    private static final int BASE_BUDGET = 10_000;
    private static final int MIN_BUDGET = 2_000;    // 简单问答
    private static final int MAX_BUDGET = 50_000;   // 复杂多步推理

    // ==================== 三因子阈值 ====================
    // Factor 1: 工具调用密度
    private static final int TOOL_HIGH_THRESHOLD = 3;
    private static final int TOOL_LOW_THRESHOLD = 1;
    private static final double TOOL_HIGH_FACTOR = 1.5;
    private static final double TOOL_LOW_FACTOR = 1.2;

    // Factor 2: 输出 token 量级
    private static final int OUTPUT_HIGH_THRESHOLD = 2000;
    private static final int OUTPUT_LOW_THRESHOLD = 500;
    private static final double OUTPUT_HIGH_FACTOR = 1.3;
    private static final double OUTPUT_LOW_FACTOR = 1.1;

    // Factor 3: 对话轮次深度
    private static final int TURN_HIGH_THRESHOLD = 10;
    private static final int TURN_LOW_THRESHOLD = 5;
    private static final double TURN_HIGH_FACTOR = 1.2;
    private static final double TURN_LOW_FACTOR = 1.1;

    /**
     * 上一轮对话的上下文指标 — 用于计算本轮预算。
     * <p>
     * 首轮对话（无历史）使用默认值，所有 factor 均为 1.0。
     *
     * @param toolCallCount   上一轮 assistant 消息中 tool_use block 数量
     * @param lastOutputTokens 上一轮 assistant 消息的输出 token 数
     * @param turnCount       当前已完成的循环轮次数
     */
    public record ContextMetrics(
            int toolCallCount,
            int lastOutputTokens,
            int turnCount
    ) {
        /** 首轮默认指标 — 所有 factor = 1.0 */
        public static final ContextMetrics INITIAL = new ContextMetrics(0, 0, 0);
    }

    /**
     * 计算自适应思考预算。
     *
     * @param metrics 上一轮上下文指标
     * @return 思考 token 预算（已 clamp 到 [MIN_BUDGET, MAX_BUDGET]）
     */
    public int calculateBudget(ContextMetrics metrics) {
        double factor1 = computeToolFactor(metrics.toolCallCount());
        double factor2 = computeOutputFactor(metrics.lastOutputTokens());
        double factor3 = computeTurnFactor(metrics.turnCount());

        double multiplier = factor1 * factor2 * factor3;
        int budget = (int) (BASE_BUDGET * multiplier);

        return Math.max(MIN_BUDGET, Math.min(MAX_BUDGET, budget));
    }

    /**
     * Factor 1: 工具调用密度 — 工具调用越多，任务越复杂。
     */
    private double computeToolFactor(int toolCallCount) {
        if (toolCallCount >= TOOL_HIGH_THRESHOLD) return TOOL_HIGH_FACTOR;  // 1.5
        if (toolCallCount >= TOOL_LOW_THRESHOLD) return TOOL_LOW_FACTOR;    // 1.2
        return 1.0;
    }

    /**
     * Factor 2: 输出规模 — 长输出意味着复杂推理。
     */
    private double computeOutputFactor(int lastOutputTokens) {
        if (lastOutputTokens > OUTPUT_HIGH_THRESHOLD) return OUTPUT_HIGH_FACTOR; // 1.3
        if (lastOutputTokens > OUTPUT_LOW_THRESHOLD) return OUTPUT_LOW_FACTOR;   // 1.1
        return 1.0;
    }

    /**
     * Factor 3: 对话深度 — 多轮对话通常更复杂。
     */
    private double computeTurnFactor(int turnCount) {
        if (turnCount > TURN_HIGH_THRESHOLD) return TURN_HIGH_FACTOR; // 1.2
        if (turnCount > TURN_LOW_THRESHOLD) return TURN_LOW_FACTOR;   // 1.1
        return 1.0;
    }
}
