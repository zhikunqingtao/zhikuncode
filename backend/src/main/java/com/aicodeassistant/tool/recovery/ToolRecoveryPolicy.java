package com.aicodeassistant.tool.recovery;

/**
 * 工具恢复策略接口 — 每种工具类型可实现自己的恢复逻辑。
 * <p>
 * 使用策略模式，通过 Spring 自动收集所有实现类。
 */
public interface ToolRecoveryPolicy {

    /**
     * 判断此策略是否能处理给定的恢复上下文。
     *
     * @param context 恢复上下文
     * @return true 表示此策略可以处理
     */
    boolean canHandle(ToolRecoveryFramework.RecoveryContext context);

    /**
     * 执行恢复逻辑，返回恢复决策。
     *
     * @param context 恢复上下文
     * @return 恢复决策
     */
    ToolRecoveryFramework.RecoveryDecision recover(ToolRecoveryFramework.RecoveryContext context);
}
