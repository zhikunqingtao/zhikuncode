package com.aicodeassistant.engine;

import com.aicodeassistant.llm.ModelRegistry;
import com.aicodeassistant.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * ContextCascade — 5 层压缩级联统一协调器。
 * <p>
 * <pre>
 *   Level 0: Snip           ← 单条工具结果超预算时，截断中间保留首尾
 *   Level 1: MicroCompact   ← 对旧的可压缩工具结果替换为 "[cleared]"
 *   Level 2: AutoCompact    ← token 率 > 阈值时，三区划分 + LLM 摘要
 *   Level 3: CollapseDrain  ← 紧急情况下激进压缩 (contextWindow*0.5 目标)
 *   Level 4: ReactiveCompact← API 返回 413 时，仅保留 1 轮 + 极度压缩
 * </pre>
 * <p>
 * 关键设计：
 * - Level 0-1 每次 API 调用前无条件执行（代价极低）
 * - Level 2 基于 buffer-based 阈值触发
 * - Level 3-4 仅在错误恢复路径触发
 * - 状态通过 CascadeState 在层级间传递
 *
 */
@Service
public class ContextCascade {

    private static final Logger log = LoggerFactory.getLogger(ContextCascade.class);

    // ============ 阈值常量 ============

    /** Buffer-based 自动压缩缓冲 token 数（ModelRegistry 未知模型时的保守值） */
    private static final int AUTOCOMPACT_BUFFER_TOKENS_DEFAULT = 13_000;

    /** 最大连续自动压缩失败次数（电路断路器） */
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    /** 工具结果预算占上下文窗口比例 */
    private static final double TOOL_RESULT_BUDGET_RATIO = 0.3;

    /** MicroCompact 保护尾部消息数 */
    private static final int MICRO_COMPACT_PROTECTED_TAIL = 10;

    private final SnipService snipService;
    private final MicroCompactService microCompactService;
    private final ContextCollapseService contextCollapseService;
    private final CompactService compactService;
    private final TokenCounter tokenCounter;
    private final ModelRegistry modelRegistry;
    @Autowired
    public ContextCascade(SnipService snipService,MicroCompactService microCompactService,
            ContextCollapseService contextCollapseService,CompactService compactService,
            TokenCounter tokenCounter,ModelRegistry modelRegistry){
        this.snipService=snipService;this.microCompactService=microCompactService;
        this.contextCollapseService=contextCollapseService;this.compactService=compactService;
        this.tokenCounter=tokenCounter;this.modelRegistry=modelRegistry;
    }

    // ============ 级联状态 ============

    /**
     * Token 警告状态 — 多级阈值判断。
         */
    public record TokenWarningState(
            boolean isAboveWarningThreshold,       // 第一级: ~70% 有效窗口
            boolean isAboveErrorThreshold,          // 第二级: ~90% 有效窗口
            boolean isAboveAutoCompactThreshold,    // 第三级: 触发自动压缩
            int currentTokens,
            int contextWindowSize,
            int autoCompactThreshold
    ) {}

    /**
     * 级联执行结果 — 记录每层的执行情况。
     */
    public record CascadeResult(
            List<Message> messages,
            int originalTokens,
            int finalTokens,
            boolean snipExecuted,
            int snipTokensFreed,
            boolean microCompactExecuted,
            int microCompactTokensFreed,
            boolean contextCollapseExecuted,      // Level 1.5 是否执行
            int contextCollapseCharsFreed,        // Level 1.5 释放字符数
            boolean autoCompactAttempted,          // 是否达到阈值并尝试执行
            boolean autoCompactExecuted,
            CompactService.CompactResult autoCompactResult
    ) {
        public int totalTokensFreed() {
            // Estimation strategies may change between the two measurements. A
            // negative "freed" value is not meaningful and previously polluted
            // run metrics, so expose the conservative lower bound.
            return Math.max(0, originalTokens - finalTokens);
        }

        public String summary() {
            StringBuilder sb = new StringBuilder("ContextCascade: ");
            sb.append(originalTokens).append(" → ").append(finalTokens).append(" tokens");
            if (snipExecuted) sb.append(", Snip: -").append(snipTokensFreed);
            if (microCompactExecuted) sb.append(", MicroCompact: -").append(microCompactTokensFreed);
            if (contextCollapseExecuted) sb.append(", Collapse: -").append(contextCollapseCharsFreed).append("chars");
            if (autoCompactAttempted && !autoCompactExecuted) sb.append(", AutoCompact: ATTEMPTED_FAILED");
            if (autoCompactExecuted && autoCompactResult != null)
                sb.append(", AutoCompact: ").append(autoCompactResult.summary());
            return sb.toString();
        }
    }

    /**
     * 自动压缩追踪状态 — 跨轮次持久化。
         */
    public record AutoCompactTrackingState(
            boolean compactedThisTurn,
            int turnCounter,
            String lastTurnId,
            int consecutiveFailures
    ) {
        public static AutoCompactTrackingState initial() {
            return new AutoCompactTrackingState(false, 0, "", 0);
        }

        public AutoCompactTrackingState withFailure() {
            return new AutoCompactTrackingState(false, turnCounter, lastTurnId,
                    consecutiveFailures + 1);
        }

        public AutoCompactTrackingState withSuccess(String turnId) {
            return new AutoCompactTrackingState(true, turnCounter + 1, turnId, 0);
        }

        public boolean isCircuitBroken() {
            return consecutiveFailures >= MAX_CONSECUTIVE_FAILURES;
        }
    }

    // ============ 核心：Token 警告状态计算 ============

    /**
     * 计算 Token 警告状态 — buffer-based 精确算法。
     * 比 CompactService.shouldAutoCompact() 的 85% 比率更精确。
     *
     * 算法：
     *   effectiveWindow = contextWindow - contextWindow/4  (预留 25% 摘要空间)
     *   autoCompactThreshold = effectiveWindow - AUTOCOMPACT_BUFFER_TOKENS
     */
    public TokenWarningState calculateTokenWarningState(
            List<Message> messages, String model) {
        int contextWindow = modelRegistry.getContextWindowForModel(model);
        int reservedForSummary = contextWindow / 4;
        int effectiveWindow = contextWindow - reservedForSummary;
        // 根据唯一的 ModelRegistry 上下文窗口动态计算缓冲区。
        int bufferTokens = Math.max(AUTOCOMPACT_BUFFER_TOKENS_DEFAULT,
                (int)(contextWindow * 0.10));
        int autoCompactThreshold = effectiveWindow - bufferTokens;

        int currentTokens = tokenCounter.estimateTokens(messages, model);

        return new TokenWarningState(
                currentTokens > (int) (autoCompactThreshold * 0.7),  // 警告
                currentTokens > (int) (autoCompactThreshold * 0.9),  // 错误
                currentTokens > autoCompactThreshold,                 // 触发压缩
                currentTokens, contextWindow, autoCompactThreshold
        );
    }

    // ============ 核心：前置级联（每次 API 调用前） ============

    /**
     * 执行前置级联 — Level 0 + Level 1 + Level 2。
     * 在 QueryEngine.queryLoop() 的 Step 1 中调用，替代分散的三段代码。
     *
     * @param messages      当前消息列表
     * @param model         当前使用的模型
     * @param trackingState 自动压缩追踪状态
     * @return 级联执行结果
     */
    public CascadeResult executePreApiCascade(
            List<Message> messages, String model,
            AutoCompactTrackingState trackingState) {

        long cascadeStartedAt = System.nanoTime();
        int contextWindow = modelRegistry.getContextWindowForModel(model);
        int originalTokens = tokenCounter.estimateTokens(messages, model);
        List<Message> current = messages;

        boolean snipExecuted = false;
        int snipTokensFreed = 0;
        boolean mcExecuted = false;
        int mcTokensFreed = 0;
        boolean collapseExecuted = false;
        int collapseCharsFreed = 0;
        boolean acAttempted = false;
        boolean acExecuted = false;
        CompactService.CompactResult acResult = null;

        // ===== Level 0: Snip (单条工具结果截断) =====
        int toolResultBudget = (int) (contextWindow * TOOL_RESULT_BUDGET_RATIO
                * modelRegistry.getTokenCharRatio(model));
        List<Message> afterSnip = snipService.snipToolResults(current, toolResultBudget);
        int snipBefore = tokenCounter.estimateTokens(current, model);
        int snipAfter = tokenCounter.estimateTokens(afterSnip, model);
        if (snipAfter < snipBefore) {
            snipExecuted = true;
            snipTokensFreed = snipBefore - snipAfter;
            current = afterSnip;
            log.debug("Level 0 Snip: freed {} tokens", snipTokensFreed);
        }

        // ===== Level 1: MicroCompact (旧工具结果清除) =====
        var mcResult = microCompactService.compactMessages(current, MICRO_COMPACT_PROTECTED_TAIL);
        if (mcResult.tokensFreed() > 0) {
            mcExecuted = true;
            mcTokensFreed = mcResult.tokensFreed();
            current = mcResult.messages();
            log.debug("Level 1 MicroCompact: freed {} tokens", mcTokensFreed);
        }

        // ===== Level 1.5: ContextCollapse (三级渐进折叠) =====
        boolean collapseAttempted = true;
        List<Message> collapseInput = current;
        Integer collapseBeforeTokens = estimateTokensForDiagnostics(collapseInput, model);
        ContextCollapseService.CollapseResult collapseResult =
                contextCollapseService.progressiveCollapse(collapseInput);
        boolean collapseMessagesChanged = diagnosticallyDifferent(collapseInput, collapseResult.messages());
        if (collapseResult.collapsedCount() > 0) {
            collapseExecuted = true;
            collapseCharsFreed = collapseResult.estimatedCharsFreed();
            current = collapseResult.messages();
        }

        // ===== Level 2: AutoCompact (LLM 摘要) — 含 Collapse 互斥协调 =====
        boolean collapseDidExecute = collapseAttempted && collapseResult.collapsedCount() > 0;
        int collapseFreedChars = collapseResult.estimatedCharsFreed();
        TokenWarningState postCollapseWarning = null;
        String autoCompactDecision;

        if (collapseDidExecute) {
            // Collapse 已执行，重新评估是否仍需 AutoCompact
            postCollapseWarning = calculateTokenWarningState(current, model);
            if (!postCollapseWarning.isAboveAutoCompactThreshold()) {
                autoCompactDecision = "SKIP_BELOW_THRESHOLD";
            } else if (!trackingState.isCircuitBroken()) {
                autoCompactDecision = "ATTEMPT";
                acAttempted = true;
                try {
                    acResult = compactService.compact(current, contextWindow, false);
                    if (acResult.skipReason() == null && !acResult.compactedMessages().isEmpty()) {
                        acExecuted = true;
                        current = acResult.compactedMessages();
                    }
                } catch (Exception e) {
                    log.error("Level 2 AutoCompact failed", e);
                }
            } else {
                autoCompactDecision = "CIRCUIT_OPEN";
            }
        } else if (!trackingState.isCircuitBroken()) {
            // Collapse 未执行，保持原有 AutoCompact 判断逻辑
            postCollapseWarning = calculateTokenWarningState(current, model);
            if (postCollapseWarning.isAboveAutoCompactThreshold()) {
                autoCompactDecision = "ATTEMPT";
                acAttempted = true;
                try {
                    acResult = compactService.compact(current, contextWindow, false);
                    if (acResult.skipReason() == null && !acResult.compactedMessages().isEmpty()) {
                        acExecuted = true;
                        current = acResult.compactedMessages();
                    }
                } catch (Exception e) {
                    log.error("Level 2 AutoCompact failed", e);
                }
            } else {
                autoCompactDecision = "NOT_NEEDED_NO_COLLAPSE";
            }
        } else {
            autoCompactDecision = "CIRCUIT_OPEN";
        }

        Integer collapseAfterTokens = postCollapseWarning != null
                ? postCollapseWarning.currentTokens()
                : collapseBeforeTokens;
        Integer collapseTokenDelta = collapseBeforeTokens != null && collapseAfterTokens != null
                ? collapseBeforeTokens - collapseAfterTokens
                : null;
        logCascadeEvaluation(collapseAttempted, collapseDidExecute,
                collapseResult.collapsedCount(), collapseFreedChars,
                collapseMessagesChanged, collapseBeforeTokens, collapseAfterTokens,
                collapseTokenDelta, postCollapseWarning, autoCompactDecision,
                acAttempted, acExecuted, cascadeStartedAt);

        int finalTokens = tokenCounter.estimateTokens(current, model);
        CascadeResult result = new CascadeResult(current, originalTokens, finalTokens,
                snipExecuted, snipTokensFreed, mcExecuted, mcTokensFreed,
                collapseExecuted, collapseCharsFreed,
                acAttempted,
                acExecuted, acResult);

        if (result.totalTokensFreed() > 0) {
            log.info(result.summary());
        }

        return result;
    }

    private void logCascadeEvaluation(
            boolean collapseAttempted, boolean collapseExecuted,
            int collapsedCount, int collapseCharsFreed,
            boolean messagesChanged, Integer collapseBeforeTokens,
            Integer collapseAfterTokens, Integer collapseTokenDelta,
            TokenWarningState warning, String autoCompactDecision,
            boolean autoCompactAttempted, boolean autoCompactExecuted,
            long cascadeStartedAt) {
        try {
            String format = "event=context_cascade_evaluation contextEvalId={} sessionId={} runId={} turn={} " +
                    "collapseAttempted={} collapseExecuted={} collapsedCount={} charsFreed={} " +
                    "messagesChanged={} tokensBefore={} tokensAfter={} tokenDelta={} " +
                    "threshold={} aboveThreshold={} autoCompactDecision={} " +
                    "autoCompactAttempted={} autoCompactExecuted={} elapsedMicros={}";
            Object[] args = {
                    mdcValue("contextEvalId"), mdcValue("sessionId"), mdcValue("runId"), mdcValue("turn"),
                    collapseAttempted, collapseExecuted, collapsedCount, collapseCharsFreed,
                    messagesChanged, collapseBeforeTokens, collapseAfterTokens, collapseTokenDelta,
                    warning != null ? warning.autoCompactThreshold() : null,
                    warning != null ? warning.isAboveAutoCompactThreshold() : null,
                    autoCompactDecision, autoCompactAttempted, autoCompactExecuted,
                    TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - cascadeStartedAt)
            };
            if (collapseCharsFreed < 0 || (collapseTokenDelta != null && collapseTokenDelta < 0)) {
                log.warn(format, args);
            } else {
                log.debug(format, args);
            }
        } catch (RuntimeException ignored) {
            // 诊断失败不得改变 Cascade 返回值。
        }
    }

    private boolean diagnosticallyDifferent(Object before, Object after) {
        try {
            return !Objects.equals(before, after);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private Integer estimateTokensForDiagnostics(List<Message> messages, String model) {
        try {
            return tokenCounter.estimateTokens(messages, model);
        } catch (RuntimeException ignored) {
            // 诊断估算失败不得改变原有压缩和 AutoCompact 流程。
            return null;
        }
    }

    private String mdcValue(String key) {
        String value = org.slf4j.MDC.get(key);
        return value != null ? value : "none";
    }

    // ============ 错误恢复级联（413 时调用） ============

    /**
     * 执行错误恢复级联 — Level 3 (CollapseDrain) + Level 4 (ReactiveCompact)。
     * 在 QueryEngine 捕获 413 错误后调用。
     *
     * @param messages      当前消息列表
     * @param contextWindow 上下文窗口大小
     * @param hasAttemptedReactive 是否已尝试过反应式压缩
     * @return 压缩后的消息列表，或 null 表示无法恢复
     */
    public List<Message> executeErrorRecoveryCascade(
            List<Message> messages, int contextWindow,
            boolean hasAttemptedReactive) {

        // Level 3: CollapseDrain — 更激进的压缩 (contextWindow * 0.5 目标)
        try {
            CompactService.CompactResult drainResult = compactService.compact(
                    messages, (int) (contextWindow * 0.5), true);
            if (drainResult.skipReason() == null && !drainResult.compactedMessages().isEmpty()) {
                log.info("Level 3 CollapseDrain: {} → {} tokens",
                        drainResult.beforeTokens(), drainResult.afterTokens());
                return drainResult.compactedMessages();
            }
        } catch (Exception e) {
            log.warn("Level 3 CollapseDrain failed: {}", e.getMessage());
        }

        // Level 4: ReactiveCompact — 最后手段
        if (!hasAttemptedReactive) {
            try {
                CompactService.CompactResult reactiveResult =
                        compactService.reactiveCompact(messages, contextWindow, false);
                if (reactiveResult.skipReason() == null) {
                    log.info("Level 4 ReactiveCompact: {} → {} tokens",
                            reactiveResult.beforeTokens(), reactiveResult.afterTokens());
                    return reactiveResult.compactedMessages();
                }
            } catch (Exception e) {
                log.error("Level 4 ReactiveCompact failed: {}", e.getMessage());
            }
        }

        log.error("ContextCascade: 所有恢复策略已耗尽");
        return null;
    }
}
