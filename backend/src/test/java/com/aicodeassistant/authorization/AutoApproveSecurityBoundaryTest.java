package com.aicodeassistant.authorization;

import com.aicodeassistant.interaction.DurableInteractionService;
import com.aicodeassistant.model.PermissionMode;
import com.aicodeassistant.permission.PermissionModeManager;
import com.aicodeassistant.run.RunControlService;
import com.aicodeassistant.security.PathSecurityService;
import com.aicodeassistant.security.SensitiveDataFilter;
import com.aicodeassistant.service.ProjectWorkspaceService;
import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import com.aicodeassistant.tool.bash.BashSecurityAnalyzer;
import com.aicodeassistant.tool.bash.ShellStateManager;
import com.aicodeassistant.tool.bash.ast.ParseForSecurityResult;
import com.aicodeassistant.tool.impl.WebFetchTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutoApproveSecurityBoundaryTest {

    @TempDir
    Path workspace;

    @Test
    void hardDeniedUncPathIsRejectedBeforeAutoApproval() throws Exception {
        Fixture fixture = fixture();
        Tool read = readTool();
        ToolInput input = ToolInput.from(Map.of(
                "file_path", "//attacker.invalid/share/secret.txt"));

        try (FrozenToolInput frozen = fixture.freeze(read, input)) {
            assertThatThrownBy(() -> fixture.service().prepare(
                    read, frozen, input, fixture.context()))
                    .isInstanceOfSatisfying(AuthorizationException.class,
                            denied -> assertThat(denied.code())
                                    .isEqualTo("PROTECTED_PATH_DENIED"));
        }
    }

    @Test
    void finalDynamicRecheckRejectsChangedFileTargetAfterAutoApproval() throws Exception {
        Fixture fixture = fixture();
        Path approvedPath = Files.writeString(workspace.resolve("approved.txt"), "approved");
        Path replacement = Files.writeString(workspace.resolve("replacement.txt"), "replacement");
        Tool read = readTool();
        ToolInput input = ToolInput.from(Map.of("file_path", approvedPath.toString()));

        try (FrozenToolInput frozen = fixture.freeze(read, input)) {
            PreparedOperation prepared = fixture.service().prepare(
                    read, frozen, input, fixture.context());
            AuthorizedOperation allowed = fixture.service().authorizePrepared(
                    read, frozen, input, fixture.context(), prepared);
            assertThat(allowed.source()).isEqualTo(AuthorizationDiagnostic.Source.MODE);
            assertThat(allowed.reasonCode()).isEqualTo("AUTO_APPROVE");

            Files.delete(approvedPath);
            Files.createSymbolicLink(approvedPath, replacement);

            assertThatThrownBy(() -> fixture.service().finalDynamicRecheck(
                    read, allowed, fixture.context()))
                    .isInstanceOfSatisfying(AuthorizationException.class,
                            denied -> assertThat(denied.code())
                                    .isEqualTo("AUTHORIZATION_FINAL_RECHECK_DENIED"));
        }
    }

    @Test
    void webFetchLoopbackIsDeniedInsideToolAfterAutoApproval() throws Exception {
        Fixture fixture = fixture();
        WebFetchTool webFetch = spy(new WebFetchTool());
        ToolInput input = ToolInput.from(Map.of("url", "http://127.0.0.1:9/private"));

        try (FrozenToolInput frozen = fixture.freeze(webFetch, input)) {
            PreparedOperation prepared = fixture.service().prepare(
                    webFetch, frozen, input, fixture.context());
            AuthorizedOperation allowed = fixture.service().authorizePrepared(
                    webFetch, frozen, input, fixture.context(), prepared);
            assertThat(allowed.source()).isEqualTo(AuthorizationDiagnostic.Source.MODE);
            assertThat(allowed.reasonCode()).isEqualTo("AUTO_APPROVE");

            ToolResult result = new ToolExecutionGateway(fixture.service(), fixture.runs())
                    .execute(webFetch, allowed, fixture.context());

            assertThat(result.isError()).isTrue();
            assertThat(result.failureCode()).isEqualTo("WEB_FETCH_URL_DENIED");
            verify(webFetch, times(1)).call(any(ToolInput.class), any(ToolUseContext.class));
        }
    }

    private Fixture fixture() throws Exception {
        Path authorizationRoot = workspace.toRealPath();
        AuthorizationSubject subject = new AuthorizationSubject(
                "session", "run", "run", "workspace", authorizationRoot);
        AuthorizationSubjectResolver subjects = mock(AuthorizationSubjectResolver.class);
        when(subjects.resolve("run")).thenReturn(subject);
        PermissionModeManager modes = mock(PermissionModeManager.class);
        when(modes.getMode("session")).thenReturn(PermissionMode.AUTO_APPROVE);
        RunControlService runs = mock(RunControlService.class);
        when(runs.executeBoundedWrite(any())).thenAnswer(invocation -> {
            Supplier<?> operation = invocation.getArgument(0);
            return operation.get();
        });
        ObjectMapper json = new ObjectMapper();
        OperationAnalyzerRegistry analyzers = new OperationAnalyzerRegistry(
                json, safeBash(), new SensitiveDataFilter(),
                new PathSecurityService(), new ShellStateManager());
        AuthorizationService service = new AuthorizationService(
                subjects, analyzers, mock(PermissionGrantRepository.class),
                mock(DurableInteractionService.class), modes, runs, json,
                mock(ProjectWorkspaceService.class));
        return new Fixture(service, runs,
                ToolUseContext.of(authorizationRoot.toString(), "session")
                        .withCurrentRunId("run")
                        .withToolUseId("tool-use"),
                new FrozenToolInputFactory(json, 16 * 1024, 64 * 1024));
    }

    private static Tool readTool() {
        Tool read = mock(Tool.class);
        when(read.getName()).thenReturn("Read");
        when(read.getPath(any(ToolInput.class))).thenAnswer(invocation ->
                invocation.getArgument(0, ToolInput.class).getString("file_path"));
        return read;
    }

    private static BashSecurityAnalyzer safeBash() {
        BashSecurityAnalyzer bash = mock(BashSecurityAnalyzer.class);
        when(bash.parseForSecurity(anyString(), any(Path.class), any(Path.class)))
                .thenReturn(new ParseForSecurityResult.Simple(List.of()));
        when(bash.analyzeEnvironmentReferences(anyString()))
                .thenReturn(new BashSecurityAnalyzer.EnvironmentReferenceAnalysis(
                        Set.of(), Set.of(), Set.of(),
                        BashSecurityAnalyzer.EnvironmentReferenceAnalysis.EnvironmentParseStatus.SUCCESS,
                        null));
        return bash;
    }

    private record Fixture(
            AuthorizationService service,
            RunControlService runs,
            ToolUseContext context,
            FrozenToolInputFactory frozenInputs) {

        FrozenToolInput freeze(Tool tool, ToolInput input) {
            return frozenInputs.freeze(tool.getName(), input);
        }
    }
}
