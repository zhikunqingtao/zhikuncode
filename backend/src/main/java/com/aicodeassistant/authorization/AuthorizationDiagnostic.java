package com.aicodeassistant.authorization;

import com.aicodeassistant.model.PermissionScope;
import com.aicodeassistant.tool.ToolUseContext;

import java.util.LinkedHashMap;
import java.util.Map;

/** 写入现有 Run 事件日志的稳定、无秘密授权诊断信息。 */
public final class AuthorizationDiagnostic {
    public enum Outcome { ALLOW, ASK, DENY }
    public enum EvaluationStage { INITIAL, INTERACTION, FINAL_RECHECK }
    public enum Source { INVARIANT, POLICY, GRANT, USER_ONCE, MODE }

    private AuthorizationDiagnostic() { }

    public static Map<String, Object> payload(AuthorizationSubject subject,
                                               OperationDescriptor operation,
                                               ToolUseContext context,
                                               String executionAttemptId,
                                               Outcome outcome,
                                               EvaluationStage stage,
                                               Source source,
                                               String reasonCode,
                                               String grantId,
                                               PermissionScope grantScope,
                                               String interactionId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("outcome", outcome.name());
        data.put("evaluationStage", stage.name());
        data.put("authorizationSource", source.name());
        data.put("reasonCode", reasonCode);
        data.put("rootRunId", subject.rootRunId());
        data.put("currentRunId", subject.currentRunId());
        data.put("toolUseId", context.toolUseId() == null ? "" : context.toolUseId());
        data.put("executionAttemptId", executionAttemptId);
        data.put("toolName", operation.toolName());
        data.put("analyzerId", operation.analyzerId());
        data.put("risk", operation.risk().name());
        data.put("inputHash", operation.inputHash());
        data.put("operationHash", operation.operationHash());
        data.put("redactedSummary", operation.redactedSummary());
        if (grantId != null) data.put("grantId", grantId);
        if (grantScope != null) data.put("grantScope", grantScope.name());
        if (interactionId != null) data.put("interactionId", interactionId);
        return Map.copyOf(data);
    }

    public static Map<String, Object> analysisFailure(AuthorizationSubject subject,
                                                       String toolName,
                                                       String inputHash,
                                                       ToolUseContext context,
                                                       String executionAttemptId,
                                                       String reasonCode) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("outcome", Outcome.DENY.name());
        data.put("evaluationStage", EvaluationStage.INITIAL.name());
        data.put("authorizationSource", Source.INVARIANT.name());
        data.put("reasonCode", reasonCode);
        data.put("rootRunId", subject.rootRunId());
        data.put("currentRunId", subject.currentRunId());
        data.put("toolUseId", context.toolUseId() == null ? "" : context.toolUseId());
        data.put("executionAttemptId", executionAttemptId);
        data.put("toolName", toolName);
        data.put("inputHash", inputHash);
        return Map.copyOf(data);
    }
}
