package com.aicodeassistant.authorization;

import com.aicodeassistant.engine.KeyFileTracker;
import com.aicodeassistant.interaction.DurableInteractionService;
import com.aicodeassistant.permission.PermissionModeManager;
import com.aicodeassistant.run.RunControlService;
import com.aicodeassistant.security.PathSecurityService;
import com.aicodeassistant.security.SensitiveDataFilter;
import com.aicodeassistant.service.FileStateCache;
import com.aicodeassistant.service.ProjectWorkspaceService;
import com.aicodeassistant.session.SessionManager;
import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import com.aicodeassistant.tool.bash.BashSecurityAnalyzer;
import com.aicodeassistant.tool.bash.ShellStateManager;
import com.aicodeassistant.tool.bash.ast.ParseForSecurityResult;
import com.aicodeassistant.tool.impl.EncodingDetector;
import com.aicodeassistant.tool.impl.FileReadTool;
import com.aicodeassistant.tool.impl.ImageResultExternalizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OperationAnalyzerRegistryTest {
    @TempDir Path temp;

    @Test
    void handoffReadRequiresBuiltinIdentityAndRechecksTheRootBinding() throws Exception {
        var reads=mock(com.aicodeassistant.session.merge.HandoffReadService.class);
        when(reads.authorize(eq("root-session"),any())).thenReturn("op:hash");
        var tool=new com.aicodeassistant.tool.impl.HandoffReadTool(reads,mock(AuthorizationSubjectResolver.class),mock(ImageResultExternalizer.class));
        var registry=registry(safeBash());
        var input=ToolInput.from(Map.of("action","list"));
        var subject=new AuthorizationSubject("root-session","root-run","child-run","wk",temp);
        var context=ToolUseContext.of(temp.toString(),"synthetic-child").withCurrentRunId("child-run");
        try(var frozen=frozen(input)) {
            var analyzer=registry.analyzerFor(tool);
            var descriptor=analyzer.analyze(tool,frozen,input,context,subject);
            assertThat(descriptor.analyzerId()).isEqualTo("handoff-read-v1");
            assertThat(descriptor.effects()).containsExactly(EffectClass.READ_RESOURCE);
            assertThat(descriptor.risk()).isEqualTo(RiskClass.SAFE);
            analyzer.recheck(tool,descriptor,input,context,subject);
            when(reads.authorize(eq("root-session"),any())).thenReturn("different:hash");
            assertThatThrownBy(() -> analyzer.recheck(tool,descriptor,input,context,subject)).isInstanceOf(AuthorizationException.class);
        }
        Tool impersonator=mock(Tool.class); when(impersonator.getName()).thenReturn("HandoffRead");
        assertThat(registry.analyzerFor(impersonator).id()).isEqualTo("static-or-remote-v1");
        when(impersonator.isMcp()).thenReturn(true);
        assertThat(registry.analyzerFor(impersonator).id()).isEqualTo("mcp-v1");
    }

    @Test
    void mcpToolsUseDedicatedAnalyzerWithoutExpandingGenericControlTools() {
        OperationAnalyzerRegistry registry = registry(safeBash());
        Tool mcp = mock(Tool.class);
        when(mcp.getName()).thenReturn("mcp__search__query");
        when(mcp.isMcp()).thenReturn(true);
        Tool control = mock(Tool.class);
        when(control.getName()).thenReturn("Agent");
        Tool spoofedMcp = mock(Tool.class);
        when(spoofedMcp.getName()).thenReturn("mcp__spoofed__tool");

        assertThat(registry.analyzerFor(mcp).id()).isEqualTo("mcp-v1");
        assertThat(registry.analyzerFor(spoofedMcp).id()).isEqualTo("static-or-remote-v1");
        assertThat(registry.analyzerFor(control).id()).isEqualTo("static-or-remote-v1");
    }

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

    @Test
    void protectedFileIsHighRiskAndOutsideFileIsGuarded()
            throws Exception {
        Path protectedFile = Files.writeString(
                temp.resolve(".env.production"), "TOKEN=secret");
        Path outside = Files.writeString(
                temp.getParent().resolve(
                        temp.getFileName() + "-outside.txt"),
                "outside");
        OperationAnalyzerRegistry registry =
                new OperationAnalyzerRegistry(
                        new ObjectMapper(), safeBash(),
                        new SensitiveDataFilter(),
                        new PathSecurityService(),
                        new ShellStateManager());
        AuthorizationSubject subject =
                new AuthorizationSubject(
                        "s", "run", "run", "wk",
                        temp.toRealPath());
        Tool read = mock(Tool.class);
        when(read.getName()).thenReturn("Read");
        when(read.getPath(any(ToolInput.class)))
                .thenReturn(protectedFile.toString());
        ToolInput protectedInput = ToolInput.from(
                java.util.Map.of(
                        "file_path", protectedFile.toString()));

        OperationDescriptor protectedOperation =
                registry.analyzerFor(read).analyze(
                        read, frozen(protectedInput),
                        protectedInput,
                        ToolUseContext.of(temp.toString(), "s"),
                        subject);
        assertThat(protectedOperation.risk())
                .isEqualTo(RiskClass.HIGH);
        org.assertj.core.api.Assertions.assertThatCode(
                () -> registry.analyzerFor(read).recheck(
                        read, protectedOperation, protectedInput,
                        ToolUseContext.of(temp.toString(), "s"),
                        subject))
                .doesNotThrowAnyException();

        Tool outsideRead = mock(Tool.class);
        when(outsideRead.getName()).thenReturn("Read");
        when(outsideRead.getPath(any(ToolInput.class)))
                .thenReturn(outside.toString());
        ToolInput outsideInput = ToolInput.from(
                java.util.Map.of(
                        "file_path", outside.toString()));
        OperationDescriptor outsideOperation =
                registry.analyzerFor(outsideRead).analyze(
                        outsideRead, frozen(outsideInput),
                        outsideInput,
                        ToolUseContext.of(
                                temp.toString(), "s"),
                        subject);
        assertThat(outsideOperation.risk())
                .isEqualTo(RiskClass.GUARDED);
        assertThat(outsideOperation.resources())
                .containsExactly(new ResourceRef(
                        "path", outside.toRealPath().toString(), true));
        assertThat(outsideOperation.redactedSummary())
                .contains("outside Project")
                .contains(outside.toRealPath().toString());
    }

    @Test
    void externalFileGrantIdentityIgnoresOffsetsAndContent()
            throws Exception {
        Path workspace = Files.createDirectory(
                temp.resolve("identity-workspace")).toRealPath();
        Path outside = Files.writeString(
                temp.resolve("identity-outside.txt"), "old")
                .toRealPath();
        OperationAnalyzerRegistry registry =
                new OperationAnalyzerRegistry(
                        new ObjectMapper(), safeBash(),
                        new SensitiveDataFilter(),
                        new PathSecurityService(),
                        new ShellStateManager());
        AuthorizationSubject subject = new AuthorizationSubject(
                "s", "run", "run", "wk", workspace);
        ToolUseContext context = ToolUseContext.of(
                workspace.toString(), "s");

        Tool read = mock(Tool.class);
        when(read.getName()).thenReturn("Read");
        when(read.getPath(any(ToolInput.class)))
                .thenReturn(outside.toString());
        ToolInput firstRead = ToolInput.from(java.util.Map.of(
                "file_path", outside.toString(), "offset", 0));
        ToolInput secondRead = ToolInput.from(java.util.Map.of(
                "file_path", outside.toString(), "offset", 500));

        Tool write = mock(Tool.class);
        when(write.getName()).thenReturn("Write");
        when(write.getPath(any(ToolInput.class)))
                .thenReturn(outside.toString());
        ToolInput firstWrite = ToolInput.from(java.util.Map.of(
                "file_path", outside.toString(), "content", "first"));
        ToolInput secondWrite = ToolInput.from(java.util.Map.of(
                "file_path", outside.toString(), "content", "second"));

        try (FrozenToolInput firstReadFrozen = frozen(firstRead);
             FrozenToolInput secondReadFrozen = frozen(secondRead);
             FrozenToolInput firstWriteFrozen = frozen(firstWrite);
             FrozenToolInput secondWriteFrozen = frozen(secondWrite)) {
            OperationDescriptor readOne = registry.analyzerFor(read)
                    .analyze(read, firstReadFrozen, firstRead,
                            context, subject);
            OperationDescriptor readTwo = registry.analyzerFor(read)
                    .analyze(read, secondReadFrozen, secondRead,
                            context, subject);
            OperationDescriptor writeOne = registry.analyzerFor(write)
                    .analyze(write, firstWriteFrozen, firstWrite,
                            context, subject);
            OperationDescriptor writeTwo = registry.analyzerFor(write)
                    .analyze(write, secondWriteFrozen, secondWrite,
                            context, subject);

            assertThat(readOne.inputHash())
                    .isNotEqualTo(readTwo.inputHash());
            assertThat(readOne.operationHash())
                    .isEqualTo(readTwo.operationHash());
            assertThat(writeOne.inputHash())
                    .isNotEqualTo(writeTwo.inputHash());
            assertThat(writeOne.operationHash())
                    .isEqualTo(writeTwo.operationHash());
            assertThat(readOne.risk()).isEqualTo(RiskClass.GUARDED);
            assertThat(writeOne.risk()).isEqualTo(RiskClass.GUARDED);
        }
    }

    @Test
    void recursiveProtectedRootIsHighRiskWithoutUpgradingProjectRoot()
            throws Exception {
        Path workspace = Files.createDirectory(
                temp.resolve("recursive-risk-workspace")).toRealPath();
        Path protectedDirectory = Files.createDirectory(
                workspace.resolve(".ssh"));
        OperationAnalyzerRegistry registry =
                new OperationAnalyzerRegistry(
                        new ObjectMapper(), safeBash(),
                        new SensitiveDataFilter(),
                        new PathSecurityService(),
                        new ShellStateManager());
        AuthorizationSubject subject = new AuthorizationSubject(
                "s", "run", "run", "wk", workspace);
        Tool grep = mock(Tool.class);
        when(grep.getName()).thenReturn("Grep");
        ToolInput broadInput = ToolInput.from(
                java.util.Map.of(
                        "pattern", "secret",
                        "path", workspace.toString()));
        ToolInput protectedInput = ToolInput.from(
                java.util.Map.of(
                        "pattern", "secret",
                        "path", protectedDirectory.toString()));

        try (FrozenToolInput broadFrozen = frozen(broadInput);
             FrozenToolInput protectedFrozen = frozen(protectedInput)) {
            OperationAnalyzer analyzer = registry.analyzerFor(grep);
            OperationDescriptor broad = analyzer.analyze(
                    grep, broadFrozen, broadInput,
                    ToolUseContext.of(workspace.toString(), "s"),
                    subject);
            OperationDescriptor protectedRoot = analyzer.analyze(
                    grep, protectedFrozen, protectedInput,
                    ToolUseContext.of(workspace.toString(), "s"),
                    subject);

            assertThat(broad.risk()).isEqualTo(RiskClass.SAFE);
            assertThat(protectedRoot.risk())
                    .isEqualTo(RiskClass.HIGH);
            org.assertj.core.api.Assertions.assertThatCode(
                    () -> analyzer.recheck(
                            grep, protectedRoot, protectedInput,
                            ToolUseContext.of(
                                    workspace.toString(), "s"),
                            subject))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void directCredentialDirectoriesAreHighButDevelopmentDirectoriesAreOrdinary()
            throws Exception {
        Path workspace = Files.createDirectory(
                temp.resolve("directory-risk-workspace"))
                .toRealPath();
        OperationAnalyzerRegistry registry =
                new OperationAnalyzerRegistry(
                        new ObjectMapper(), safeBash(),
                        new SensitiveDataFilter(),
                        new PathSecurityService(),
                        new ShellStateManager());
        AuthorizationSubject subject = new AuthorizationSubject(
                "s", "run", "run", "wk", workspace);
        Tool read = mock(Tool.class);
        when(read.getName()).thenReturn("Read");

        for (String directory : List.of(
                ".git", ".ssh", ".aws", ".kube",
                ".docker", ".gnupg", ".ai-code-assistant")) {
            Path file = Files.writeString(
                    Files.createDirectory(workspace.resolve(directory))
                            .resolve("ordinary-name"),
                    "secret");
            when(read.getPath(any(ToolInput.class)))
                    .thenReturn(file.toString());
            ToolInput input = ToolInput.from(java.util.Map.of(
                    "file_path", file.toString()));
            try (FrozenToolInput frozen = frozen(input)) {
                assertThat(registry.analyzerFor(read)
                        .analyze(read, frozen, input,
                                ToolUseContext.of(
                                        workspace.toString(), "s"),
                                subject).risk())
                        .as(directory)
                        .isEqualTo(RiskClass.HIGH);
            }
        }

        for (String directory : List.of(
                ".vscode", ".idea", "node_modules", ".local")) {
            Path file = Files.writeString(
                    Files.createDirectory(workspace.resolve(directory))
                            .resolve("ordinary-name"),
                    "ordinary");
            when(read.getPath(any(ToolInput.class)))
                    .thenReturn(file.toString());
            ToolInput input = ToolInput.from(java.util.Map.of(
                    "file_path", file.toString()));
            try (FrozenToolInput frozen = frozen(input)) {
                assertThat(registry.analyzerFor(read)
                        .analyze(read, frozen, input,
                                ToolUseContext.of(
                                        workspace.toString(), "s"),
                                subject).risk())
                        .as(directory)
                        .isEqualTo(RiskClass.SAFE);
            }
        }
    }

    @Test
    void sensitiveAncestorOutsideSelectedProjectDoesNotUpgradeOrdinaryFile()
            throws Exception {
        Path sensitiveAncestor = Files.createDirectory(
                temp.resolve(".ssh"));
        Path workspace = Files.createDirectory(
                sensitiveAncestor.resolve("selected-project"))
                .toRealPath();
        Path file = Files.writeString(
                workspace.resolve("ordinary.txt"), "ordinary");
        OperationAnalyzerRegistry registry =
                new OperationAnalyzerRegistry(
                        new ObjectMapper(), safeBash(),
                        new SensitiveDataFilter(),
                        new PathSecurityService(),
                        new ShellStateManager());
        AuthorizationSubject subject = new AuthorizationSubject(
                "s", "run", "run", "wk", workspace);
        Tool read = mock(Tool.class);
        when(read.getName()).thenReturn("Read");
        when(read.getPath(any(ToolInput.class)))
                .thenReturn(file.toString());
        ToolInput input = ToolInput.from(java.util.Map.of(
                "file_path", file.toString()));

        try (FrozenToolInput frozen = frozen(input)) {
            assertThat(registry.analyzerFor(read)
                    .analyze(read, frozen, input,
                            ToolUseContext.of(
                                    workspace.toString(), "s"),
                            subject).risk())
                    .isEqualTo(RiskClass.SAFE);
        }
    }

    @Test
    void finalFileRecheckRejectsSymlinkReplacement()
            throws Exception {
        Path workspace = Files.createDirectory(
                temp.resolve("recheck-workspace")).toRealPath();
        Path outside = Files.createDirectory(
                temp.resolve("recheck-outside")).toRealPath();
        Path secret = Files.writeString(
                outside.resolve("secret.txt"), "secret");
        Path target = workspace.resolve("target.txt");
        OperationAnalyzerRegistry registry =
                new OperationAnalyzerRegistry(
                        new ObjectMapper(), safeBash(),
                        new SensitiveDataFilter(),
                        new PathSecurityService(),
                        new ShellStateManager());
        AuthorizationSubject subject =
                new AuthorizationSubject(
                        "s", "run", "run", "wk", workspace);
        Tool read = mock(Tool.class);
        when(read.getName()).thenReturn("Read");
        when(read.getPath(any(ToolInput.class)))
                .thenReturn(target.toString());
        ToolInput input = ToolInput.from(
                java.util.Map.of(
                        "file_path", target.toString()));

        try (FrozenToolInput frozen = frozen(input)) {
            OperationAnalyzer analyzer =
                    registry.analyzerFor(read);
            OperationDescriptor descriptor = analyzer.analyze(
                    read, frozen, input,
                    ToolUseContext.of(workspace.toString(), "s"),
                    subject);
            Files.createSymbolicLink(target, secret);

            assertThatThrownBy(() -> analyzer.recheck(
                    read, descriptor, input,
                    ToolUseContext.of(
                            workspace.toString(), "s"),
                    subject))
                    .isInstanceOfSatisfying(
                            AuthorizationException.class,
                            error -> assertThat(error.code())
                                    .isEqualTo(
                                            "AUTHORIZATION_FINAL_RECHECK_DENIED"));
        }
    }

    @Test
    void boundExecutionInputCannotBeRedirectedByAliasRebound()
            throws Exception {
        Path workspace = Files.createDirectory(
                temp.resolve("bound-input-workspace"))
                .toRealPath();
        Path first = Files.writeString(
                workspace.resolve("first.txt"), "first").toRealPath();
        Path second = Files.writeString(
                workspace.resolve("second.txt"), "second").toRealPath();
        Path alias = workspace.resolve("alias.txt");
        Files.createSymbolicLink(alias, first);
        OperationAnalyzerRegistry registry =
                new OperationAnalyzerRegistry(
                        new ObjectMapper(), safeBash(),
                        new SensitiveDataFilter(),
                        new PathSecurityService(),
                        new ShellStateManager());
        AuthorizationSubject subject = new AuthorizationSubject(
                "s", "run", "run", "wk", workspace);
        Tool read = mock(Tool.class);
        when(read.getName()).thenReturn("Read");
        when(read.getPath(any(ToolInput.class)))
                .thenAnswer(invocation -> invocation
                        .getArgument(0, ToolInput.class)
                        .getString("file_path"));
        ToolInput input = ToolInput.from(java.util.Map.of(
                "file_path", alias.toString(), "offset", 7));

        try (FrozenToolInput frozen = frozen(input)) {
            OperationAnalyzer analyzer = registry.analyzerFor(read);
            OperationDescriptor descriptor = analyzer.analyze(
                    read, frozen, input,
                    ToolUseContext.of(workspace.toString(), "s"),
                    subject);
            ToolInput bound = registry.bindExecutionInput(
                    read, descriptor, input, subject);
            assertThat(bound.getString("file_path"))
                    .isEqualTo(first.toString());
            assertThat(bound.getInt("offset"))
                    .isEqualTo(7);

            Files.delete(alias);
            Files.createSymbolicLink(alias, second);

            org.assertj.core.api.Assertions.assertThatCode(
                    () -> analyzer.recheck(
                            read, descriptor, bound,
                            ToolUseContext.of(
                                    workspace.toString(), "s"),
                            subject))
                    .doesNotThrowAnyException();
            assertThat(Files.readString(
                    Path.of(bound.getString("file_path"))))
                    .isEqualTo("first");
        }
    }

    @Test
    void fileAnalyzerRejectsUncBeforePathCanonicalization()
            throws Exception {
        Path workspace = Files.createDirectory(
                temp.resolve("unc-analyzer-workspace"))
                .toRealPath();
        OperationAnalyzerRegistry registry =
                new OperationAnalyzerRegistry(
                        new ObjectMapper(), safeBash(),
                        new SensitiveDataFilter(),
                        new PathSecurityService(),
                        new ShellStateManager());
        AuthorizationSubject subject = new AuthorizationSubject(
                "s", "run", "run", "wk", workspace);
        Tool read = mock(Tool.class);
        when(read.getName()).thenReturn("Read");
        when(read.getPath(any(ToolInput.class)))
                .thenAnswer(invocation -> invocation
                        .getArgument(0, ToolInput.class)
                        .getString("file_path"));
        ToolInput input = ToolInput.from(Map.of(
                "file_path",
                "//attacker.invalid/share/secret.txt"));

        try (FrozenToolInput frozen = frozen(input)) {
            assertThatThrownBy(() -> registry.analyzerFor(read)
                    .analyze(read, frozen, input,
                            ToolUseContext.of(
                                    workspace.toString(), "s"),
                            subject))
                    .isInstanceOfSatisfying(
                            AuthorizationException.class,
                            denied -> {
                                assertThat(denied.code())
                                        .isEqualTo(
                                                "PROTECTED_PATH_DENIED");
                                assertThat(denied.getMessage())
                                        .contains("UNC path access denied");
                            });
        }
    }

    @Test
    void finalFileRecheckRejectsProtectedSymlinkReplacement()
            throws Exception {
        Path workspace = Files.createDirectory(
                temp.resolve("protected-recheck-workspace"))
                .toRealPath();
        Path protectedFile = Files.writeString(
                workspace.resolve(".env"), "TOKEN=secret");
        Path target = Files.writeString(
                workspace.resolve("target.txt"), "ordinary");
        OperationAnalyzerRegistry registry =
                new OperationAnalyzerRegistry(
                        new ObjectMapper(), safeBash(),
                        new SensitiveDataFilter(),
                        new PathSecurityService(),
                        new ShellStateManager());
        AuthorizationSubject subject =
                new AuthorizationSubject(
                        "s", "run", "run", "wk", workspace);
        Tool read = mock(Tool.class);
        when(read.getName()).thenReturn("Read");
        when(read.getPath(any(ToolInput.class)))
                .thenReturn(target.toString());
        ToolInput input = ToolInput.from(
                java.util.Map.of(
                        "file_path", target.toString()));

        try (FrozenToolInput frozen = frozen(input)) {
            OperationAnalyzer analyzer =
                    registry.analyzerFor(read);
            OperationDescriptor descriptor = analyzer.analyze(
                    read, frozen, input,
                    ToolUseContext.of(workspace.toString(), "s"),
                    subject);
            assertThat(descriptor.risk()).isEqualTo(RiskClass.SAFE);
            Files.delete(target);
            Files.createSymbolicLink(target, protectedFile);

            assertThatThrownBy(() -> analyzer.recheck(
                    read, descriptor, input,
                    ToolUseContext.of(
                            workspace.toString(), "s"),
                    subject))
                    .isInstanceOfSatisfying(
                            AuthorizationException.class,
                            error -> assertThat(error.code())
                                    .isEqualTo(
                                            "AUTHORIZATION_FINAL_RECHECK_DENIED"));
        }
    }

    @Test
    void executionCallRejectsSensitiveReboundAfterFinalDynamicRecheck()
            throws Exception {
        Path workspace = Files.createDirectory(
                temp.resolve("call-rebound-workspace"))
                .toRealPath();
        Path ordinary = Files.writeString(
                workspace.resolve("ordinary.txt"), "ordinary")
                .toRealPath();
        Path sensitive = Files.writeString(
                workspace.resolve(".env.staging"),
                "TOKEN=must-not-leak").toRealPath();
        PathSecurityService paths = org.mockito.Mockito.spy(
                new PathSecurityService());
        OperationAnalyzerRegistry registry =
                new OperationAnalyzerRegistry(
                        new ObjectMapper(), safeBash(),
                        new SensitiveDataFilter(), paths,
                        new ShellStateManager());
        AuthorizationSubject subject = new AuthorizationSubject(
                "s", "run", "run", "wk", workspace);
        ToolUseContext context = ToolUseContext.of(
                workspace.toString(), "s");
        FileReadTool read = fileReadTool(paths);
        ToolInput input = ToolInput.from(Map.of(
                "file_path", ordinary.toString()));

        try (FrozenToolInput frozen = frozen(input)) {
            OperationDescriptor descriptor = registry
                    .analyzerFor(read).analyze(
                            read, frozen, input, context, subject);
            ToolInput bound = registry.bindExecutionInput(
                    read, descriptor, input, subject);
            AuthorizedOperation allowed = new AuthorizedOperation(
                    subject, descriptor, bound,
                    AuthorizationDiagnostic.Source.POLICY,
                    "SAFE_READ_AUTO", null, null,
                    null, "attempt");
            authorizationForFinalRecheck(registry)
                    .finalDynamicRecheck(read, allowed, context);
            org.mockito.Mockito.clearInvocations(paths);

            Files.delete(ordinary);
            Files.createSymbolicLink(ordinary, sensitive);

            ToolResult result = read.call(bound, context);
            assertThat(result.isError()).isTrue();
            assertThat(result.failureCode())
                    .isEqualTo("PATH_OUTSIDE_WORKSPACE");
            assertThat(result.content())
                    .doesNotContain("must-not-leak");
            org.mockito.Mockito.verify(paths,
                    org.mockito.Mockito.times(1)).resolvePath(
                            bound.getString("file_path"),
                            workspace.toString());
        }
    }

    @Test
    void executionCallAllowsUnchangedTargetOriginallyAuthorizedAsHigh()
            throws Exception {
        Path workspace = Files.createDirectory(
                temp.resolve("call-high-workspace"))
                .toRealPath();
        Path sensitive = Files.writeString(
                workspace.resolve(".ENV.Development"),
                "TOKEN=approved").toRealPath();
        PathSecurityService paths = new PathSecurityService();
        OperationAnalyzerRegistry registry =
                new OperationAnalyzerRegistry(
                        new ObjectMapper(), safeBash(),
                        new SensitiveDataFilter(), paths,
                        new ShellStateManager());
        AuthorizationSubject subject = new AuthorizationSubject(
                "s", "run", "run", "wk", workspace);
        ToolUseContext context = ToolUseContext.of(
                workspace.toString(), "s");
        FileReadTool read = fileReadTool(paths);
        ToolInput input = ToolInput.from(Map.of(
                "file_path", sensitive.toString()));

        try (FrozenToolInput frozen = frozen(input)) {
            OperationDescriptor descriptor = registry
                    .analyzerFor(read).analyze(
                            read, frozen, input, context, subject);
            assertThat(descriptor.risk()).isEqualTo(RiskClass.HIGH);
            ToolInput bound = registry.bindExecutionInput(
                    read, descriptor, input, subject);
            AuthorizedOperation allowed = new AuthorizedOperation(
                    subject, descriptor, bound,
                    AuthorizationDiagnostic.Source.USER_ONCE,
                    "USER_ALLOWED_ONCE", null, null,
                    "interaction", "attempt");
            authorizationForFinalRecheck(registry)
                    .finalDynamicRecheck(read, allowed, context);

            ToolResult result = read.call(bound, context);
            assertThat(result.isError()).isFalse();
            assertThat(result.content()).contains("TOKEN=approved");
        }
    }

    @Test
    void finalFileRecheckBindsApprovedCanonicalTargetEvenWhenRiskDecreases()
            throws Exception {
        Path workspace = Files.createDirectory(
                temp.resolve("decreased-recheck-workspace"))
                .toRealPath();
        Path protectedFile = Files.writeString(
                workspace.resolve(".env"), "TOKEN=secret");
        Path target = workspace.resolve("target.txt");
        Files.createSymbolicLink(target, protectedFile);
        OperationAnalyzerRegistry registry =
                new OperationAnalyzerRegistry(
                        new ObjectMapper(), safeBash(),
                        new SensitiveDataFilter(),
                        new PathSecurityService(),
                        new ShellStateManager());
        AuthorizationSubject subject =
                new AuthorizationSubject(
                        "s", "run", "run", "wk", workspace);
        Tool read = mock(Tool.class);
        when(read.getName()).thenReturn("Read");
        when(read.getPath(any(ToolInput.class)))
                .thenReturn(target.toString());
        ToolInput input = ToolInput.from(
                java.util.Map.of(
                        "file_path", target.toString()));

        try (FrozenToolInput frozen = frozen(input)) {
            OperationAnalyzer analyzer =
                    registry.analyzerFor(read);
            OperationDescriptor descriptor = analyzer.analyze(
                    read, frozen, input,
                    ToolUseContext.of(workspace.toString(), "s"),
                    subject);
            assertThat(descriptor.risk()).isEqualTo(RiskClass.HIGH);

            org.assertj.core.api.Assertions.assertThatCode(
                    () -> analyzer.recheck(
                            read, descriptor, input,
                            ToolUseContext.of(
                                    workspace.toString(), "s"),
                            subject))
                    .doesNotThrowAnyException();

            Files.delete(target);
            Files.writeString(target, "ordinary");

            assertThatThrownBy(() -> analyzer.recheck(
                    read, descriptor, input,
                    ToolUseContext.of(
                            workspace.toString(), "s"),
                    subject))
                    .isInstanceOfSatisfying(
                            AuthorizationException.class,
                            error -> assertThat(error.code())
                                    .isEqualTo(
                                            "AUTHORIZATION_FINAL_RECHECK_DENIED"));
        }
    }

    @Test
    void scratchpadSensitiveSymlinkAliasesRemainHighRisk()
            throws Exception {
        Path workspace = Files.createDirectory(
                temp.resolve("scratchpad-alias-workspace"))
                .toRealPath();
        Path scratchpad = Files.createDirectories(
                workspace.resolve(".zhikun/scratchpad"));
        Path ordinaryDirectory = Files.createDirectory(
                scratchpad.resolve("ordinary"));
        Path ordinaryFile = Files.writeString(
                ordinaryDirectory.resolve("notes.txt"), "notes");
        Path envAlias = Files.createSymbolicLink(
                scratchpad.resolve(".envrc"), ordinaryFile);
        Path sshAlias = Files.createSymbolicLink(
                scratchpad.resolve(".ssh"), ordinaryDirectory);

        PathSecurityService paths = new PathSecurityService();
        OperationAnalyzerRegistry registry = new OperationAnalyzerRegistry(
                new ObjectMapper(), safeBash(),
                new SensitiveDataFilter(), paths,
                new ShellStateManager());
        AuthorizationSubject subject = new AuthorizationSubject(
                "s", "run", "run", "wk", workspace);
        ToolUseContext context = ToolUseContext.of(
                workspace.toString(), "s");

        for (String toolName : List.of("Read", "Write")) {
            Tool tool = mock(Tool.class);
            when(tool.getName()).thenReturn(toolName);
            when(tool.getPath(any(ToolInput.class)))
                    .thenAnswer(invocation -> invocation
                            .getArgument(0, ToolInput.class)
                            .getString("file_path"));

            for (Path requested : List.of(
                    envAlias, sshAlias.resolve("notes.txt"))) {
                ToolInput input = ToolInput.from(Map.of(
                        "file_path", requested.toString()));
                try (FrozenToolInput frozen = frozen(input)) {
                    OperationDescriptor descriptor = registry
                            .analyzerFor(tool).analyze(
                                    tool, frozen, input,
                                    context, subject);
                    assertThat(descriptor.risk())
                            .as(toolName + " " + requested)
                            .isEqualTo(RiskClass.HIGH);
                }
            }
        }

        Path ordinaryAlias = Files.createSymbolicLink(
                scratchpad.resolve("notes-link.txt"), ordinaryFile);
        Tool read = mock(Tool.class);
        when(read.getName()).thenReturn("Read");
        when(read.getPath(any(ToolInput.class)))
                .thenReturn(ordinaryAlias.toString());
        ToolInput ordinaryInput = ToolInput.from(Map.of(
                "file_path", ordinaryAlias.toString()));
        try (FrozenToolInput frozen = frozen(ordinaryInput)) {
            assertThat(registry.analyzerFor(read).analyze(
                    read, frozen, ordinaryInput, context, subject).risk())
                    .isEqualTo(RiskClass.SAFE);
        }
    }

    private static OperationAnalyzerRegistry registry(BashSecurityAnalyzer bash) {
        return registry(bash, new ShellStateManager());
    }

    private static AuthorizationService authorizationForFinalRecheck(
            OperationAnalyzerRegistry registry) {
        return new AuthorizationService(
                mock(AuthorizationSubjectResolver.class), registry,
                mock(PermissionGrantRepository.class),
                mock(DurableInteractionService.class),
                mock(PermissionModeManager.class),
                mock(RunControlService.class), new ObjectMapper(),
                mock(ProjectWorkspaceService.class));
    }

    private static FileReadTool fileReadTool(
            PathSecurityService paths) throws Exception {
        SessionManager sessions = mock(SessionManager.class);
        when(sessions.getFileStateCache(anyString()))
                .thenReturn(new FileStateCache());
        EncodingDetector encoding = mock(EncodingDetector.class);
        when(encoding.detectCharset(any(Path.class)))
                .thenReturn(StandardCharsets.UTF_8);
        return new FileReadTool(
                paths, sessions, mock(KeyFileTracker.class), encoding,
                mock(ImageResultExternalizer.class));
    }

    private static OperationAnalyzerRegistry registry(BashSecurityAnalyzer bash, ShellStateManager shellState) {
        PathSecurityService paths = mock(PathSecurityService.class);
        when(paths.checkReadPermission(anyString(), anyString()))
                .thenReturn(PathSecurityService.PathCheckResult.allowed());
        when(paths.checkAuthorizedReadPermission(
                anyString(), anyString()))
                .thenReturn(PathSecurityService.PathCheckResult.allowed());
        when(paths.checkWritePermission(anyString(), anyString()))
                .thenReturn(PathSecurityService.PathCheckResult.allowed());
        when(paths.checkAuthorizedWritePermission(
                anyString(), anyString()))
                .thenReturn(PathSecurityService.PathCheckResult.allowed());
        when(paths.checkRecursiveReadRootPermission(
                anyString(), anyString()))
                .thenReturn(PathSecurityService.PathCheckResult.allowed());
        when(paths.checkAuthorizedRecursiveReadRootPermission(
                anyString(), anyString()))
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
