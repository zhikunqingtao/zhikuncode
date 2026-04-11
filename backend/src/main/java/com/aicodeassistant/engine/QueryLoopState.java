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
 * 对照源码 query.ts L204-217，每次迭代开始时解构状态，结束时更新。
 *
 * @see <a href="SPEC §3.1.1a">查询主循环实现细节</a>
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
}
