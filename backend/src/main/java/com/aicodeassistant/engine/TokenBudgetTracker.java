package com.aicodeassistant.engine;

/**
 * Token Budget 续写追踪器。
 * 对齐原版 tokenBudget.ts 的完整逻辑。
 * <p>
 * 当模型自然停止(end_turn)但还没用完预算时，
 * 注入续写消息要求模型继续工作。
 */
public class TokenBudgetTracker {

    private static final double COMPLETION_THRESHOLD = 0.9;
    private static final int DIMINISHING_THRESHOLD = 500;

    private int continuationCount = 0;
    private int lastDeltaTokens = 0;
    private int lastGlobalTurnTokens = 0;
    private final long startedAt;

    public TokenBudgetTracker() {
        this.startedAt = System.currentTimeMillis();
    }

    public sealed interface Decision permits ContinueDecision, StopDecision {}

    public record ContinueDecision(
            String nudgeMessage,
            int continuationCount,
            int pct,
            int turnTokens,
            int budget
    ) implements Decision {}

    public record StopDecision(
            CompletionEvent completionEvent
    ) implements Decision {}

    public record CompletionEvent(
            int continuationCount,
            int pct, int turnTokens, int budget,
            boolean diminishingReturns,
            long durationMs
    ) {}

    /**
     * 检查 token budget，决定是否继续。
     * 对齐 tokenBudget.ts:45-93 checkTokenBudget()
     *
     * @param agentId          子代理ID (非null时跳过检查)
     * @param budget           token 预算 (null表示不限)
     * @param globalTurnTokens 当前已使用的 output tokens
     */
    public Decision check(String agentId, Integer budget, int globalTurnTokens) {
        // 子代理或无 budget → 直接停止
        if (agentId != null || budget == null || budget <= 0) {
            return new StopDecision(null);
        }

        int turnTokens = globalTurnTokens;
        int pct = Math.round((float) turnTokens / budget * 100);
        int deltaSinceLastCheck = globalTurnTokens - lastGlobalTurnTokens;

        // 递减回报检测: 连续3次以上 + 连续2次delta < 500
        boolean isDiminishing = continuationCount >= 3
                && deltaSinceLastCheck < DIMINISHING_THRESHOLD
                && lastDeltaTokens < DIMINISHING_THRESHOLD;

        // 未达到90%预算 且 不递减 → 继续（对齐 tokenBudget.ts:64）
        if (!isDiminishing && turnTokens < budget * COMPLETION_THRESHOLD) {
            continuationCount++;
            lastDeltaTokens = deltaSinceLastCheck;
            lastGlobalTurnTokens = globalTurnTokens;

            String nudge = String.format(
                    "You've used %d%% of the token budget (%,d / %,d tokens). "
                    + "Continue working towards completing the task.",
                    pct, turnTokens, budget);

            return new ContinueDecision(nudge, continuationCount, pct, turnTokens, budget);
        }

        // 停止: 达到90%或递减回报
        if (isDiminishing || continuationCount > 0) {
            return new StopDecision(new CompletionEvent(
                    continuationCount, pct, turnTokens, budget,
                    isDiminishing, System.currentTimeMillis() - startedAt));
        }

        return new StopDecision(null);
    }
}
