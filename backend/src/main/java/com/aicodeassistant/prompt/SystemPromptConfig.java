package com.aicodeassistant.prompt;

import com.aicodeassistant.tool.agent.SubAgentExecutor;

/**
 * SystemPromptConfig — 系统提示配置。
 * <p>
 * 用于 EffectiveSystemPromptBuilder 的 5 级优先级链选择。
 * <p>
 * 对照源码: SPEC §3.1.1 EffectiveSystemPromptBuilder (10659-10704行)
 *
 * @see EffectiveSystemPromptBuilder
 */
public class SystemPromptConfig {

    // 优先级 0: Override 系统提示（最高优先级）
    private String overrideSystemPrompt;

    // 优先级 1: Coordinator 系统提示
    private String coordinatorPrompt;

    // 优先级 2: Agent 定义
    private SubAgentExecutor.AgentDefinition agentDefinition;
    private boolean proactive;

    // 优先级 3: Custom 系统提示（--system-prompt CLI 参数）
    private String customSystemPrompt;

    // 追加到最终提示末尾（除非使用 Override）
    private String appendSystemPrompt;

    public SystemPromptConfig() {
    }

    // ===== Builder 风格构造 =====

    public static SystemPromptConfig defaults() {
        return new SystemPromptConfig();
    }

    public SystemPromptConfig withOverride(String override) {
        this.overrideSystemPrompt = override;
        return this;
    }

    public SystemPromptConfig withCoordinator(String coordinator) {
        this.coordinatorPrompt = coordinator;
        return this;
    }

    public SystemPromptConfig withAgent(SubAgentExecutor.AgentDefinition agent, boolean proactive) {
        this.agentDefinition = agent;
        this.proactive = proactive;
        return this;
    }

    public SystemPromptConfig withCustom(String custom) {
        this.customSystemPrompt = custom;
        return this;
    }

    public SystemPromptConfig withAppend(String append) {
        this.appendSystemPrompt = append;
        return this;
    }

    // ===== Getters =====

    public String getOverrideSystemPrompt() {
        return overrideSystemPrompt;
    }

    public void setOverrideSystemPrompt(String overrideSystemPrompt) {
        this.overrideSystemPrompt = overrideSystemPrompt;
    }

    public String getCoordinatorPrompt() {
        return coordinatorPrompt;
    }

    public void setCoordinatorPrompt(String coordinatorPrompt) {
        this.coordinatorPrompt = coordinatorPrompt;
    }

    public SubAgentExecutor.AgentDefinition getAgentDefinition() {
        return agentDefinition;
    }

    public void setAgentDefinition(SubAgentExecutor.AgentDefinition agentDefinition) {
        this.agentDefinition = agentDefinition;
    }

    public boolean isProactive() {
        return proactive;
    }

    public void setProactive(boolean proactive) {
        this.proactive = proactive;
    }

    public String getCustomSystemPrompt() {
        return customSystemPrompt;
    }

    public void setCustomSystemPrompt(String customSystemPrompt) {
        this.customSystemPrompt = customSystemPrompt;
    }

    public String getAppendSystemPrompt() {
        return appendSystemPrompt;
    }

    public void setAppendSystemPrompt(String appendSystemPrompt) {
        this.appendSystemPrompt = appendSystemPrompt;
    }
}
