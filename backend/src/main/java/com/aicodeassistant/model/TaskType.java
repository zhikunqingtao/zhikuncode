package com.aicodeassistant.model;

/**
 * 任务类型枚举。
 *
 * @see <a href="SPEC §5.5">任务模型</a>
 */
public enum TaskType {
    SHELL,
    AGENT,
    REMOTE_AGENT,
    IN_PROCESS_TEAMMATE,
    LOCAL_WORKFLOW,
    MONITOR_MCP,
    DREAM
}
