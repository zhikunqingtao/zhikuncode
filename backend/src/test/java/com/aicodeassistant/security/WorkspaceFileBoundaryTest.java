package com.aicodeassistant.security;

import com.aicodeassistant.engine.KeyFileTracker;
import com.aicodeassistant.history.FileHistoryService;
import com.aicodeassistant.lsp.LSPServerManager;
import com.aicodeassistant.lsp.LSPTool;
import com.aicodeassistant.lsp.LspCallHierarchyService;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import com.aicodeassistant.tool.impl.GlobTool;
import com.aicodeassistant.tool.impl.GrepTool;
import com.aicodeassistant.tool.impl.AtomicFileWriter;
import com.aicodeassistant.tool.impl.FileVersionTracker;
import com.aicodeassistant.tool.impl.FileWriteTool;
import com.aicodeassistant.tool.impl.SnipTool;
import com.aicodeassistant.session.SessionManager;
import com.aicodeassistant.service.FileStateCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class WorkspaceFileBoundaryTest {

    @TempDir
    Path temp;

    @Test
    void allowsInRootPathsAndRejectsAbsoluteOrRelativeEscape()
            throws Exception {
        Path workspace = Files.createDirectory(
                temp.resolve("workspace")).toRealPath();
        Path inside = Files.writeString(
                workspace.resolve("inside.txt"), "ok");
        Path outside = Files.writeString(
                temp.resolve("outside.txt"), "secret");
        PathSecurityService security =
                new PathSecurityService();

        assertThat(security.checkReadPermission(
                inside.toString(), workspace.toString())
                .isAllowed()).isTrue();
        assertThat(security.checkWritePermission(
                workspace.resolve("new.txt").toString(),
                workspace.toString()).isAllowed()).isTrue();
        assertThat(security.checkReadPermission(
                outside.toString(), workspace.toString())
                .isAllowed()).isFalse();
        assertThat(security.checkWritePermission(
                "../outside.txt", workspace.toString())
                .isAllowed()).isFalse();
        assertThat(security.checkAuthorizedReadPermission(
                outside.toString(), workspace.toString())
                .isAllowed()).isTrue();
        assertThat(security.checkAuthorizedWritePermission(
                outside.toString(), workspace.toString())
                .isAllowed()).isTrue();
    }

    @Test
    void rejectsFileAndParentSymlinksThatEscape()
            throws Exception {
        Path workspace = Files.createDirectory(
                temp.resolve("workspace")).toRealPath();
        Path externalDirectory = Files.createDirectory(
                temp.resolve("external")).toRealPath();
        Path externalFile = Files.writeString(
                externalDirectory.resolve("secret.txt"),
                "secret").toRealPath();
        Files.createSymbolicLink(
                workspace.resolve("file-link"), externalFile);
        Files.createSymbolicLink(
                workspace.resolve("dir-link"),
                externalDirectory);
        PathSecurityService security =
                new PathSecurityService();
        ManagedWorkspacePathResolver resolver =
                new ManagedWorkspacePathResolver();

        assertThat(security.checkReadPermission(
                workspace.resolve("file-link").toString(),
                workspace.toString()).isAllowed()).isFalse();
        assertThatThrownBy(() -> resolver.resolveProspective(
                Path.of("dir-link/new.txt"),
                workspace.toString()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(resolver.resolveAuthorizedProspective(
                Path.of("dir-link/new.txt"),
                workspace.toString()))
                .isEqualTo(externalDirectory.resolve("new.txt"));
    }

    @Test
    void rejectsAWorkspaceReplacedBySymlink()
            throws Exception {
        Path savedWorkspace = Files.createDirectory(
                temp.resolve("saved-workspace"))
                .toAbsolutePath().normalize();
        Path replacement = Files.createDirectory(
                temp.resolve("replacement")).toRealPath();
        Path secret = Files.writeString(
                replacement.resolve("secret.txt"), "secret");
        Files.delete(savedWorkspace);
        Files.createSymbolicLink(savedWorkspace, replacement);

        PathSecurityService.PathCheckResult result =
                new PathSecurityService().checkReadPermission(
                        savedWorkspace.resolve("secret.txt").toString(),
                        savedWorkspace.toString());

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.message()).contains(
                "project boundary has changed");
        assertThat(Files.readString(secret)).isEqualTo("secret");
    }

    @Test
    void strictResolverRejectsExternalWhileAuthorizedWriterAndGlobAllowIt()
            throws Exception {
        Path workspace = Files.createDirectory(
                temp.resolve("workspace")).toRealPath();
        Path external = Files.createDirectory(
                temp.resolve("external")).toRealPath();
        ManagedWorkspacePathResolver resolver =
                new ManagedWorkspacePathResolver();

        assertThatThrownBy(() -> resolver.resolveProspective(
                external.resolve("file.txt"),
                workspace.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes workspace");
        assertThat(resolver.resolveAuthorizedProspective(
                external.resolve("file.txt"),
                workspace.toString()))
                .isEqualTo(external.resolve("file.txt"));

        GlobTool glob = new GlobTool(
                new PathSecurityService());
        ToolResult result = glob.call(
                ToolInput.from(Map.of(
                        "pattern", "**/*",
                        "path", external.toString())),
                ToolUseContext.of(
                        workspace.toString(), "session"));
        assertThat(result.isError()).isFalse();
    }

    @Test
    void admittedBuiltinSearchAndCodeReadToolsAcceptExternalPaths()
            throws Exception {
        Path workspace = Files.createDirectory(
                temp.resolve("tool-workspace")).toRealPath();
        Path external = Files.createDirectory(
                temp.resolve("tool-external")).toRealPath();
        Path externalFile = Files.writeString(
                external.resolve("secret.java"),
                "class Secret {}").toRealPath();
        PathSecurityService security =
                new PathSecurityService();
        ToolUseContext context = ToolUseContext.of(
                workspace.toString(), "session");

        ToolResult grep = new GrepTool(
                mock(KeyFileTracker.class), security).call(
                ToolInput.from(Map.of(
                        "pattern", "Secret",
                        "path", external.toString())),
                context);
        ToolResult snip = new SnipTool(security).call(
                ToolInput.from(Map.of(
                        "file_path", externalFile.toString(),
                        "start_line", 1)),
                context);
        ToolResult lsp = new LSPTool(
                mock(LSPServerManager.class),
                mock(LspCallHierarchyService.class),
                security).call(
                ToolInput.from(Map.of(
                        "operation", "documentSymbol",
                        "filePath", externalFile.toString())),
                context);
        ToolResult legacyLsp =
                new com.aicodeassistant.tool.impl.LspTool(
                        mock(com.aicodeassistant.lsp.LspService.class),
                        security).call(
                        ToolInput.from(Map.of(
                                "action", "symbols",
                                "file_path",
                                externalFile.toString())),
                        context);

        assertThat(grep.failureCode())
                .isNotEqualTo("GREP_PATH_DENIED");
        assertThat(snip.failureCode())
                .isNotEqualTo("SNIP_PATH_DENIED");
        assertThat(lsp.failureCode())
                .isNotEqualTo("LSP_PATH_DENIED");
        assertThat(legacyLsp.failureCode())
                .isNotEqualTo("LSP_PATH_DENIED");
    }

    @Test
    void recursiveGrepSkipsProtectedFilesButDirectAccessCanBeAuthorized()
            throws Exception {
        Path workspace = Files.createDirectory(
                temp.resolve("grep-workspace")).toRealPath();
        Files.writeString(workspace.resolve("visible.txt"),
                "MATCH visible");
        Path protectedFile = Files.writeString(
                workspace.resolve(".ENV"), "MATCH secret-token");
        Path protectedDirectory = Files.createDirectory(
                workspace.resolve(".SsH"));
        Files.writeString(protectedDirectory.resolve("custom-key"),
                "MATCH directory-secret");
        Files.writeString(protectedDirectory.resolve(".ENV"),
                "MATCH nested-env-secret");
        Path nestedProtected = Files.createDirectories(
                protectedDirectory.resolve("sub/.SsH"));
        Files.writeString(nestedProtected.resolve("deep-key"),
                "MATCH nested-deep-secret");
        Path colonProtected = Files.createDirectories(
                protectedDirectory.resolve("weird:name/.SsH"));
        Files.writeString(colonProtected.resolve("colon-key"),
                "MATCH colon-path-secret");
        PathSecurityService security = new PathSecurityService();
        GrepTool grep = new GrepTool(
                mock(KeyFileTracker.class), security);
        ToolUseContext context = ToolUseContext.of(
                workspace.toString(), "session");

        ToolResult recursive = grep.call(
                ToolInput.from(Map.of(
                        "pattern", "MATCH",
                        "path", workspace.toString(),
                        "output_mode", "content")),
                context);

        assertThat(recursive.isError()).isFalse();
        assertThat(recursive.content()).contains("visible");
        assertThat(recursive.content()).doesNotContain("secret-token");
        assertThat(recursive.content()).doesNotContain("directory-secret");

        ToolResult attemptedReinclude = grep.call(
                ToolInput.from(Map.of(
                        "pattern", "MATCH",
                        "path", workspace.toString(),
                        "include", ".ENV",
                        "output_mode", "content")),
                context);
        assertThat(attemptedReinclude.content())
                .doesNotContain("secret-token");
        assertThat(security.checkReadPermission(
                protectedFile.toString(), workspace.toString())
                .needsConfirmation()).isTrue();

        assertThat(security.checkRecursiveReadRootPermission(
                workspace.toString(), workspace.toString())
                .needsConfirmation()).isFalse();
        assertThat(security.checkRecursiveReadRootPermission(
                protectedDirectory.toString(), workspace.toString())
                .needsConfirmation()).isTrue();

        ToolResult directProtectedRoot = grep.call(
                ToolInput.from(Map.of(
                        "pattern", "MATCH",
                        "path", protectedDirectory.toString(),
                        "output_mode", "content")),
                context);
        assertThat(directProtectedRoot.isError()).isFalse();
        assertThat(directProtectedRoot.content())
                .contains("directory-secret");
        // Descendant protections stay active inside a directly authorized
        // root: neither the nested .ENV file nor deeper same-name .SsH
        // directories (including one below a colon-named directory) may leak.
        assertThat(directProtectedRoot.content())
                .doesNotContain("nested-env-secret", "nested-deep-secret",
                        "colon-path-secret");
    }

    @Test
    void rootsNamedLikeExcludedDirectoriesRemainDirectlySearchable()
            throws Exception {
        Path workspace = Files.createDirectory(
                temp.resolve("excluded-root-workspace")).toRealPath();
        Path gitObjects = Files.createDirectories(
                workspace.resolve(".git/objects"));
        Files.writeString(gitObjects.resolve("note.txt"),
                "MATCH git-note");
        Path gitInner = Files.createDirectories(
                workspace.resolve(".git/objects/.git"));
        Files.writeString(gitInner.resolve("inner.txt"),
                "MATCH git-inner");
        Path dependency = Files.createDirectories(
                workspace.resolve("node_modules/pkg"));
        Files.writeString(dependency.resolve("index.js"),
                "MATCH dependency-note");
        PathSecurityService security = new PathSecurityService();
        GrepTool grep = new GrepTool(
                mock(KeyFileTracker.class), security);
        ToolUseContext context = ToolUseContext.of(
                workspace.toString(), "session");

        // Direct search roots must never be pruned by the traversal
        // exclusions that still protect descendants (a pruned root used to
        // return an empty result).
        ToolResult gitRoot = grep.call(
                ToolInput.from(Map.of(
                        "pattern", "MATCH",
                        "path", workspace.resolve(".git").toString(),
                        "output_mode", "content")),
                context);
        assertThat(gitRoot.isError()).isFalse();
        assertThat(gitRoot.content()).contains("git-note");
        // A deeper same-name .git directory inside the root stays protected.
        assertThat(gitRoot.content()).doesNotContain("git-inner");

        ToolResult modulesRoot = grep.call(
                ToolInput.from(Map.of(
                        "pattern", "MATCH",
                        "path", workspace.resolve("node_modules").toString(),
                        "output_mode", "content")),
                context);
        assertThat(modulesRoot.isError()).isFalse();
        assertThat(modulesRoot.content()).contains("dependency-note");
    }

    @Test
    void paginationSlicesRestoredSearchRootPaths() throws Exception {
        Path workspace = Files.createDirectory(
                temp.resolve("grep-pagination-workspace")).toRealPath();
        // File traversal order can differ between searches; line order within
        // one file is stable, so it provides a deterministic pagination oracle.
        Path file = Files.writeString(workspace.resolve("note.txt"),
                "MATCH entry-1\nMATCH entry-2\nMATCH entry-3\nMATCH entry-4\n");
        PathSecurityService security = new PathSecurityService();
        GrepTool grep = new GrepTool(
                mock(KeyFileTracker.class), security);
        ToolUseContext context = ToolUseContext.of(
                workspace.toString(), "session");

        ToolResult firstPage = grep.call(
                ToolInput.from(Map.of(
                        "pattern", "MATCH",
                        "path", workspace.toString(),
                        "output_mode", "content",
                        "head_limit", 2)),
                context);
        ToolResult secondPage = grep.call(
                ToolInput.from(Map.of(
                        "pattern", "MATCH",
                        "path", workspace.toString(),
                        "output_mode", "content",
                        "head_limit", 2,
                        "offset", 2)),
                context);

        assertThat(firstPage.isError()).isFalse();
        assertThat(secondPage.isError()).isFalse();
        List<String> firstLines = firstPage.content().lines()
                .filter(line -> line.contains("MATCH")).toList();
        List<String> secondLines = secondPage.content().lines()
                .filter(line -> line.contains("MATCH")).toList();
        assertThat(firstLines).containsExactly(
                file + ":1:MATCH entry-1", file + ":2:MATCH entry-2");
        assertThat(secondLines).containsExactly(
                file + ":3:MATCH entry-3", file + ":4:MATCH entry-4");
        assertThat(firstPage.content()).contains("[Results truncated]");
        assertThat(secondPage.content()).doesNotContain("[Results truncated]");
        assertThat(firstPage.metadata().get("truncated")).isEqualTo(true);
        assertThat(secondPage.metadata().get("truncated")).isEqualTo(false);
    }

    @Test
    void multiFileSearchRestoresAbsolutePathsWithoutAssumingOrder() throws Exception {
        Path workspace = Files.createDirectory(
                temp.resolve("grep-path-workspace")).toRealPath();
        Path searchRoot = Files.createDirectory(workspace.resolve("nested"));
        List<String> expectedPaths = new ArrayList<>();
        for (int index = 1; index <= 4; index++) {
            expectedPaths.add(Files.writeString(
                    searchRoot.resolve("note-" + index + ".txt"),
                    "MATCH entry-" + index).toString());
        }
        GrepTool grep = new GrepTool(
                mock(KeyFileTracker.class), new PathSecurityService());
        ToolResult result = grep.call(
                ToolInput.from(Map.of("pattern", "MATCH", "path", searchRoot.toString())),
                ToolUseContext.of(workspace.toString(), "session"));

        assertThat(result.isError()).isFalse();
        List<String> paths = result.content().lines().toList();
        assertThat(paths).containsExactlyInAnyOrderElementsOf(expectedPaths);
        assertThat(result.metadata().get("filenames")).isEqualTo(paths);
        assertThat(result.metadata().get("numFiles")).isEqualTo(4);
        assertThat(result.metadata().get("truncated")).isEqualTo(false);
    }

    @Test
    void externalBroadSearchSkipsEnvVariantsAndCredentialDirectories()
            throws Exception {
        Path workspace = Files.createDirectory(
                temp.resolve("external-search-workspace"))
                .toRealPath();
        Path external = Files.createDirectory(
                temp.resolve("external-search-root"))
                .toRealPath();
        Files.writeString(external.resolve("visible.txt"),
                "MATCH visible");
        Files.writeString(external.resolve(".env.staging"),
                "MATCH staging-secret");
        Files.writeString(external.resolve(".ENV.Test"),
                "MATCH test-secret");
        Path ssh = Files.createDirectory(
                external.resolve(".ssh"));
        Files.writeString(ssh.resolve("custom-key"),
                "MATCH key-secret");
        PathSecurityService security = new PathSecurityService();
        ToolUseContext context = ToolUseContext.of(
                workspace.toString(), "session");

        ToolResult grep = new GrepTool(
                mock(KeyFileTracker.class), security).call(
                ToolInput.from(Map.of(
                        "pattern", "MATCH",
                        "path", external.toString(),
                        "output_mode", "content")),
                context);
        ToolResult glob = new GlobTool(security).call(
                ToolInput.from(Map.of(
                        "pattern", "**",
                        "path", external.toString())),
                context);

        assertThat(grep.isError()).isFalse();
        assertThat(grep.content()).contains("visible");
        assertThat(grep.content())
                .doesNotContain("staging-secret", "test-secret",
                        "key-secret");
        assertThat(glob.isError()).isFalse();
        assertThat(glob.content()).contains("visible.txt");
        assertThat(glob.content())
                .doesNotContain(".env.staging", ".ENV.Test",
                        "custom-key");
    }

    @Test
    void recursiveGlobSkipsProtectedDescendantsButAllowsApprovedRoot()
            throws Exception {
        Path workspace = Files.createDirectory(
                temp.resolve("glob-workspace")).toRealPath();
        Files.writeString(workspace.resolve("visible.txt"), "visible");
        Files.writeString(workspace.resolve(".ENV"), "secret");
        Path protectedDirectory = Files.createDirectory(
                workspace.resolve(".SsH"));
        Files.writeString(
                protectedDirectory.resolve("custom-key"), "secret");
        PathSecurityService security = new PathSecurityService();
        GlobTool glob = new GlobTool(security);
        ToolUseContext context = ToolUseContext.of(
                workspace.toString(), "session");

        ToolResult broad = glob.call(
                ToolInput.from(Map.of(
                        "pattern", "**",
                        "path", workspace.toString())),
                context);
        assertThat(broad.isError()).isFalse();
        assertThat(broad.content()).contains("visible.txt");
        assertThat(broad.content()).doesNotContain(".ENV");
        assertThat(broad.content()).doesNotContain("custom-key");

        assertThat(security.checkRecursiveReadRootPermission(
                protectedDirectory.toString(), workspace.toString())
                .needsConfirmation()).isTrue();
        ToolResult direct = glob.call(
                ToolInput.from(Map.of(
                        "pattern", "**",
                        "path", protectedDirectory.toString())),
                context);
        assertThat(direct.isError()).isFalse();
        assertThat(direct.content()).contains("custom-key");
    }

    @Test
    void envProductionRequiresConfirmation() throws Exception {
        Path workspace = Files.createDirectory(
                temp.resolve("env-production-workspace")).toRealPath();
        Path envProduction = Files.writeString(
                workspace.resolve(".env.production"), "TOKEN=secret");
        PathSecurityService security = new PathSecurityService();

        assertThat(security.checkReadPermission(
                envProduction.toString(), workspace.toString())
                .needsConfirmation()).isTrue();
        assertThat(security.checkWritePermission(
                envProduction.toString(), workspace.toString())
                .needsConfirmation()).isTrue();

        for (String variant : java.util.List.of(
                ".env.development", ".ENV.Test", ".env.staging")) {
            Path path = Files.writeString(
                    workspace.resolve(variant), "TOKEN=secret");
            assertThat(security.checkReadPermission(
                    path.toString(), workspace.toString())
                    .needsConfirmation()).as(variant).isTrue();
            assertThat(security.checkWritePermission(
                    path.toString(), workspace.toString())
                    .needsConfirmation()).as(variant).isTrue();
        }
    }

    @Test
    void uncPathsAreRejectedBeforeCanonicalResolution()
            throws Exception {
        Path workspace = Files.createDirectory(
                temp.resolve("unc-workspace")).toRealPath();
        PathSecurityService security = new PathSecurityService();

        assertThat(security.checkAuthorizedReadPermission(
                "//attacker.invalid/share/secret.txt",
                workspace.toString()).isAllowed()).isFalse();
        assertThat(security.checkAuthorizedWritePermission(
                "\\\\attacker.invalid\\share\\secret.txt",
                workspace.toString()).isAllowed()).isFalse();
        assertThatThrownBy(() -> security.resolvePath(
                "//attacker.invalid/share/secret.txt",
                workspace.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNC path access denied");
    }

    @Test
    void applicationStateDirectoryRequiresConfirmationForWrites()
            throws Exception {
        Path workspace = Files.createDirectory(
                temp.resolve("state-workspace")).toRealPath();
        Path stateDirectory = Files.createDirectory(
                workspace.resolve(".ai-code-assistant"));
        PathSecurityService security =
                new PathSecurityService();

        PathSecurityService.PathCheckResult result =
                security.checkWritePermission(
                        stateDirectory.resolve("data.db").toString(),
                        workspace.toString());

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.needsConfirmation()).isTrue();
        assertThat(result.message())
                .contains(".ai-code-assistant");
    }

    @Test
    void systemCredentialsAreHighRiskAndKernelPathsStayDenied()
            throws Exception {
        Path workspace = Files.createDirectory(
                temp.resolve("system-path-workspace"))
                .toRealPath();
        PathSecurityService security = new PathSecurityService();

        assertThat(security.checkAuthorizedReadPermission(
                "/etc/shadow", workspace.toString())
                .needsConfirmation()).isTrue();
        assertThat(security.checkAuthorizedReadPermission(
                "/etc/sudoers", workspace.toString())
                .needsConfirmation()).isTrue();
        assertThat(security.checkAuthorizedReadPermission(
                "/proc/kcore", workspace.toString())
                .isAllowed()).isFalse();
        assertThat(security.checkAuthorizedReadPermission(
                "/sys/kernel", workspace.toString())
                .isAllowed()).isFalse();
        assertThat(security.checkAuthorizedReadPermission(
                "/dev/null", workspace.toString())
                .isAllowed()).isFalse();
        assertThat(security.checkAuthorizedReadPermission(
                "/proc/version", workspace.toString())
                .isAllowed()).isTrue();
        assertThat(security.checkAuthorizedRecursiveReadRootPermission(
                workspace.getRoot().toString(), workspace.toString())
                .isAllowed()).isFalse();
    }

    @Test
    void canonicalMacSystemAliasesRetainWriteAndRecursivePolicies()
            throws Exception {
        Path workspace = Files.createDirectory(
                temp.resolve("canonical-system-workspace"))
                .toRealPath();
        PathSecurityService security = new PathSecurityService();

        assertThat(security.checkAuthorizedWritePermission(
                "/etc/zhikun-test.conf", workspace.toString())
                .needsConfirmation()).isTrue();
        assertThat(security.checkAuthorizedWritePermission(
                "/var/zhikun-test/data", workspace.toString())
                .needsConfirmation()).isTrue();
        assertThat(security.checkAuthorizedWritePermission(
                "/private/etc/zhikun-test.conf", workspace.toString())
                .needsConfirmation()).isTrue();
        assertThat(security.checkAuthorizedWritePermission(
                "/private/var/zhikun-test/data", workspace.toString())
                .needsConfirmation()).isTrue();
        assertThat(security.checkAuthorizedRecursiveReadRootPermission(
                "/private/etc", workspace.toString())
                .isAllowed()).isFalse();
        assertThat(security.checkAuthorizedRecursiveReadRootPermission(
                "/etc", workspace.toString())
                .isAllowed()).isFalse();
        assertThat(PathSecurityService.isSystemCriticalPath(
                "/private/var/folders/ab/user/T/ordinary.txt"))
                .isFalse();
    }

    @Test
    void windowsSystemDirectoriesAreMatchedWithNativeSeparatorsAndCase() {
        for (String path : java.util.List.of(
                "C:\\Windows\\System32\\drivers\\etc\\hosts",
                "c:\\program files\\Example\\config.ini",
                "C:\\Program Files (x86)\\Example\\config.ini",
                "C:\\ProgramData\\Example\\config.ini")) {
            assertThat(PathSecurityService.isSystemCriticalPath(path))
                    .as(path).isTrue();
        }

        assertThat(PathSecurityService.isSystemCriticalPath(
                "C:\\WindowsBackup\\ordinary.txt")).isFalse();
        assertThat(PathSecurityService.isSystemCriticalPath(
                "C:\\Program Files-old\\ordinary.txt")).isFalse();
    }

    @Test
    void dependencyDirectoriesAreOrdinaryWritesButControlDirectoriesAreNot()
            throws Exception {
        Path workspace = Files.createDirectory(
                temp.resolve("write-directory-workspace"))
                .toRealPath();
        PathSecurityService security = new PathSecurityService();

        for (String directory : java.util.List.of(
                "node_modules", ".local")) {
            Path target = workspace.resolve(directory)
                    .resolve("ordinary.txt");
            assertThat(security.checkWritePermission(
                    target.toString(), workspace.toString())
                    .needsConfirmation()).as(directory).isFalse();
        }
        for (String directory : java.util.List.of(
                ".git", ".vscode", ".idea", ".ssh")) {
            Path target = workspace.resolve(directory)
                    .resolve("control.txt");
            assertThat(security.checkWritePermission(
                    target.toString(), workspace.toString())
                    .needsConfirmation()).as(directory).isTrue();
        }
    }

    @Test
    void admittedWriteUpdatesExternalTargetWithoutRecordingUnusableHistory()
            throws Exception {
        Path workspace = Files.createDirectory(
                temp.resolve("write-workspace")).toRealPath();
        Path external = Files.writeString(
                temp.resolve("write-external.txt"), "secret")
                .toRealPath();
        FileVersionTracker tracker = new FileVersionTracker();
        ManagedWorkspacePathResolver resolver =
                new ManagedWorkspacePathResolver();
        AtomicFileWriter atomicWriter = new AtomicFileWriter(
                tracker, new PathSecurityService(), resolver);
        FileHistoryService history = mock(FileHistoryService.class);
        SessionManager sessions = mock(SessionManager.class);
        org.mockito.Mockito.when(sessions.getFileStateCache("session"))
                .thenReturn(mock(FileStateCache.class));
        FileWriteTool write = new FileWriteTool(
                history, sessions, tracker,
                atomicWriter,
                resolver);

        ToolResult result = write.call(
                ToolInput.from(Map.of(
                        "file_path", external.toString(),
                        "content", "replacement")),
                ToolUseContext.of(
                        workspace.toString(), "session"));

        assertThat(result.isError()).isFalse();
        assertThat(Files.readString(external))
                .isEqualTo("replacement");
        assertThat(result.metadata().get("historyRecorded"))
                .isEqualTo(false);
        assertThat(result.metadata().get("historyErrorCode"))
                .isEqualTo("OUTSIDE_AUTHORIZED_RESOURCE");
        verifyNoInteractions(history);
    }
}
