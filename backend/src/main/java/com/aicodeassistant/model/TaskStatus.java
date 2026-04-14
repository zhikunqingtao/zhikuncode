package com.aicodeassistant.model;

/**
 * 任务状态枚举。
 *
 * @see <a href="SPEC §5.5">任务模型</a>
 */
public enum TaskStatus {
    PENDING,
    RUNNING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    CANCELLED,
    KILLED;

    /** 是否为终态（不可再变更） */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED || this == KILLED;
    }

    /** RUNNING 和 IN_PROGRESS 视为等价 */
    public boolean isActive() {
        return this == RUNNING || this == IN_PROGRESS;
    }
}
