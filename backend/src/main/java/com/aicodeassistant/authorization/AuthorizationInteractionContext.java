package com.aicodeassistant.authorization;

import java.nio.file.Path;
import java.util.List;

/** 校验 v3 决策并原子创建授权所需的持久化权限事实。 */
public record AuthorizationInteractionContext(
        int protocolVersion,
        String toolUseId,
        String executionAttemptId,
        String inputHash,
        String operationHash,
        AuthorizationSubjectData subject,
        OperationDescriptor operation,
        List<DecisionOption> options) {
    public static final int PROTOCOL_VERSION = 3;
    public record DecisionOption(String optionId, String decision, String scope) { }
    public record AuthorizationSubjectData(String rootSessionId, String rootRunId,
            String currentRunId, String workspaceKey, String authorizationRoot) {
        public static AuthorizationSubjectData from(AuthorizationSubject value) {
            return new AuthorizationSubjectData(value.rootSessionId(), value.rootRunId(), value.currentRunId(),
                    value.workspaceKey(), value.authorizationRoot().toString());
        }
        public AuthorizationSubject toSubject() {
            return new AuthorizationSubject(rootSessionId, rootRunId, currentRunId, workspaceKey,
                    Path.of(authorizationRoot));
        }
    }
}
