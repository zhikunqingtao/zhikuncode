package com.aicodeassistant.tool.task;

/**
 * 任务状态枚举。
 *
 * @see <a href="SPEC §4.1.3a">TaskCoordinator</a>
 */
public enum TaskStatus {

    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED;

    /** 是否为终态（不可再变更） */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
