package com.aicodeassistant.engine;

/**
 * 中断原因枚举。
 *
 */
public enum AbortReason {
    /** Ctrl+C / 停止按钮 → 注入中断消息 */
    USER_INTERRUPT,
    /** 用户提交新消息 → 不注入中断消息 */
    SUBMIT_INTERRUPT,
    /** maxTurns 或 token 超时 */
    TIMEOUT,
    /** 不可恢复错误 */
    ERROR,
    /** 系统关闭 — 应用优雅关闭时终止后台代理 */
    SYSTEM_SHUTDOWN
}
