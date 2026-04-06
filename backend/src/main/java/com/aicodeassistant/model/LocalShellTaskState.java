package com.aicodeassistant.model;

import java.util.List;

/**
 * Shell 任务特有字段 (SHELL 类型)。
 *
 * @see <a href="SPEC §5.5">任务模型</a>
 */
public record LocalShellTaskState(
        String command,
        String result,
        boolean completionStatusSentInAttachment,
        ShellCommand shellCommand,
        boolean isBackgrounded,
        String agentId,
        String kind
) {}
