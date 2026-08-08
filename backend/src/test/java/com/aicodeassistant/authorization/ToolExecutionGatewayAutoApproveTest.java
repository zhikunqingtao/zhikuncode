package com.aicodeassistant.authorization;

import com.aicodeassistant.run.RunControlService;
import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolExecutionGatewayAutoApproveTest {

    @TempDir
    Path workspace;

    @Test
    void recordsModeAuditAndCallsToolExactlyOnce() {
        AuthorizationService authorization = mock(AuthorizationService.class);
        RunControlService runs = mock(RunControlService.class);
        Tool tool = mock(Tool.class);
        ToolInput input = ToolInput.from(Map.of("value", "safe-test-value"));
        ToolUseContext context = ToolUseContext.of(workspace.toString(), "session")
                .withCurrentRunId("run")
                .withToolUseId("tool-use");
        AuthorizationSubject subject = new AuthorizationSubject(
                "session", "run", "run", "workspace", workspace);
        OperationDescriptor descriptor = new OperationDescriptor(
                1, "TestTool", "invoke", "input-hash", "generic-v1",
                List.of(EffectClass.PROCESS), List.of(), List.of(), List.of(),
                RiskClass.HIGH, "operation-hash", "test operation");
        AuthorizedOperation allowed = new AuthorizedOperation(
                subject, descriptor, input, AuthorizationDiagnostic.Source.MODE,
                "AUTO_APPROVE", null, null, null, "attempt");

        when(runs.executeBoundedWrite(any())).thenAnswer(invocation -> {
            Supplier<?> operation = invocation.getArgument(0);
            return operation.get();
        });
        when(tool.call(input, context)).thenReturn(ToolResult.success("ok"));

        ToolResult result = new ToolExecutionGateway(authorization, runs)
                .execute(tool, allowed, context);

        assertThat(result.isError()).isFalse();
        verify(authorization).finalDynamicRecheck(tool, allowed, context);
        verify(authorization).finalGrantRecheckInCurrentTransaction(allowed, context);
        verify(tool, times(1)).call(input, context);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> event = ArgumentCaptor.forClass(Map.class);
        verify(runs).appendEventInCurrentWrite(
                eq("run"), eq("tool_started"), eq("tool-use"), event.capture());
        assertThat(event.getValue())
                .containsEntry("outcome", "ALLOW")
                .containsEntry("authorizationSource", "MODE")
                .containsEntry("reasonCode", "AUTO_APPROVE")
                .containsEntry("risk", "HIGH")
                .containsEntry("operationHash", "operation-hash")
                .containsEntry("inputHash", "input-hash");
    }
}
