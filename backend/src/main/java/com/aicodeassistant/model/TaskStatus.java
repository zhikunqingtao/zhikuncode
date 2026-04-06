package com.aicodeassistant.model;

/**
 * 任务状态枚举。
 *
 * @see <a href="SPEC §5.5">任务模型</a>
 */
public enum TaskStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    KILLED
}
