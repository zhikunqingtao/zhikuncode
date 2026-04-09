package com.aicodeassistant.engine;

/**
 * 中断原因枚举 — 对齐原版 AbortController.signal.reason 区分。
 *
 * @see <a href="SPEC §3.1.1">查询主循环</a>
 */
public enum AbortReason {
    /** Ctrl+C / 停止按钮 → 注入中断消息 */
    USER_INTERRUPT,
    /** 用户提交新消息 → 不注入中断消息 (对齐原版 reason !== 'interrupt') */
    SUBMIT_INTERRUPT,
    /** maxTurns 或 token 超时 */
    TIMEOUT,
    /** 不可恢复错误 */
    ERROR
}
