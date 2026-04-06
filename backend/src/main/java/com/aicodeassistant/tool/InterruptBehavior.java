package com.aicodeassistant.tool;

/**
 * 中断行为枚举 — 工具执行被中断时的处理策略。
 *
 * @see <a href="SPEC §3.2.2">工具并发执行引擎</a>
 */
public enum InterruptBehavior {
    /** 取消执行，丢弃结果 — 适用于可重试的只读工具 */
    CANCEL,
    /** 继续执行，新消息等待 — 适用于不可中断的写入工具 (默认) */
    BLOCK
}
