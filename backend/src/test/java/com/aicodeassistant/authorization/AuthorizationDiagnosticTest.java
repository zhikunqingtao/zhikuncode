package com.aicodeassistant.authorization;

import com.aicodeassistant.model.PermissionScope;
import com.aicodeassistant.tool.ToolUseContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationDiagnosticTest {
    @Test
    void allowDiagnosticContainsStableCorrelationAndNoRawInput() {
        AuthorizationSubject subject = new AuthorizationSubject(
                "session-root", "run-root", "run-child", "workspace", Path.of("/workspace"));
        OperationDescriptor operation = new OperationDescriptor(
                1, "Bash", "execute", "input-hash", "bash-v1",
                List.of(EffectClass.PROCESS, EffectClass.READ_RESOURCE),
                List.of(new ResourceRef("cwd", ".", false)), List.of(), List.of(),
                RiskClass.GUARDED, "operation-hash", "ls <redacted>");
        ToolUseContext context = ToolUseContext.of("/workspace", "synthetic-child")
                .withCurrentRunId("run-child").withToolUseId("tool-use");

        var payload = AuthorizationDiagnostic.payload(subject, operation, context, "attempt-1",
                AuthorizationDiagnostic.Outcome.ALLOW,
                AuthorizationDiagnostic.EvaluationStage.FINAL_RECHECK,
                AuthorizationDiagnostic.Source.GRANT, "GRANT_MATCH", "grant-1",
                PermissionScope.SESSION, "interaction-1");

        assertThat(payload).containsEntry("rootRunId", "run-root")
                .containsEntry("currentRunId", "run-child")
                .containsEntry("toolUseId", "tool-use")
                .containsEntry("executionAttemptId", "attempt-1")
                .containsEntry("authorizationSource", "GRANT")
                .containsEntry("evaluationStage", "FINAL_RECHECK")
                .containsEntry("grantScope", "SESSION")
                .doesNotContainKeys("input", "command", "canonicalJson");
        assertThat(payload.toString()).doesNotContain("secret-value");
    }
}
