package com.aicodeassistant.engine;

import com.aicodeassistant.llm.LlmApiException;
import com.aicodeassistant.llm.ThinkingBudgetCalculator;
import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.aicodeassistant.tool.ToolUseContext;

import java.util.ArrayList;
import java.util.List;

/**
 * 查询循环状态 — 跨迭代共享的可变状态。
 * <p>
 *
 */
public class QueryLoopState {

    private List<Message> messages;
    private ToolUseContext toolUseContext;
    private boolean autoCompactEnabled = true;
    private int autoCompactFailures = 0;
    private int maxOutputTokensRecoveryCount = 0;
    private Integer maxTokensOverride = null;
    private boolean hasAttemptedReactiveCompact = false;
    private int turnCount = 0;
    private AbortReason abortReason = null;
    private boolean stopHookActive = false;
    private String lastTransitionReason = null;

    /** 扣留的错误 — 413/max_output_tokens 等可恢复错误在恢复尝试期间扣留，不立即释放给消费者 */
    private List<LlmApiException> withheldErrors = new ArrayList<>();

    /** 413错误扣留状态 — true表示正在恢复中，不向消费者暴露错误 */
    @com.fasterxml.jackson.annotation.JsonProperty("promptTooLongWithheld")
    private boolean promptTooLongWithheld = false;

    /** 增量折叠标记，标识当前轮次是否需要增量折叠 */
    @com.fasterxml.jackson.annotation.JsonProperty("incrementalCollapseNeeded")
    private boolean incrementalCollapseNeeded = false;

    public QueryLoopState(List<Message> messages, ToolUseContext toolUseContext) {
        this.messages = new ArrayList<>(messages);
        this.toolUseContext = toolUseContext;
    }

    // ==================== Getters ====================

    public List<Message> getMessages() { return messages; }
    public ToolUseContext getToolUseContext() { return toolUseContext; }
    public boolean isAutoCompactEnabled() { return autoCompactEnabled; }
    public int getAutoCompactFailures() { return autoCompactFailures; }
    public int getMaxOutputTokensRecoveryCount() { return maxOutputTokensRecoveryCount; }
    public Integer getMaxTokensOverride() { return maxTokensOverride; }
    public boolean hasAttemptedReactiveCompact() { return hasAttemptedReactiveCompact; }
    public int getTurnCount() { return turnCount; }
    public AbortReason getAbortReason() { return abortReason; }
    public boolean isStopHookActive() { return stopHookActive; }

    // ==================== Setters ====================

    public void setMessages(List<Message> messages) {
        this.messages = new ArrayList<>(messages);
    }

    public void addMessage(Message message) {
        this.messages.add(message);
    }

    public void addMessages(List<Message> messages) {
        this.messages.addAll(messages);
    }

    public void setToolUseContext(ToolUseContext context) {
        this.toolUseContext = context;
    }

    public void setAutoCompactEnabled(boolean enabled) {
        this.autoCompactEnabled = enabled;
    }

    public void incrementAutoCompactFailures() {
        this.autoCompactFailures++;
    }

    public void resetAutoCompactFailures() {
        this.autoCompactFailures = 0;
    }

    public void incrementRecoveryCount() {
        this.maxOutputTokensRecoveryCount++;
    }

    public void setMaxTokensOverride(Integer override) {
        this.maxTokensOverride = override;
    }

    public void setHasAttemptedReactiveCompact(boolean attempted) {
        this.hasAttemptedReactiveCompact = attempted;
    }

    public void incrementTurnCount() {
        this.turnCount++;
    }

    public void setAbortReason(AbortReason reason) {
        this.abortReason = reason;
    }

    public void setStopHookActive(boolean active) {
        this.stopHookActive = active;
    }

    public void resetRecoveryCount() {
        this.maxOutputTokensRecoveryCount = 0;
    }

    public String getLastTransitionReason() { return lastTransitionReason; }
    public void setLastTransitionReason(String reason) { this.lastTransitionReason = reason; }

    public boolean isPromptTooLongWithheld() { return promptTooLongWithheld; }
    public void setPromptTooLongWithheld(boolean withheld) { this.promptTooLongWithheld = withheld; }

    public boolean isIncrementalCollapseNeeded() { return incrementalCollapseNeeded; }
    public void setIncrementalCollapseNeeded(boolean needed) { this.incrementalCollapseNeeded = needed; }

    // ==================== Withheld Errors ====================

    /** 添加扣留错误 */
    public void addWithheldError(LlmApiException error) {
        this.withheldErrors.add(error);
    }

    /** 清空扣留错误（恢复成功时调用） */
    public void clearWithheldErrors() {
        this.withheldErrors.clear();
    }

    /** 是否有扣留错误 */
    public boolean hasWithheldErrors() {
        return !this.withheldErrors.isEmpty();
    }

    /** 获取扣留错误列表 */
    public List<LlmApiException> getWithheldErrors() {
        return List.copyOf(this.withheldErrors);
    }

    /** 获取有效的 maxTokens (考虑 override) */
    public int getEffectiveMaxTokens(int defaultMaxTokens) {
        return maxTokensOverride != null ? maxTokensOverride : defaultMaxTokens;
    }

    /** 电路断路器 — 自动压缩连续失败超过阈值时禁用 */
    public boolean isAutoCompactCircuitBroken() {
        return autoCompactFailures >= 3;
    }

    /**
     * 提取上一轮 assistant 消息的上下文指标。
     * 用于 ThinkingBudgetCalculator 计算本轮思考预算。
     */
    public ThinkingBudgetCalculator.ContextMetrics getContextMetrics() {
        // 从 messages 中倒序查找最近的 assistant 消息
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof Message.AssistantMessage am) {
                int toolCallCount = (int) am.content().stream()
                        .filter(b -> b instanceof ContentBlock.ToolUseBlock)
                        .count();
                int outputTokens = am.usage() != null ? am.usage().outputTokens() : 0;
                return new ThinkingBudgetCalculator.ContextMetrics(
                        toolCallCount, outputTokens, turnCount);
            }
        }
        return ThinkingBudgetCalculator.ContextMetrics.INITIAL;
    }

    /**
     * 构建 ContextCascade 所需的 AutoCompactTrackingState。
     * 每轮开始时 compactedThisTurn 重置为 false。
     */
    public ContextCascade.AutoCompactTrackingState toAutoCompactTrackingState() {
        return new ContextCascade.AutoCompactTrackingState(
                false,                // compactedThisTurn: 每轮开始重置
                turnCount,            // turnCounter
                null,                 // lastTurnId
                autoCompactFailures   // consecutiveFailures
        );
    }
}
