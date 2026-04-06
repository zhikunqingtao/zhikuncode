package com.aicodeassistant.model;

/**
 * 会话状态枚举。
 *
 * @see <a href="SPEC §5.2">会话模型</a>
 */
public enum SessionStatus {
    ACTIVE,
    IDLE,
    SUSPENDED,
    COMPLETED,
    FAILED
}
