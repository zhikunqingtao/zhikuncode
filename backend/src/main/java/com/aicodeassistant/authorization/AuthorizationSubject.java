package com.aicodeassistant.authorization;

import java.nio.file.Path;

/** 仅根据持久化 Run/会话状态推导出的可信授权主体。 */
public record AuthorizationSubject(
        String rootSessionId,
        String rootRunId,
        String currentRunId,
        String workspaceKey,
        Path authorizationRoot) {
}
