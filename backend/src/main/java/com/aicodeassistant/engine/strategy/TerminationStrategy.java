package com.aicodeassistant.engine.strategy;

import java.time.Instant;
import java.util.List;

/**
 * 终止策略接口 — 定义 Agent Loop 的终止评估契约。
 * <p>
 * 实现类根据当前循环上下文决定是否继续、终止或切换策略。
 */
public interface TerminationStrategy {

    /**
     * 评估当前循环状态，返回终止决策。
     *
     * @param context 当前循环上下文
     * @return 终止决策
     */
    TerminationDecision evaluate(LoopContext context);

    // ==================== Agent Loop 上下文 ====================

    /**
     * Agent Loop 上下文 — 封装终止评估所需的所有状态信息。
     *
     * @param currentTurn            当前轮次
     * @param maxTurns               最大轮次限制
     * @param consecutiveErrors      连续错误计数
     * @param toolCallsThisTurn      本轮工具调用数
     * @param lastResponseHasToolCalls 上一次响应是否包含工具调用
     * @param stopReason             API 返回的停止原因
     * @param totalTokensUsed        已使用的总 token 数
     * @param tokenBudget            token 预算上限
     * @param recentResults          最近的工具调用记录
     */
    record LoopContext(
            int currentTurn,
            int maxTurns,
            int consecutiveErrors,
            int toolCallsThisTurn,
            boolean lastResponseHasToolCalls,
            String stopReason,
            long totalTokensUsed,
            long tokenBudget,
            List<ToolCallRecord> recentResults
    ) {}

    // ==================== 工具调用记录 ====================

    /**
     * 工具调用记录 — 追踪单次工具执行的结果。
     *
     * @param toolName     工具名称
     * @param success      是否成功
     * @param errorMessage 错误信息（成功时为 null）
     * @param timestamp    执行时间戳
     */
    record ToolCallRecord(
            String toolName,
            boolean success,
            String errorMessage,
            Instant timestamp
    ) {}
}
