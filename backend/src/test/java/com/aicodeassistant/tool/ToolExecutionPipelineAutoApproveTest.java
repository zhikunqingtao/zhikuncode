package com.aicodeassistant.tool;

import com.aicodeassistant.authorization.AuthorizationDiagnostic;
import com.aicodeassistant.authorization.AuthorizationService;
import com.aicodeassistant.authorization.AuthorizationSubject;
import com.aicodeassistant.authorization.AuthorizedOperation;
import com.aicodeassistant.authorization.EffectClass;
import com.aicodeassistant.authorization.FrozenToolInputFactory;
import com.aicodeassistant.authorization.OperationDescriptor;
import com.aicodeassistant.authorization.PreparedOperation;
import com.aicodeassistant.authorization.RiskClass;
import com.aicodeassistant.authorization.ToolExecutionGateway;
import com.aicodeassistant.hook.HookEvent;
import com.aicodeassistant.hook.HookRegistry;
import com.aicodeassistant.hook.HookService;
import com.aicodeassistant.security.SensitiveDataFilter;
import com.aicodeassistant.tool.recovery.ToolRecoveryFramework;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolExecutionPipelineAutoApproveTest {

    @TempDir
    Path workspace;

    @Test
    void autoApprovedOperationStillUsesGatewayAndCallsToolOnce() {
        HookRegistry hooks = new HookRegistry();
        AuthorizationService authorization = mock(AuthorizationService.class);
        ToolExecutionGateway gateway = mock(ToolExecutionGateway.class);
        AtomicInteger calls = new AtomicInteger();
        Tool tool = tool(calls);
        ToolUseContext context = context();
        PreparedOperation prepared = prepared();
        when(authorization.prepare(any(), any(), any(), any())).thenReturn(prepared);
        when(authorization.authorizePrepared(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> new AuthorizedOperation(
                        prepared.subject(), prepared.descriptor(), invocation.getArgument(2),
                        AuthorizationDiagnostic.Source.MODE, "AUTO_APPROVE",
                        null, null, null, "attempt"));
        when(gateway.execute(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Runnable admission = invocation.getArgument(3);
                    Runnable started = invocation.getArgument(4);
                    admission.run();
                    started.run();
                    Tool executingTool = invocation.getArgument(0);
                    AuthorizedOperation allowed = invocation.getArgument(1);
                    ToolUseContext executingContext = invocation.getArgument(2);
                    return executingTool.call(allowed.executionInput(), executingContext);
                });

        ToolExecutionResult result = pipeline(hooks, authorization, gateway)
                .execute(tool, ToolInput.from(Map.of("value", "ok")), context);

        assertThat(result.result().isError()).isFalse();
        assertThat(calls).hasValue(1);
        verify(gateway).execute(any(), any(), any(), any(), any());
    }

    @Test
    void securityHookDenialStillStopsBeforeAutoApprovalAndExecution() {
        HookRegistry hooks = new HookRegistry();
        hooks.register(HookEvent.PRE_TOOL_USE, null, 1,
                ignored -> HookRegistry.HookResult.deny("blocked-by-test"),
                "test-security", HookRegistry.HookRole.SECURITY_CONSTRAINT);
        AuthorizationService authorization = mock(AuthorizationService.class);
        ToolExecutionGateway gateway = mock(ToolExecutionGateway.class);
        AtomicInteger calls = new AtomicInteger();
        when(authorization.prepare(any(), any(), any(), any())).thenReturn(prepared());

        ToolExecutionResult result = pipeline(hooks, authorization, gateway)
                .execute(tool(calls), ToolInput.from(Map.of("value", "blocked")), context());

        assertThat(result.result().failureCode()).isEqualTo("TOOL_BLOCKED_BY_SECURITY_HOOK");
        assertThat(calls).hasValue(0);
        verify(authorization, never()).authorizePrepared(any(), any(), any(), any(), any());
        verify(gateway, never()).execute(any(), any(), any(), any(), any());
    }

    private ToolExecutionPipeline pipeline(HookRegistry hooks,
                                           AuthorizationService authorization,
                                           ToolExecutionGateway gateway) {
        ObjectMapper json = new ObjectMapper();
        return new ToolExecutionPipeline(
                new HookService(hooks, null), json, new SensitiveDataFilter(),
                new FrozenToolInputFactory(json, 1024, 4096),
                authorization, gateway, new ToolRecoveryFramework(List.of()),
                null, null);
    }

    private PreparedOperation prepared() {
        AuthorizationSubject subject = new AuthorizationSubject(
                "session", "run", "run", "workspace", workspace);
        OperationDescriptor descriptor = new OperationDescriptor(
                1, "TestTool", "invoke", "input-hash", "generic-v1",
                List.of(EffectClass.PROCESS), List.of(), List.of(), List.of(),
                RiskClass.HIGH, "operation-hash", "test operation");
        return new PreparedOperation(subject, descriptor, "attempt");
    }

    private ToolUseContext context() {
        return ToolUseContext.of(workspace.toString(), "session")
                .withCurrentRunId("run")
                .withToolUseId("tool-use");
    }

    private static Tool tool(AtomicInteger calls) {
        return new Tool() {
            @Override public String getName() { return "TestTool"; }
            @Override public String getDescription() { return "test"; }
            @Override public Map<String, Object> getInputSchema() { return Map.of(); }
            @Override public ToolResult call(ToolInput input, ToolUseContext context) {
                calls.incrementAndGet();
                return ToolResult.success("ok");
            }
        };
    }
}
