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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthorizationServiceProjectFileScopeTest {

    @TempDir
    Path workspace;

    @Test
    void planAllowsAnalyzedHandoffReadButNotAnUnknownToolWithTheSameName() {
        var fixture=fixture(PermissionMode.PLAN,false);
        var read=descriptor("HandoffRead","handoff-read-v1",List.of(EffectClass.READ_RESOURCE),RiskClass.SAFE,
                List.of(new ResourceRef("handoff","bound:hash",false)));
        assertThat(fixture.authorize(read).reasonCode()).isEqualTo("SAFE_READ_AUTO");
        var unknown=descriptor("HandoffRead","static-or-remote-v1",List.of(EffectClass.READ_RESOURCE),RiskClass.SAFE,
                List.of(new ResourceRef("handoff","bound:hash",false)));
        assertThatThrownBy(() -> fixture.authorize(unknown)).isInstanceOf(AuthorizationException.class);
    }

    @Test
    void defaultModeAutoAllowsOnlyTrustedOrdinaryFileWrites() {
        Fixture fixture = fixture(PermissionMode.DEFAULT, true);
        OperationDescriptor write = descriptor(
                "Write", "file-v1",
                List.of(EffectClass.WRITE_RESOURCE),
                RiskClass.GUARDED,
                List.of(new ResourceRef(
                        "path", "src/App.java", false)));

        AuthorizedOperation authorized =
                fixture.authorize(write);

        assertThat(authorized.reasonCode())
                .isEqualTo("PROJECT_FILE_SCOPE");
        assertThat(authorized.source())
                .isEqualTo(AuthorizationDiagnostic.Source.POLICY);
        verify(fixture.interactions(), never())
                .createAuthorization(
                        anyString(), anyString(), nullable(String.class),
                        any(), anyList(), anyList(), anyString(),
                        any(AuthorizationInteractionContext.class));
    }

    @Test
    void planStillDeniesTrustedFileWrites() {
        Fixture fixture = fixture(PermissionMode.PLAN, true);
        OperationDescriptor write = descriptor(
                "Edit", "file-v1",
                List.of(EffectClass.WRITE_RESOURCE),
                RiskClass.GUARDED,
                List.of(new ResourceRef(
                        "path", "src/App.java", false)));

        assertThatThrownBy(() -> fixture.authorize(write))
                .isInstanceOfSatisfying(
                        AuthorizationException.class,
                        denied -> assertThat(denied.code())
                                .isEqualTo("PLAN_MODE_EFFECT_DENIED"));
    }

    @Test
    void dontAskAllowsTrustedProjectFileWritesWithoutInteraction() {
        Fixture fixture = fixture(PermissionMode.DONT_ASK, true);
        OperationDescriptor write = descriptor(
                "Edit", "file-v1",
                List.of(EffectClass.WRITE_RESOURCE),
                RiskClass.GUARDED,
                List.of(new ResourceRef(
                        "path", "src/App.java", false)));

        AuthorizedOperation authorized = fixture.authorize(write);

        assertThat(authorized.reasonCode())
                .isEqualTo("PROJECT_FILE_SCOPE");
        verify(fixture.interactions(), never())
                .createAuthorization(
                        anyString(), anyString(), nullable(String.class),
                        any(), anyList(), anyList(), anyString(),
                        any(AuthorizationInteractionContext.class));
    }

    @Test
    void dontAskDeniesOrdinaryWritesWhenRootIsNotTrustedProject() {
        Fixture fixture = fixture(PermissionMode.DONT_ASK, false);
        OperationDescriptor write = descriptor(
                "Write", "file-v1",
                List.of(EffectClass.WRITE_RESOURCE),
                RiskClass.GUARDED,
                List.of(new ResourceRef(
                        "path", "src/App.java", false)));

        assertThatThrownBy(() -> fixture.authorize(write))
                .isInstanceOfSatisfying(
                        AuthorizationException.class,
                        denied -> assertThat(denied.code())
                                .isEqualTo(
                                        "PERMISSION_INTERACTION_REQUIRED"));
    }

    @Test
    void planAndDontAskDenyProtectedHighRiskWritesWithoutPrompt() {
        for (PermissionMode mode : List.of(
                PermissionMode.PLAN,
                PermissionMode.DONT_ASK)) {
            Fixture fixture = fixture(mode, true);
            OperationDescriptor protectedFile = descriptor(
                    "Write", "file-v1",
                    List.of(EffectClass.WRITE_RESOURCE),
                    RiskClass.HIGH,
                    List.of(new ResourceRef(
                            "path",
                            ".ai-code-assistant/data.db",
                            false)));

            assertThatThrownBy(() ->
                    fixture.authorize(protectedFile))
                    .isInstanceOfSatisfying(
                            AuthorizationException.class,
                            denied -> assertThat(denied.code())
                                    .isEqualTo(
                                            mode == PermissionMode.PLAN
                                                    ? "PLAN_MODE_EFFECT_DENIED"
                                                    : "PERMISSION_INTERACTION_REQUIRED"));
            verify(fixture.interactions(), never())
                    .createAuthorization(
                            anyString(), anyString(), nullable(String.class),
                            any(), anyList(), anyList(), anyString(),
                            any(AuthorizationInteractionContext.class));
        }
    }

    @Test
    void planAndDontAskDenyEveryHighRiskAnalyzerWithoutPrompt() {
        for (PermissionMode mode : List.of(
                PermissionMode.PLAN,
                PermissionMode.DONT_ASK)) {
            Fixture fixture = fixture(mode, true);
            OperationDescriptor highRiskBash = descriptor(
                    "Bash", "bash-v2",
                    List.of(EffectClass.PROCESS),
                    RiskClass.HIGH,
                    List.of(new ResourceRef(
                            "cwd", ".", false)));

            assertThatThrownBy(() ->
                    fixture.authorize(highRiskBash))
                    .isInstanceOfSatisfying(
                            AuthorizationException.class,
                            denied -> assertThat(denied.code())
                                    .isEqualTo(
                                            mode == PermissionMode.PLAN
                                                    ? "PLAN_MODE_EFFECT_DENIED"
                                                    : "PERMISSION_INTERACTION_REQUIRED"));
            verify(fixture.interactions(), never())
                    .createAuthorization(
                            anyString(), anyString(), nullable(String.class),
                            any(), anyList(), anyList(), anyString(),
                            any(AuthorizationInteractionContext.class));
        }
    }

    @Test
    void protectedFilesNeverUseProjectAutoAllow() {
        Fixture fixture = fixture(PermissionMode.DEFAULT, true);
        when(fixture.interactions().createAuthorization(
                anyString(), anyString(), nullable(String.class),
                any(), anyList(), anyList(), anyString(),
                any(AuthorizationInteractionContext.class)))
                .thenThrow(PermissionPromptExpected.class);

        OperationDescriptor protectedFile = descriptor(
                "Write", "file-v1",
                List.of(EffectClass.WRITE_RESOURCE),
                RiskClass.HIGH,
                List.of(new ResourceRef(
                        "path", ".git/config", false)));
        assertThatThrownBy(() ->
                fixture.authorize(protectedFile))
                .isInstanceOf(PermissionPromptExpected.class);
        verify(fixture.projects(), never())
                .isTrustedFileScope(any(Path.class));
    }

    @Test
    void nonFileToolsNeverUseProjectAutoAllow() {
        Fixture fixture = fixture(PermissionMode.DEFAULT, true);
        when(fixture.interactions().createAuthorization(
                anyString(), anyString(), nullable(String.class),
                any(), anyList(), anyList(), anyString(),
                any(AuthorizationInteractionContext.class)))
                .thenThrow(PermissionPromptExpected.class);
        OperationDescriptor bash = descriptor(
                "Bash", "bash-v2",
                List.of(
                        EffectClass.PROCESS,
                        EffectClass.WRITE_RESOURCE),
                RiskClass.GUARDED,
                List.of(new ResourceRef(
                        "cwd", ".", false)));
        assertThatThrownBy(() -> fixture.authorize(bash))
                .isInstanceOf(PermissionPromptExpected.class);
        verify(fixture.projects(), never())
                .isTrustedFileScope(any(Path.class));
    }

    @Test
    void revokedProjectIsDeniedAtFinalAdmission() {
        Fixture fixture = fixture(PermissionMode.DEFAULT, true);
        when(fixture.projects().isTrustedFileScope(workspace))
                .thenReturn(true, false);
        OperationDescriptor write = descriptor(
                "Write", "file-v1",
                List.of(EffectClass.WRITE_RESOURCE),
                RiskClass.GUARDED,
                List.of(new ResourceRef(
                        "path", "src/App.java", false)));

        AuthorizedOperation authorized = fixture.authorize(write);

        assertThatThrownBy(() -> fixture.service()
                .finalGrantRecheckInCurrentTransaction(
                        authorized,
                        ToolUseContext.of(
                                workspace.toString(),
                                "session")))
                .isInstanceOfSatisfying(
                        AuthorizationException.class,
                        denied -> assertThat(denied.code())
                                .isEqualTo(
                                        "AUTHORIZATION_FINAL_RECHECK_DENIED"));
    }

    private Fixture fixture(
            PermissionMode mode, boolean trusted) {
        AuthorizationSubjectResolver subjects =
                mock(AuthorizationSubjectResolver.class);
        OperationAnalyzerRegistry analyzers =
                mock(OperationAnalyzerRegistry.class);
        PermissionGrantRepository grants =
                mock(PermissionGrantRepository.class);
        DurableInteractionService interactions =
                mock(DurableInteractionService.class);
        PermissionModeManager modes =
                mock(PermissionModeManager.class);
        RunControlService runs =
                mock(RunControlService.class);
        ProjectWorkspaceService projects =
                mock(ProjectWorkspaceService.class);
        AuthorizationSubject subject =
                new AuthorizationSubject(
                        "session", "run", "run",
                        "workspace", workspace);
        when(modes.getMode("session")).thenReturn(mode);
        when(projects.isTrustedFileScope(workspace))
                .thenReturn(trusted);

        AuthorizationService service =
                new AuthorizationService(
                        subjects, analyzers, grants,
                        interactions, modes, runs,
                        new ObjectMapper(), projects);
        return new Fixture(
                service, interactions, projects, subject);
    }

    private static OperationDescriptor descriptor(
            String toolName,
            String analyzer,
            List<EffectClass> effects,
            RiskClass risk,
            List<ResourceRef> resources) {
        return new OperationDescriptor(
                1, toolName, "invoke", "input-hash",
                analyzer, effects, resources,
                List.of(), List.of(), risk,
                "operation-hash", "summary");
    }

    private record Fixture(
            AuthorizationService service,
            DurableInteractionService interactions,
            ProjectWorkspaceService projects,
            AuthorizationSubject subject) {

        AuthorizedOperation authorize(
                OperationDescriptor descriptor) {
            Tool tool = mock(Tool.class);
            when(tool.getName())
                    .thenReturn(descriptor.toolName());
            ToolInput input = ToolInput.from(Map.of());
            FrozenToolInputFactory factory =
                    new FrozenToolInputFactory(
                            new ObjectMapper(), 1024, 4096);
            try (FrozenToolInput frozen =
                         factory.freeze(
                                 descriptor.toolName(), input)) {
                return service.authorizePrepared(
                        tool, frozen, input,
                        ToolUseContext.of(
                                subject.authorizationRoot().toString(),
                                subject.rootSessionId()),
                        new PreparedOperation(
                                subject, descriptor, "attempt"));
            }
        }
    }

    private static final class PermissionPromptExpected
            extends RuntimeException {
    }
}
