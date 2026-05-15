package com.aicodeassistant.engine.strategy;

/**
 * Agent Loop 终止决策枚举。
 * <p>
 * 定义查询循环在每轮结束时可能做出的决策类型。
 */
public enum TerminationDecision {
    /** 继续执行下一轮 */
    CONTINUE,
    /** 正常结束（任务完成） */
    TERMINATE_SUCCESS,
    /** Token 预算耗尽 */
    TERMINATE_BUDGET,
    /** 错误过多，无法继续 */
    TERMINATE_ERROR,
    /** 需要用户确认才能继续 */
    REQUEST_USER_INPUT,
    /** 切换恢复策略 */
    SWITCH_STRATEGY
}
