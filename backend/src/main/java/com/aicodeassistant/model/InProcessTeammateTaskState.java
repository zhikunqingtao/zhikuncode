package com.aicodeassistant.model;

import java.util.List;

/**
 * 进程内协作者任务特有字段 (IN_PROCESS_TEAMMATE 类型)。
 *
 * @see <a href="SPEC §5.5">任务模型</a>
 */
public record InProcessTeammateTaskState(
        AgentIdentity identity,
        String prompt,
        String model,
        String selectedAgent,
        boolean awaitingPlanApproval,
        PermissionMode permissionMode,
        String error,
        String result,
        double progress,
        List<Message> messages,
        List<Message> pendingUserMessages,
        boolean isIdle,
        boolean shutdownRequested
) {}
