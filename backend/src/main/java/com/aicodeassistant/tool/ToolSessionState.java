package com.aicodeassistant.tool;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具会话级状态管理 — 维护每个会话对工具的启用/禁用覆盖。
 * <p>
 * 会话级状态优先于工具的全局 {@link Tool#isEnabled()} 状态。
 * 当会话结束时应调用 {@link #clearSession(String)} 释放资源。
 */
@Component
public class ToolSessionState {

    // sessionId → (toolName → enabled)
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Boolean>> state = new ConcurrentHashMap<>();

    /**
     * 获取会话中某工具的覆盖状态。
     *
     * @param sessionId 会话ID
     * @param toolName  工具名称
     * @return 覆盖状态，若未设置则返回 null
     */
    public Boolean getToolState(String sessionId, String toolName) {
        return state.getOrDefault(sessionId, new ConcurrentHashMap<>()).get(toolName);
    }

    /**
     * 设置会话中某工具的覆盖状态。
     *
     * @param sessionId 会话ID
     * @param toolName  工具名称
     * @param enabled   是否启用
     */
    public void setToolState(String sessionId, String toolName, boolean enabled) {
        state.computeIfAbsent(sessionId, k -> new ConcurrentHashMap<>()).put(toolName, enabled);
    }

    /**
     * 清除指定会话的所有工具状态覆盖。
     *
     * @param sessionId 会话ID
     */
    public void clearSession(String sessionId) {
        state.remove(sessionId);
    }
}
