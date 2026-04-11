package com.aicodeassistant.tool;

/**
 * Pipeline 执行结果 — 包装 ToolResult + 可能被 contextModifier 更新的 context。
 *
 * @param result         工具执行结果
 * @param updatedContext 被 contextModifier 修改后的上下文（null 表示未修改）
 */
public record ToolExecutionResult(
    ToolResult result,
    ToolUseContext updatedContext
) {
    /** 无 context 变更的快捷工厂 */
    public static ToolExecutionResult of(ToolResult result) {
        return new ToolExecutionResult(result, null);
    }

    /** 带 context 变更的快捷工厂 */
    public static ToolExecutionResult of(ToolResult result, ToolUseContext ctx) {
        return new ToolExecutionResult(result, ctx);
    }
}
