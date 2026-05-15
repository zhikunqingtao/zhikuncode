package com.aicodeassistant.engine.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 默认终止策略实现 — 基于多维度评估决定 Agent Loop 是否应终止。
 * <p>
 * 评估优先级：
 * 1. Token 预算检查（> 95% 触发）
 * 2. 连续错误检查（≥ 3 次切换策略）
 * 3. 正常结束判断（stopReason + 无工具调用）
 * 4. 动态 maxTurns（base + consecutiveErrors * 2）
 * 5. 滑动窗口检查（最近 5 次全失败 → 请求用户输入）
 */
@Component
public class DefaultTerminationStrategy implements TerminationStrategy {

    private static final Logger log = LoggerFactory.getLogger(DefaultTerminationStrategy.class);

    /** Token 预算警告阈值 */
    private static final double TOKEN_BUDGET_THRESHOLD = 0.95;

    /** 连续错误阈值 — 切换恢复策略 */
    private static final int CONSECUTIVE_ERROR_THRESHOLD = 3;

    /** maxTurns 弹性系数 — 每次连续错误增加的额外轮次 */
    private static final int ERROR_TURN_EXTENSION = 2;

    /** 滑动窗口大小 — 最近 N 次工具调用 */
    private static final int SLIDING_WINDOW_SIZE = 5;

    @Override
    public TerminationDecision evaluate(LoopContext context) {
        // 1. Token 预算检查：> 95% → TERMINATE_BUDGET
        if (context.tokenBudget() > 0
                && context.totalTokensUsed() > context.tokenBudget() * TOKEN_BUDGET_THRESHOLD) {
            log.warn("Token budget threshold reached: used={}, budget={}, threshold={}%",
                    context.totalTokensUsed(), context.tokenBudget(),
                    (int) (TOKEN_BUDGET_THRESHOLD * 100));
            return TerminationDecision.TERMINATE_BUDGET;
        }

        // 2. 连续错误检查：≥ 3 → SWITCH_STRATEGY
        if (context.consecutiveErrors() >= CONSECUTIVE_ERROR_THRESHOLD) {
            log.warn("Consecutive errors threshold reached: count={}, threshold={}",
                    context.consecutiveErrors(), CONSECUTIVE_ERROR_THRESHOLD);
            return TerminationDecision.SWITCH_STRATEGY;
        }

        // 3. 正常结束判断：stopReason 为 end_turn/stop + 无工具调用
        if (("end_turn".equals(context.stopReason()) || "stop".equals(context.stopReason()))
                && !context.lastResponseHasToolCalls()) {
            return TerminationDecision.TERMINATE_SUCCESS;
        }

        // 4. 动态 maxTurns = base + consecutiveErrors * 2
        int effectiveMaxTurns = context.maxTurns()
                + context.consecutiveErrors() * ERROR_TURN_EXTENSION;
        if (context.currentTurn() >= effectiveMaxTurns) {
            log.warn("Effective maxTurns reached: turn={}, effectiveMax={} (base={}, errors={})",
                    context.currentTurn(), effectiveMaxTurns,
                    context.maxTurns(), context.consecutiveErrors());
            return TerminationDecision.TERMINATE_ERROR;
        }

        // 5. 滑动窗口检查：最近 5 次工具调用全失败 → REQUEST_USER_INPUT
        List<ToolCallRecord> recent = context.recentResults();
        if (recent != null && recent.size() >= SLIDING_WINDOW_SIZE) {
            List<ToolCallRecord> lastWindow = recent.subList(
                    recent.size() - SLIDING_WINDOW_SIZE, recent.size());
            if (lastWindow.stream().noneMatch(ToolCallRecord::success)) {
                log.warn("Sliding window all-failed: last {} tool calls all failed",
                        SLIDING_WINDOW_SIZE);
                return TerminationDecision.REQUEST_USER_INPUT;
            }
        }

        log.debug("Agent loop continuing: turn={}/{}, tokenUsed={}%, consecutiveErrors={}",
                context.currentTurn(), context.maxTurns(),
                context.tokenBudget() > 0 ? (int)(context.totalTokensUsed() * 100 / context.tokenBudget()) : 0,
                context.consecutiveErrors());
        return TerminationDecision.CONTINUE;
    }
}
