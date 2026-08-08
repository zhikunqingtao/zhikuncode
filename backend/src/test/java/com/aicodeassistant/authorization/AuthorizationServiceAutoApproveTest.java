package com.aicodeassistant.authorization;

import com.aicodeassistant.interaction.DurableInteractionService;
import com.aicodeassistant.model.PermissionMode;
import com.aicodeassistant.permission.PermissionModeManager;
import com.aicodeassistant.run.RunControlService;
import com.aicodeassistant.service.ProjectWorkspaceService;
import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolUseContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthorizationServiceAutoApproveTest {

    @TempDir
    Path workspace;

    @Test
    void autoApprovesGuardedHighAndExternalOperationsWithoutInteractionOrGrantLookup() {
        for (OperationDescriptor operation : List.of(
                descriptor("Bash", "bash-v2", RiskClass.GUARDED,
                        List.of(new ResourceRef("cwd", ".", false))),
                descriptor("Bash", "bash-v2", RiskClass.HIGH,
                        List.of(new ResourceRef("cwd", ".", false))),
                descriptor("Read", "file-v1", RiskClass.GUARDED,
                        List.of(new ResourceRef("path", "/outside/file.txt", true))),
                descriptor("WebFetch", "network-v1", RiskClass.GUARDED,
                        List.of()))) {
            Fixture fixture = fixture();

            AuthorizedOperation authorized = fixture.authorize(operation);

            assertThat(authorized.source()).isEqualTo(AuthorizationDiagnostic.Source.MODE);
            assertThat(authorized.reasonCode()).isEqualTo("AUTO_APPROVE");
            assertThat(authorized.grantId()).isNull();
            assertThat(authorized.grantScope()).isNull();
            assertThat(authorized.interactionId()).isNull();
            verify(fixture.grants(), never()).findMatch(any(), any());
            verify(fixture.interactions(), never()).createAuthorization(
                    any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    @Test
    void safeInternalKeepsBuiltinPolicySemantics() {
        Fixture fixture = fixture();
        OperationDescriptor operation = new OperationDescriptor(
                1, "Internal", "invoke", "input-hash", "generic-v1",
                List.of(EffectClass.SAFE_INTERNAL), List.of(), List.of(), List.of(),
                RiskClass.SAFE, "operation-hash", "summary");

        AuthorizedOperation authorized = fixture.authorize(operation);

        assertThat(authorized.source()).isEqualTo(AuthorizationDiagnostic.Source.POLICY);
        assertThat(authorized.reasonCode()).isEqualTo("BUILTIN_SAFE");
    }

    private Fixture fixture() {
        AuthorizationSubjectResolver subjects = mock(AuthorizationSubjectResolver.class);
        OperationAnalyzerRegistry analyzers = mock(OperationAnalyzerRegistry.class);
        PermissionGrantRepository grants = mock(PermissionGrantRepository.class);
        DurableInteractionService interactions = mock(DurableInteractionService.class);
        PermissionModeManager modes = mock(PermissionModeManager.class);
        RunControlService runs = mock(RunControlService.class);
        ProjectWorkspaceService projects = mock(ProjectWorkspaceService.class);
        AuthorizationSubject subject = new AuthorizationSubject(
                "session", "run", "run", "workspace", workspace);
        when(modes.getMode("session")).thenReturn(PermissionMode.AUTO_APPROVE);

        AuthorizationService service = new AuthorizationService(
                subjects, analyzers, grants, interactions, modes, runs,
                new ObjectMapper(), projects);
        return new Fixture(service, grants, interactions, subject);
    }

    private static OperationDescriptor descriptor(
            String toolName, String analyzer, RiskClass risk, List<ResourceRef> resources) {
        return new OperationDescriptor(
                1, toolName, "invoke", "input-hash", analyzer,
                List.of(EffectClass.PROCESS), resources, List.of(), List.of(), risk,
                "operation-hash", "summary");
    }

    private record Fixture(
            AuthorizationService service,
            PermissionGrantRepository grants,
            DurableInteractionService interactions,
            AuthorizationSubject subject) {

        AuthorizedOperation authorize(OperationDescriptor descriptor) {
            Tool tool = mock(Tool.class);
            when(tool.getName()).thenReturn(descriptor.toolName());
            ToolInput input = ToolInput.from(Map.of());
            FrozenToolInputFactory factory = new FrozenToolInputFactory(
                    new ObjectMapper(), 1024, 4096);
            try (FrozenToolInput frozen = factory.freeze(descriptor.toolName(), input)) {
                return service.authorizePrepared(
                        tool, frozen, input,
                        ToolUseContext.of(subject.authorizationRoot().toString(),
                                subject.rootSessionId()),
                        new PreparedOperation(subject, descriptor, "attempt"));
            }
        }
    }
}
