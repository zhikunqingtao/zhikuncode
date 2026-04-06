package com.aicodeassistant.state;

import java.util.function.UnaryOperator;

/**
 * 应用状态 — 6 子状态组合的不可变 record。
 * <p>
 * 对照源码 AppState 的 90+ 字段，按 §3.5.1 建议拆分为子状态组合：
 * <ul>
 *     <li>SessionState — 会话核心 (sessionId, model, messages, workingDir)</li>
 *     <li>PermissionState — 权限系统 (mode, rules, denialTracking)</li>
 *     <li>CostState — 成本与用量 (totalUsage, costUsd, breakdown)</li>
 *     <li>ContextState — 上下文管理 (autoCompact, claudeMd, memory, settings)</li>
 *     <li>ToolState — 工具状态 (enabledTools, toolPool, callCounts, mcp)</li>
 *     <li>UiState — UI 状态 (isStreaming, isCompacting, spinner, theme)</li>
 * </ul>
 * 状态变更通过 withXxx() 方法返回新实例，保证不可变性。
 *
 * @see <a href="SPEC §3.5.1">AppState 结构</a>
 */
public record AppState(
        SessionState session,
        PermissionState permissions,
        CostState cost,
        ContextState context,
        ToolState tools,
        UiState ui
) {
    /**
     * 创建默认初始状态 — 对应 getDefaultAppState()。
     */
    public static AppState defaultState() {
        return new AppState(
                SessionState.empty(),
                PermissionState.empty(),
                CostState.empty(),
                ContextState.empty(),
                ToolState.empty(),
                UiState.empty()
        );
    }

    // ───── 子状态级别的 withXxx 更新方法 ─────

    public AppState withSession(SessionState session) {
        return new AppState(session, permissions, cost, context, tools, ui);
    }

    public AppState withSession(UnaryOperator<SessionState> updater) {
        return new AppState(updater.apply(session), permissions, cost, context, tools, ui);
    }

    public AppState withPermissions(PermissionState permissions) {
        return new AppState(session, permissions, cost, context, tools, ui);
    }

    public AppState withPermissions(UnaryOperator<PermissionState> updater) {
        return new AppState(session, updater.apply(permissions), cost, context, tools, ui);
    }

    public AppState withCost(CostState cost) {
        return new AppState(session, permissions, cost, context, tools, ui);
    }

    public AppState withCost(UnaryOperator<CostState> updater) {
        return new AppState(session, permissions, updater.apply(cost), context, tools, ui);
    }

    public AppState withContext(ContextState context) {
        return new AppState(session, permissions, cost, context, tools, ui);
    }

    public AppState withContext(UnaryOperator<ContextState> updater) {
        return new AppState(session, permissions, cost, updater.apply(context), tools, ui);
    }

    public AppState withTools(ToolState tools) {
        return new AppState(session, permissions, cost, context, tools, ui);
    }

    public AppState withTools(UnaryOperator<ToolState> updater) {
        return new AppState(session, permissions, cost, context, updater.apply(tools), ui);
    }

    public AppState withUi(UiState ui) {
        return new AppState(session, permissions, cost, context, tools, ui);
    }

    public AppState withUi(UnaryOperator<UiState> updater) {
        return new AppState(session, permissions, cost, context, tools, updater.apply(ui));
    }
}
