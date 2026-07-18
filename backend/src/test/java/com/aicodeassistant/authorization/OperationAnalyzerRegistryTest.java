package com.aicodeassistant.authorization;

import com.aicodeassistant.security.PathSecurityService;
import com.aicodeassistant.security.SensitiveDataFilter;
import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolUseContext;
import com.aicodeassistant.tool.bash.BashSecurityAnalyzer;
import com.aicodeassistant.tool.bash.ShellStateManager;
import com.aicodeassistant.tool.bash.ast.ParseForSecurityResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OperationAnalyzerRegistryTest {
    @TempDir Path temp;

    @Test
    void unchangedBashFactsPassStrictFinalRecheck() throws Exception {
        BashSecurityAnalyzer bash = safeBash();
        OperationAnalyzerRegistry registry = registry(bash);
        Tool tool = bashTool();
        ToolInput input = ToolInput.from(java.util.Map.of("command", "pwd && ls -la"));
        AuthorizationSubject subject = new AuthorizationSubject(
                "s", "root", "root", "wk", temp.toRealPath());
        ToolUseContext context = ToolUseContext.of(temp.toString(), "s");

        try (FrozenToolInput frozen = frozen(input)) {
            OperationDescriptor approved = registry.analyzerFor(tool).analyze(
                    tool, frozen, input, context, subject);

            org.assertj.core.api.Assertions.assertThatCode(() -> registry.analyzerFor(tool)
                    .recheck(tool, approved, input, context, subject)).doesNotThrowAnyException();
            assertThat(approved.effects())
                    .containsExactly(EffectClass.PROCESS, EffectClass.READ_RESOURCE);
        }
    }

    @Test
    void bashExactIdentityIncludesWorkingDirectory() throws Exception {
        BashSecurityAnalyzer bash = safeBash();
        OperationAnalyzerRegistry registry = registry(bash);
        Tool tool = bashTool();
        ToolInput input = ToolInput.from(java.util.Map.of("command", "ls -la"));
        FrozenToolInput frozen = frozen(input);
        Path firstDirectory = java.nio.file.Files.createDirectory(temp.resolve("a"));
        Path secondDirectory = java.nio.file.Files.createDirectory(temp.resolve("b"));
        AuthorizationSubject subject = new AuthorizationSubject("s", "root", "child", "wk", temp.toRealPath());

        OperationDescriptor first = registry.analyzerFor(tool).analyze(tool, frozen, input,
                ToolUseContext.of(firstDirectory.toString(), "child"), subject);
        OperationDescriptor second = registry.analyzerFor(tool).analyze(tool, frozen, input,
                ToolUseContext.of(secondDirectory.toString(), "child"), subject);

        assertThat(first.operationHash()).isNotEqualTo(second.operationHash());
        assertThat(first.resources()).containsExactly(new ResourceRef("cwd", "a", false));
    }

    @Test
    void relativeWorkingDirectoryIsResolvedAgainstAuthorizationRoot() throws Exception {
        BashSecurityAnalyzer bash = safeBash();
        OperationAnalyzerRegistry registry = registry(bash);
        java.nio.file.Files.createDirectory(temp.resolve("module"));
        AuthorizationSubject subject = new AuthorizationSubject(
                "s", "root", "child", "wk", temp.toRealPath());
        ToolInput input = ToolInput.from(java.util.Map.of("command", "ls"));

        OperationDescriptor operation = registry.analyzerFor(bashTool()).analyze(
                bashTool(), frozen(input), input, ToolUseContext.of("module", "s"), subject);

        assertThat(operation.resources()).containsExactly(new ResourceRef("cwd", "module", false));
    }

    @Test
    void bashExactIdentityIgnoresDisplayDescriptionAndTimeout() throws Exception {
        BashSecurityAnalyzer bash = safeBash();
        OperationAnalyzerRegistry registry = registry(bash);
        Tool tool = bashTool();
        ToolInput firstInput = ToolInput.from(java.util.Map.of(
                "command", "ls -la", "description", "first wording", "timeout", 1_000));
        ToolInput secondInput = ToolInput.from(java.util.Map.of(
                "command", "ls -la", "description", "different wording", "timeout", 30_000));
        AuthorizationSubject subject = new AuthorizationSubject("s", "root", "child", "wk", temp.toRealPath());

        OperationDescriptor first = registry.analyzerFor(tool).analyze(tool, frozen(firstInput), firstInput,
                ToolUseContext.of(temp.toString(), "child"), subject);
        OperationDescriptor second = registry.analyzerFor(tool).analyze(tool, frozen(secondInput), secondInput,
                ToolUseContext.of(temp.toString(), "child"), subject);

        assertThat(first.inputHash()).isNotEqualTo(second.inputHash());
        assertThat(first.operationHash()).isEqualTo(second.operationHash());
        assertThat(first.analyzerId()).isEqualTo("bash-v2");
    }

    @Test
    void bashExactIdentityIncludesBackgroundExecutionMode() throws Exception {
        BashSecurityAnalyzer bash = safeBash();
        OperationAnalyzerRegistry registry = registry(bash);
        Tool tool = bashTool();
        ToolInput foreground = ToolInput.from(java.util.Map.of("command", "ls -la"));
        ToolInput background = ToolInput.from(java.util.Map.of("command", "ls -la", "is_background", true));
        AuthorizationSubject subject = new AuthorizationSubject("s", "root", "child", "wk", temp.toRealPath());

        OperationDescriptor first = registry.analyzerFor(tool).analyze(tool, frozen(foreground), foreground,
                ToolUseContext.of(temp.toString(), "child"), subject);
        OperationDescriptor second = registry.analyzerFor(tool).analyze(tool, frozen(background), background,
                ToolUseContext.of(temp.toString(), "child"), subject);

        assertThat(first.operationHash()).isNotEqualTo(second.operationHash());
    }

    @Test
    void reusableBashIdentityIsStableAcrossRepeatedAnalysis() throws Exception {
        BashSecurityAnalyzer bash = safeBash();
        when(bash.analyzeEnvironmentReferences(anyString())).thenReturn(
                new BashSecurityAnalyzer.EnvironmentReferenceAnalysis(Set.of(), Set.of("HOME"), Set.of(),
                        BashSecurityAnalyzer.EnvironmentReferenceAnalysis.EnvironmentParseStatus.SUCCESS, null));
        when(bash.isAllowedInheritedEnvironmentReference("HOME")).thenReturn(true);
        ShellStateManager shellState = new ShellStateManager();
        String session = "auth-env-" + java.util.UUID.randomUUID();
        OperationAnalyzerRegistry registry = registry(bash, shellState);
        Tool tool = bashTool();
        ToolInput input = ToolInput.from(java.util.Map.of("command", "printf '%s' \"$HOME\""));
        AuthorizationSubject subject = new AuthorizationSubject(session, "root", "root", "wk", temp.toRealPath());

        OperationDescriptor first = registry.analyzerFor(tool).analyze(tool, frozen(input), input,
                ToolUseContext.of(temp.toString(), session), subject);
        OperationDescriptor second = registry.analyzerFor(tool).analyze(tool, frozen(input), input,
                ToolUseContext.of(temp.toString(), session), subject);

        assertThat(second.operationHash()).isEqualTo(first.operationHash());
        org.assertj.core.api.Assertions.assertThatCode(() -> registry.analyzerFor(tool).recheck(
                tool, first, input, ToolUseContext.of(temp.toString(), session), subject))
                .doesNotThrowAnyException();
    }

    @Test
    void absoluteCommandBlacklistCannotBeOverriddenByOnceInteraction() throws Exception {
        BashSecurityAnalyzer bash = mock(BashSecurityAnalyzer.class);
        when(bash.parseForSecurity(anyString(), any(Path.class), any(Path.class)))
                .thenReturn(new ParseForSecurityResult.TooComplex("disk destruction", "command-blacklist-deny"));
        when(bash.analyzeEnvironmentReferences(anyString())).thenReturn(environment());
        OperationAnalyzer analyzer = registry(bash).analyzerFor(bashTool());

        assertThatThrownBy(() -> analyzer.analyze(bashTool(),
                frozen(ToolInput.from(java.util.Map.of("command", "dd of=/dev/disk0"))),
                ToolInput.from(java.util.Map.of("command", "dd of=/dev/disk0")),
                ToolUseContext.of(temp.toString(), "s"),
                new AuthorizationSubject("s", "r", "r", "wk", temp.toRealPath())))
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("disk destruction");
    }

    private static OperationAnalyzerRegistry registry(BashSecurityAnalyzer bash) {
        return registry(bash, new ShellStateManager());
    }

    private static OperationAnalyzerRegistry registry(BashSecurityAnalyzer bash, ShellStateManager shellState) {
        PathSecurityService paths = mock(PathSecurityService.class);
        when(paths.checkReadPermission(anyString(), anyString()))
                .thenReturn(PathSecurityService.PathCheckResult.allowed());
        when(paths.checkWritePermission(anyString(), anyString()))
                .thenReturn(PathSecurityService.PathCheckResult.allowed());
        return new OperationAnalyzerRegistry(new ObjectMapper(), bash, new SensitiveDataFilter(), paths, shellState);
    }

    private static BashSecurityAnalyzer safeBash() {
        BashSecurityAnalyzer bash = mock(BashSecurityAnalyzer.class);
        when(bash.parseForSecurity(anyString(), any(Path.class), any(Path.class)))
                .thenReturn(new ParseForSecurityResult.Simple(List.of()));
        when(bash.analyzeEnvironmentReferences(anyString())).thenReturn(environment());
        return bash;
    }

    private static BashSecurityAnalyzer.EnvironmentReferenceAnalysis environment() {
        return new BashSecurityAnalyzer.EnvironmentReferenceAnalysis(Set.of(), Set.of(), Set.of(),
                BashSecurityAnalyzer.EnvironmentReferenceAnalysis.EnvironmentParseStatus.SUCCESS, null);
    }

    private static Tool bashTool() {
        Tool tool = mock(Tool.class);
        when(tool.getName()).thenReturn("Bash");
        when(tool.isReadOnly(any(ToolInput.class))).thenReturn(true);
        return tool;
    }

    private static FrozenToolInput frozen(ToolInput input) {
        return new FrozenToolInputFactory(new ObjectMapper(), 1024 * 1024, 4 * 1024 * 1024)
                .freeze("Bash", input);
    }
}
