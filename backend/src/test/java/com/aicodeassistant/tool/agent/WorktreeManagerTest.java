package com.aicodeassistant.tool.agent;

import com.aicodeassistant.authorization.AuthorizationSubject;
import com.aicodeassistant.authorization.AuthorizationSubjectResolver;
import com.aicodeassistant.service.GitService;
import com.aicodeassistant.engine.AbortContext;
import com.aicodeassistant.run.RunExecutionRegistry;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.impl.WorktreeTool;
import com.aicodeassistant.tool.ToolUseContext;
import com.aicodeassistant.tool.process.ManagedProcessRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;

class WorktreeManagerTest {
    @TempDir Path temp;
    Path root;
    AuthorizationSubjectResolver subjects;
    ManagedProcessRunner runner;
    RunExecutionRegistry runs;
    WorktreeManager manager;
    ToolUseContext context;
    final List<Path> fixtureWorktrees = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        root = repository("project");
        subjects = mock(AuthorizationSubjectResolver.class);
        when(subjects.resolve("root-run")).thenReturn(new AuthorizationSubject(
                "session", "root-run", "root-run", "workspace", root));
        runs = spy(new RunExecutionRegistry());
        runs.register("root-run", "session", new AbortContext());
        runs.register("caller-run", "caller", new AbortContext());
        runner = spy(new ManagedProcessRunner(runs));
        manager = new WorktreeManager(subjects, new GitService(), runner);
        context = ToolUseContext.of(root.toString(), "session").withCurrentRunId("root-run");
    }

    @AfterEach
    void removeOnlyTestOwnedFixtures() throws Exception {
        // Deliberately destructive fixture teardown, never used by production cleanup.
        for (Path path : fixtureWorktrees) {
            if (Files.exists(path)) git(root, "worktree", "remove", "--force", path.toString());
        }
    }

    @Test
    void bindsTrustedProjectAndMapsSubdirectoryWithoutCopyingParentDirtyContent() throws Exception {
        Files.createDirectories(root.resolve("backend"));
        Files.writeString(root.resolve("backend/base.txt"), "committed");
        git(root, "add", "."); git(root, "commit", "-qm", "backend");
        Files.writeString(root.resolve("backend/base.txt"), "parent dirty");
        Files.writeString(root.resolve("parent-untracked"), "must not copy");
        var tree = create(context.withWorkingDirectory(root.resolve("backend").toString()), false);
        assertThat(tree.workingDirectory()).isEqualTo(tree.path().resolve("backend"));
        assertThat(Files.readString(tree.workingDirectory().resolve("base.txt"))).isEqualTo("committed");
        assertThat(tree.path().resolve("parent-untracked")).doesNotExist();
        assertThat(tree.warning()).contains("not copied");
        assertThat(manager.finishWorktree(tree.path(), true).success()).isTrue();
        assertThat(tree.path()).doesNotExist();
        assertThat(Files.readString(root.resolve("backend/base.txt"))).isEqualTo("parent dirty");
        assertThat(Files.readString(root.resolve("parent-untracked"))).isEqualTo("must not copy");
    }

    @Test
    void selectedSubdirectoryIsNotSilentlyPromotedToParentRepository() throws Exception {
        Path sub = Files.createDirectory(root.resolve("selected-sub"));
        when(subjects.resolve("root-run")).thenReturn(new AuthorizationSubject(
                "session", "root-run", "root-run", "sub", sub));
        assertThatThrownBy(() -> manager.createWorktree("agent", context.withWorkingDirectory(sub.toString()), true))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no parent fallback");
        assertThat(manager.getActiveCount()).isZero();
    }

    @Test
    void committedCleanWorkIsDeliveredAndNormalCleanupRemovesOnlyItsResources() throws Exception {
        var tree = create(context, false);
        Files.writeString(tree.path().resolve("result.txt"), "committed agent result");
        git(tree.path(), "add", "."); git(tree.path(), "commit", "-qm", "agent commit");
        String tip = git(tree.path(), "rev-parse", "HEAD").strip();
        assertThat(manager.inspectPendingDelivery(tree.path())).isEqualTo(WorktreeManager.PendingDelivery.PENDING);
        var result = manager.finishWorktree(tree.path(), true);
        assertThat(result.success()).isTrue();
        assertThat(result.cleanupWarning()).isEmpty();
        assertThat(Files.readString(root.resolve("result.txt"))).isEqualTo("committed agent result");
        assertThat(git(root, "rev-parse", "HEAD").strip()).isEqualTo(tip);
        assertThat(tree.path()).doesNotExist();
        assertThat(git(root, "branch", "--list", tree.branch())).isBlank();
        assertThat(manager.getActiveCount()).isZero();
        manager.setWorkerActive(tree.path(), false); // late resource release is idempotent
        AtomicBoolean callback = new AtomicBoolean();
        manager.whenTargetSettled(tree.path(), () -> callback.set(true));
        assertThat(callback).isTrue();
    }

    @Test
    void unstagedAndMixedResultsAreCommittedOnceThenDeliveredByThreeWayMerge() throws Exception {
        var first = create(context, false);
        var second = create(context, false);
        Files.writeString(first.path().resolve("first.txt"), "one");
        Files.writeString(second.path().resolve("second.txt"), "two committed");
        git(second.path(), "add", "."); git(second.path(), "commit", "-qm", "second part 1");
        Files.writeString(second.path().resolve("extra.txt"), "two uncommitted");
        assertThat(manager.finishWorktree(first.path(), true).success()).isTrue();
        var result = manager.finishWorktree(second.path(), true);
        assertThat(result.success()).isTrue();
        assertThat(result.cleanupWarning()).isEmpty();
        assertThat(root.resolve("first.txt")).exists();
        assertThat(root.resolve("second.txt")).exists();
        assertThat(root.resolve("extra.txt")).exists();
        assertThat(git(root, "rev-list", "--parents", "-n", "1", "HEAD").strip().split(" ")).hasSize(3);
    }

    @Test
    void cleanNoChangeTreeDoesNotCreateCommitAndManualEmptyTreeCanBeRemoved() throws Exception {
        String before = git(root, "rev-parse", "HEAD");
        var tree = create(context, true);
        assertThat(manager.inspectPendingDelivery(tree.path())).isEqualTo(WorktreeManager.PendingDelivery.NONE);
        assertThat(manager.removeWorktree(tree.path(), context).success()).isTrue();
        assertThat(git(root, "rev-parse", "HEAD")).isEqualTo(before);
        assertThat(tree.path()).doesNotExist();
    }

    @ParameterizedTest
    @ValueSource(strings = {"session", "caller"})
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void manualRemovePreservesCreatorOrCallerBackgroundServiceUntilItStops(String backgroundSession) throws Exception {
        var tree = create(context, true);
        when(subjects.resolve("caller-run")).thenReturn(new AuthorizationSubject(
                "caller", "caller-run", "caller-run", "workspace", root));
        ToolUseContext caller = ToolUseContext.of(root.toString(), "caller").withCurrentRunId("caller-run");
        String backgroundRun = backgroundSession.equals("session") ? "root-run" : "caller-run";
        try {
            runner.startBackground(new ManagedProcessRunner.BackgroundRequest(
                    List.of("sh", "-c", "exec sleep 30"), tree.path(),
                    backgroundRun, "fixture-background", backgroundSession));
            assertThat(runner.currentSessionBackground(backgroundSession).allTerminated()).isFalse();

            var retained = manager.removeWorktree(tree.path(), caller);
            assertThat(retained.success()).isFalse();
            assertThat(retained.summary()).contains("background work", "occupancy cannot be excluded");
            assertThat(tree.path()).isDirectory();
            assertThat(git(root, "branch", "--list", tree.branch())).isNotBlank();
            assertThat(runner.currentSessionBackground(backgroundSession).allTerminated()).isFalse();

            assertThat(runner.cancelSessionBackground(backgroundSession).allTerminated()).isTrue();
            var removed = manager.removeWorktree(tree.path(), caller);
            assertThat(removed.success()).as(removed.summary()).isTrue();
            assertThat(removed.cleanupWarning()).isEmpty();
            assertThat(tree.path()).doesNotExist();
        } finally {
            // Only this test's runner/session owns this fixture process.
            assertThat(runner.cancelSessionBackground(backgroundSession).allTerminated()).isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"creator", "creator-next", "caller", "unrelated"})
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void manualToolRemovePreservesRelatedForegroundAndAllowsCleanupAfterExit(String owner) throws Exception {
        var tree = create(context, true);
        ToolUseContext caller = callerContext();
        String runId = switch (owner) {
            case "creator-next" -> {
                runs.unregister("root-run");
                runs.register("creator-next-run", "session", new AbortContext());
                yield "creator-next-run";
            }
            case "caller" -> "caller-run";
            case "unrelated" -> {
                runs.register("unrelated-run", "other-session", new AbortContext());
                yield "unrelated-run";
            }
            default -> "root-run";
        };
        Path ready = temp.resolve("foreground-ready.fifo");
        Path release = temp.resolve("foreground-release.fifo");
        Process fifo = new ProcessBuilder("mkfifo", ready.toString(), release.toString()).start();
        assertThat(fifo.waitFor(5, TimeUnit.SECONDS)).isTrue();
        assertThat(fifo.exitValue()).isZero();
        Path survived = temp.resolve("foreground-survived");
        String script = "printf r > " + quote(ready) + "; IFS= read -r token < " + quote(release)
                + "; printf survived > " + quote(survived);
        WorktreeTool tool = new WorktreeTool(manager);
        ToolInput input = ToolInput.from(Map.of("subcommand", "remove", "path", tree.path().toString()));
        // The caller's tool-session lease remains live: remove must not demand
        // Run quiescence or close its admission just to observe process ownership.
        try (var callerLease = runs.acquireWork("caller-run", "tool-session", "remove");
             var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            try (var readyChannel = FileChannel.open(ready, StandardOpenOption.READ, StandardOpenOption.WRITE);
                 var releaseChannel = FileChannel.open(release, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                var readySignal = pool.submit(() -> readyChannel.read(ByteBuffer.allocate(1)));
                Path cwd = owner.equals("unrelated") ? root : tree.path();
                var running = pool.submit(() -> runner.run(new ManagedProcessRunner.Request(
                        List.of("bash", "-c", script), cwd, Duration.ofSeconds(45), runId, "foreground")));
                try {
                    assertThat(readySignal.get(5, TimeUnit.SECONDS)).isEqualTo(1);
                    assertThat(runner.currentRunTermination(runId).allTerminated()).isFalse();
                    assertThat(tool.validateInput(input, caller).isValid()).isTrue();
                    var first = tool.call(input, caller);
                    if (owner.equals("unrelated")) {
                        assertThat(first.isError()).as(first.content()).isFalse();
                        assertThat(tree.path()).doesNotExist();
                    } else {
                        assertThat(first.isError()).isTrue();
                        assertThat(first.content()).contains("foreground", "occupancy");
                        assertThat(tree.path()).isDirectory();
                        assertThat(git(root, "branch", "--list", tree.branch())).isNotBlank();
                    }
                    assertThat(running.isDone()).isFalse();
                    assertThat(runner.currentRunTermination(runId).allTerminated()).isFalse();
                    releaseChannel.write(ByteBuffer.wrap("go\n".getBytes()));
                    assertThat(running.get(10, TimeUnit.SECONDS).exitCode()).isZero();
                    assertThat(Files.readString(survived)).isEqualTo("survived");
                    if (!owner.equals("unrelated")) {
                        var removed = tool.call(input, caller);
                        assertThat(removed.isError()).as(removed.content()).isFalse();
                        assertThat(tree.path()).doesNotExist();
                    }
                    try (var admitted = runs.acquireWork("caller-run", "fixture", "after-remove")) {
                        assertThat(admitted).isNotNull();
                    }
                } finally {
                    releaseChannel.write(ByteBuffer.wrap("go\n".getBytes()));
                    running.get(10, TimeUnit.SECONDS);
                }
            }
        }
        verify(runs, never()).beginCompletion(any());
        verify(runs, never()).beginTermination(any());
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void manualRemoveRetainsCallerForegroundCleanupResidueUntilConfirmed() throws Exception {
        var tree = create(context, true);
        ToolUseContext caller = callerContext();
        AtomicBoolean stopped = new AtomicBoolean();
        try {
            var result = runner.run(new ManagedProcessRunner.Request(List.of("true"), root,
                    Duration.ofSeconds(3), "caller-run", "residue", deadline -> stopped.get()));
            assertThat(result.terminationConfirmed()).isFalse();
            var retained = manager.removeWorktree(tree.path(), caller);
            assertThat(retained.success()).isFalse();
            assertThat(retained.summary()).contains("foreground work");
            assertThat(tree.path()).isDirectory();
            stopped.set(true);
            assertThat(runner.cancelRunDetailed("caller-run").allTerminated()).isTrue();
            assertThat(manager.removeWorktree(tree.path(), caller).success()).isTrue();
        } finally {
            stopped.set(true);
            runner.cancelRunDetailed("caller-run");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"root-run", "caller-run"})
    void manualRemoveChecksSavedRunEvenWhenCurrentSessionLookupIsIdle(String savedRun) {
        var tree = create(context, true);
        ToolUseContext caller = callerContext();
        doReturn(new ManagedProcessRunner.CancelSummary(1, 0, 1))
                .when(runner).currentRunTermination(savedRun);
        doReturn(new ManagedProcessRunner.SessionForegroundSnapshot(null,
                new ManagedProcessRunner.CancelSummary(0, 0, 0)))
                .when(runner).currentSessionForeground(any());
        assertThat(manager.removeWorktree(tree.path(), caller).success()).isFalse();
        assertThat(tree.path()).isDirectory();
    }

    @ParameterizedTest
    @ValueSource(strings = {"unavailable", "null-snapshot", "mapping-race"})
    void manualRemoveRetainsWhenForegroundInspectionIsUnknown(String failure) {
        var tree = create(context, true);
        if (failure.equals("unavailable")) {
            when(runner.currentSessionForeground("session")).thenThrow(new IllegalStateException("fixture unavailable"));
        } else if (failure.equals("null-snapshot")) {
            doReturn(null).when(runner).currentSessionForeground("session");
        } else {
            when(runs.activeRunForSession("session")).thenReturn(
                    java.util.Optional.of("root-run"), java.util.Optional.of("changed-run"));
        }
        var retained = manager.removeWorktree(tree.path(), context);
        assertThat(retained.success()).isFalse();
        assertThat(retained.summary()).contains("foreground");
        assertThat(tree.path()).isDirectory();
    }

    @Test
    void manualRemoveRechecksMappingImmediatelyBeforeDirectoryDeletion() throws Exception {
        var tree = create(context, true);
        AtomicBoolean checkedInitially = new AtomicBoolean();
        AtomicBoolean mappingChanged = new AtomicBoolean();
        doAnswer(call -> {
            var result = call.callRealMethod();
            checkedInitially.set(true);
            return result;
        }).when(runner).currentSessionForeground("session");
        doAnswer(call -> {
            if (checkedInitially.get() && mappingChanged.compareAndSet(false, true)) {
                runs.unregister("root-run");
                runs.register("next-run", "session", new AbortContext());
            }
            return call.callRealMethod();
        }).when(runner).runRawGit(any());
        var result = new WorktreeTool(manager).call(
                ToolInput.from(Map.of("subcommand", "remove", "path", tree.path().toString())), context);
        assertThat(mappingChanged).isTrue();
        assertThat(result.isError()).isTrue();
        assertThat(result.content()).contains("Run mapping changed");
        assertThat(tree.path()).isDirectory();
        assertThat(git(root, "branch", "--list", tree.branch())).isNotBlank();
        assertThat(manager.removeWorktree(tree.path(), context).success()).isTrue();
    }

    private ToolUseContext callerContext() {
        when(subjects.resolve("caller-run")).thenReturn(new AuthorizationSubject(
                "caller", "caller-run", "caller-run", "workspace", root));
        return ToolUseContext.of(root.toString(), "caller").withCurrentRunId("caller-run");
    }

    private static String quote(Path path) {
        return "'" + path.toString().replace("'", "'\"'\"'") + "'";
    }

    @Test
    void manualRemoveRetainsWorktreeWhenBackgroundInspectionFails() throws Exception {
        var tree = create(context, true);
        when(runner.currentSessionBackground("session")).thenThrow(new IllegalStateException("fixture inspection failure"));
        var retained = manager.removeWorktree(tree.path(), context);
        assertThat(retained.success()).isFalse();
        assertThat(retained.summary()).contains("occupancy could not be checked");
        assertThat(tree.path()).isDirectory();
        assertThat(git(root, "branch", "--list", tree.branch())).isNotBlank();
    }

    @Test
    void stoppedSubAgentDeliveryIsNotBlockedByParentSessionBackgroundWork() {
        var tree = create(context, false);
        when(runner.currentSessionBackground("session"))
                .thenReturn(new ManagedProcessRunner.CancelSummary(1, 0, 1));
        var delivered = manager.finishWorktree(tree.path(), true);
        assertThat(delivered.success()).as(delivered.summary()).isTrue();
        assertThat(delivered.cleanupWarning()).isEmpty();
        assertThat(tree.path()).doesNotExist();
    }

    @Test
    void explicitRemoveRejectsActiveUndeliveredUnknownAndWrongProjectTrees() throws Exception {
        var tree = create(context, false);
        assertThat(manager.removeWorktree(tree.path(), context).success()).isFalse();
        manager.setWorkerActive(tree.path(), false);
        Files.writeString(tree.path().resolve("retained.txt"), "not delivered");
        assertThat(manager.removeWorktree(tree.path(), context).success()).isFalse();
        assertThat(tree.path()).exists();
        assertThatThrownBy(() -> manager.removeWorktree(temp.resolve("unknown"), context))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unknown");
        Path other = repository("other");
        when(subjects.resolve("other-run")).thenReturn(new AuthorizationSubject("other", "other-run", "other-run", "other", other));
        assertThatThrownBy(() -> manager.removeWorktree(tree.path(),
                ToolUseContext.of(other.toString(), "other").withCurrentRunId("other-run")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("different project");
    }

    @Test
    void switchedTargetAndPreexistingTargetChangesArePreserved() throws Exception {
        var tree = create(context, false);
        Files.writeString(tree.path().resolve("result.txt"), "agent");
        git(root, "checkout", "-qb", "other-target");
        Files.writeString(root.resolve("user-file"), "user");
        var result = manager.finishWorktree(tree.path(), true);
        assertThat(result.success()).isFalse();
        assertThat(result.targetMayHaveChanged()).isFalse();
        assertThat(git(root, "branch", "--show-current").strip()).isEqualTo("other-target");
        assertThat(root.resolve("result.txt")).doesNotExist();
        assertThat(Files.readString(root.resolve("user-file"))).isEqualTo("user");
        assertThat(tree.path()).exists();
    }

    @Test
    void ownConflictIsAbortedButAgentCommitAndBranchRemain() throws Exception {
        var tree = create(context, false);
        Files.writeString(tree.path().resolve("base.txt"), "agent ?? conflict\n");
        Files.writeString(root.resolve("base.txt"), "target ?? conflict\n");
        git(root, "add", "."); git(root, "commit", "-qm", "target advance");
        String target = git(root, "rev-parse", "HEAD");
        var result = manager.finishWorktree(tree.path(), true);
        assertThat(result.success()).isFalse();
        assertThat(result.targetMayHaveChanged()).isTrue();
        assertThat(result.summary()).contains("aborted");
        assertThat(git(root, "rev-parse", "HEAD")).isEqualTo(target);
        assertThat(git(root, "status", "--porcelain")).isEmpty();
        assertThat(root.resolve(".git/MERGE_HEAD")).doesNotExist();
        assertThat(tree.path()).exists();
        assertThat(git(root, "branch", "--list", tree.branch())).isNotBlank();
    }

    @Test
    void preexistingGitOperationAndChangedAgentBranchAreNeverCleaned() throws Exception {
        var tree = create(context, false);
        String before = git(root, "rev-parse", "HEAD").strip();
        Files.writeString(root.resolve(".git/MERGE_HEAD"), before + "\n");
        assertThat(manager.finishWorktree(tree.path(), true).success()).isFalse();
        assertThat(root.resolve(".git/MERGE_HEAD")).exists();
        Files.delete(root.resolve(".git/MERGE_HEAD"));
        var changed = create(context, false);
        git(changed.path(), "checkout", "-qb", "unexpected-agent-branch");
        assertThat(manager.finishWorktree(changed.path(), true).success()).isFalse();
        assertThat(changed.path()).exists();
        assertThat(git(changed.path(), "branch", "--show-current").strip()).isEqualTo("unexpected-agent-branch");
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void rejectedCommitHookRetainsFilesAndNeverMergesOrRetries() throws Exception {
        Path hooks = root.resolve(".git/hooks");
        Files.createDirectories(hooks);
        Path hook = hooks.resolve("pre-commit");
        Files.writeString(hook, "#!/bin/sh\necho called >> '" + temp.resolve("hook-count") + "'\nexit 1\n");
        assertThat(hook.toFile().setExecutable(true)).isTrue();
        git(root, "config", "core.hooksPath", hooks.toString());
        var tree = create(context, false);
        Files.writeString(tree.path().resolve("result.txt"), "agent");
        var result = manager.finishWorktree(tree.path(), true);
        assertThat(result.success()).isFalse();
        assertThat(result.targetMayHaveChanged()).isFalse();
        assertThat(tree.path().resolve("result.txt")).exists();
        assertThat(root.resolve("result.txt")).doesNotExist();
        assertThat(Files.readAllLines(temp.resolve("hook-count"))).hasSize(1);
        assertThat(manager.finishWorktree(tree.path(), true).success()).isFalse();
        assertThat(Files.readAllLines(temp.resolve("hook-count"))).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void statusFailureOrTruncationIsUnknownAndCannotDeleteArtifacts(boolean truncated) throws Exception {
        var tree = create(context, false);
        Files.writeString(tree.path().resolve("result.txt"), "agent");
        doAnswer(invocation -> {
            ManagedProcessRunner.Request request = invocation.getArgument(0);
            if (request.command().contains("status")) return result(truncated ? 0 : 128, "", truncated, true);
            return invocation.callRealMethod();
        }).when(runner).runRawGit(any());
        assertThat(manager.inspectPendingDelivery(tree.path())).isEqualTo(WorktreeManager.PendingDelivery.UNKNOWN);
        assertThat(manager.finishWorktree(tree.path(), true).success()).isFalse();
        assertThat(tree.path().resolve("result.txt")).exists();
        assertThat(root.resolve("result.txt")).doesNotExist();
    }

    @Test
    void unconfirmedWorkerGitScopeBlocksSameTargetUntilReadOnlyStopConfirmation() throws Exception {
        var first = create(context, false);
        var second = create(context, false);
        Files.writeString(first.path().resolve("first.txt"), "one");
        AtomicBoolean inject = new AtomicBoolean(true);
        AtomicBoolean stopped = new AtomicBoolean();
        doAnswer(invocation -> {
            ManagedProcessRunner.Request request = invocation.getArgument(0);
            if (inject.get() && request.workingDirectory().equals(first.path()) && request.command().contains("status")) {
                inject.set(false);
                return result(0, "", false, false);
            }
            return invocation.callRealMethod();
        }).when(runner).runRawGit(any());
        when(runner.currentGitOperation(any())).thenAnswer(i ->
                new ManagedProcessRunner.CancelSummary(1, stopped.get() ? 1 : 0, stopped.get() ? 0 : 1));
        assertThat(manager.finishWorktree(first.path(), true).success()).isFalse();
        assertThat(manager.inspectPendingDelivery(second.path())).isEqualTo(WorktreeManager.PendingDelivery.UNKNOWN);
        assertThatThrownBy(() -> manager.listWorktrees(context)).hasMessageContaining("has not stopped");
        AtomicBoolean invalidated = new AtomicBoolean();
        manager.whenTargetSettled(first.path(), () -> invalidated.set(true));
        stopped.set(true);
        assertThat(manager.inspectPendingDelivery(second.path())).isEqualTo(WorktreeManager.PendingDelivery.NONE);
        assertThat(invalidated).isTrue();
        assertThat(first.path().resolve("first.txt")).exists();
        assertThat(root.resolve("first.txt")).doesNotExist();
    }

    @Test
    void runtimeTransportFailureWithLiveScopeBlocksEverySubsequentTargetOperation() throws Exception {
        var first = create(context, false);
        var second = create(context, false);
        Files.writeString(first.path().resolve("first.txt"), "keep first");
        Files.writeString(second.path().resolve("second.txt"), "keep second");
        AtomicReference<String> retainedOwner = new AtomicReference<>();
        AtomicInteger gitCalls = new AtomicInteger();
        doAnswer(invocation -> {
            ManagedProcessRunner.Request request = invocation.getArgument(0);
            gitCalls.incrementAndGet();
            if (request.workingDirectory().equals(first.path()) && request.command().contains("status")) {
                retainedOwner.set(request.runId());
                throw new IllegalStateException("fixture reader setup failed after process launch");
            }
            return invocation.callRealMethod();
        }).when(runner).runRawGit(any());
        doAnswer(invocation -> {
            String owner = invocation.getArgument(0);
            return owner.equals(retainedOwner.get())
                    ? new ManagedProcessRunner.CancelSummary(1, 0, 1)
                    : new ManagedProcessRunner.CancelSummary(0, 0, 0);
        }).when(runner).currentGitOperation(any());
        var failed = manager.finishWorktree(first.path(), true);
        assertThat(failed.success()).isFalse();
        assertThat(failed.summary()).contains("reader setup failed");
        assertThat(retainedOwner.get()).startsWith("worktree-git-");
        int callsAtFailure = gitCalls.get();
        assertThatThrownBy(() -> manager.listWorktrees(context)).hasMessageContaining("has not stopped");
        assertThatThrownBy(() -> manager.createWorktree("blocked", context, true)).hasMessageContaining("has not stopped");
        assertThat(manager.finishWorktree(second.path(), true).success()).isFalse();
        assertThat(manager.removeWorktree(first.path(), context).success()).isFalse();
        assertThat(gitCalls.get()).isEqualTo(callsAtFailure);
        assertThat(first.path().resolve("first.txt")).exists();
        assertThat(second.path().resolve("second.txt")).exists();
        assertThat(root.resolve("first.txt")).doesNotExist();
        assertThat(root.resolve("second.txt")).doesNotExist();
    }

    @Test
    void cleanupFailureKeepsCompletedDeliveryAndDoesNotRecommit() throws Exception {
        var tree = create(context, false);
        Files.writeString(tree.path().resolve("result.txt"), "one");
        doAnswer(invocation -> {
            ManagedProcessRunner.Request request = invocation.getArgument(0);
            if (request.command().contains("worktree") && request.command().contains("remove")) return result(1, "", false, true);
            return invocation.callRealMethod();
        }).when(runner).runRawGit(any());
        var delivered = manager.finishWorktree(tree.path(), true);
        assertThat(delivered.success()).isTrue();
        assertThat(delivered.cleanupWarning()).isNotEmpty();
        assertThat(delivered.summary()).contains("do not rerun");
        assertThat(tree.path()).exists();
        String target = git(root, "rev-parse", "HEAD");
        assertThat(manager.finishWorktree(tree.path(), true).success()).isTrue();
        assertThat(git(root, "rev-parse", "HEAD")).isEqualTo(target);
        assertThat(manager.getActiveCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "fixture cleanup failure")
    void cleanupExceptionKeepsWarningAndListAvailableWithoutReportingRemovalSuccess(String message) throws Exception {
        var tree = create(context, false);
        Files.writeString(tree.path().resolve("result.txt"), "delivered");
        AtomicBoolean rejectRemoval = new AtomicBoolean(true);
        doAnswer(invocation -> {
            ManagedProcessRunner.Request request = invocation.getArgument(0);
            if (rejectRemoval.get() && request.command().contains("worktree") && request.command().contains("remove")) {
                throw new IllegalStateException(message);
            }
            return invocation.callRealMethod();
        }).when(runner).runRawGit(any());

        var delivered = manager.finishWorktree(tree.path(), true);
        String expected = message == null ? "Cleanup failed without an error message" : message;
        assertThat(delivered.success()).isTrue();
        assertThat(delivered.cleanupWarning()).isEqualTo(expected);
        assertThat(Files.readString(root.resolve("result.txt"))).isEqualTo("delivered");
        assertThat(tree.path()).isDirectory();
        String target = git(root, "rev-parse", "HEAD");
        WorktreeTool tool = new WorktreeTool(manager);
        var listing = tool.call(new ToolInput(Map.of("subcommand", "list")), context);
        assertThat(listing.isError()).as(listing.content()).isFalse();
        assertThat(listing.content()).contains(tree.path().toString(), "cleanup-warning", expected);
        ToolInput remove = new ToolInput(Map.of("subcommand", "remove", "path", tree.path().toString()));
        var incomplete = tool.call(remove, context);
        assertThat(incomplete.isError()).isTrue();
        assertThat(incomplete.failureCode()).isEqualTo("WORKTREE_REMOVE_INCOMPLETE");
        assertThat(incomplete.content()).contains(expected);
        assertThat(tree.path()).isDirectory();

        rejectRemoval.set(false);
        var removed = tool.call(remove, context);
        assertThat(removed.isError()).as(removed.content()).isFalse();
        assertThat(tree.path()).doesNotExist();
        assertThat(git(root, "rev-parse", "HEAD")).isEqualTo(target);
    }

    @Test
    void nullStoppedProofNeverCommitsOrDeletes() throws Exception {
        var tree = create(context, false);
        Files.writeString(tree.path().resolve("result.txt"), "one");
        assertThat(manager.finishWorktree(tree.path(), false).success()).isFalse();
        assertThat(root.resolve("result.txt")).doesNotExist();
        assertThat(tree.path()).exists();
    }

    @Test
    void unicodeWhitespaceInRefCannotBeStrippedIntoAnotherTargetIdentity() throws Exception {
        git(root, "checkout", "-qb", "topic\u3000");
        var tree = create(context, false);
        Files.writeString(tree.path().resolve("result.txt"), "agent");
        git(root, "checkout", "-qb", "topic"); // same baseline, different exact ref
        var result = manager.finishWorktree(tree.path(), true);
        assertThat(result.success()).isFalse();
        assertThat(result.targetMayHaveChanged()).isFalse();
        assertThat(root.resolve("result.txt")).doesNotExist();
        assertThat(tree.path()).exists();
        assertThat(git(root, "symbolic-ref", "HEAD")).isEqualTo("refs/heads/topic\n");
    }

    @Test
    void partiallySuccessfulCreationKeepsRecoveryRecordWithoutInventingActiveWorker() throws Exception {
        AtomicReference<Path> created = new AtomicReference<>();
        doAnswer(invocation -> {
            ManagedProcessRunner.Request request = invocation.getArgument(0);
            var actual = (ManagedProcessRunner.Result) invocation.callRealMethod();
            if (request.command().contains("worktree") && request.command().contains("add")) {
                Path path = Path.of(request.command().get(request.command().size() - 2));
                created.set(path); fixtureWorktrees.add(path);
                return result(0, actual.stdout(), true, true);
            }
            return actual;
        }).when(runner).runRawGit(any());
        assertThatThrownBy(() -> manager.createWorktree("partial", context, false))
                .hasMessageContaining("creation failed").hasMessageContaining("Worktree retained");
        assertThat(created.get()).exists();
        assertThat(manager.getActiveCount()).isEqualTo(1);
        // No worker started. A later explicit safe cleanup can remove the empty snapshot.
        assertThat(manager.removeWorktree(created.get(), context).success()).isTrue();
        assertThat(created.get()).doesNotExist();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "fixture creation failure")
    void creationExceptionKeepsRecoveryRecordListableAndPreservesCause(String message) throws Exception {
        AtomicReference<Path> created = new AtomicReference<>();
        IllegalStateException failure = new IllegalStateException(message);
        doAnswer(invocation -> {
            ManagedProcessRunner.Request request = invocation.getArgument(0);
            Object actual = invocation.callRealMethod();
            if (request.command().contains("worktree") && request.command().contains("add")) {
                Path path = Path.of(request.command().get(request.command().size() - 2));
                created.set(path); fixtureWorktrees.add(path);
                throw failure;
            }
            return actual;
        }).when(runner).runRawGit(any());

        String expected = message == null ? "Creation failed without an error message" : message;
        assertThatThrownBy(() -> manager.createWorktree("partial", context, false))
                .hasMessageContaining("Worktree retained").hasMessageContaining(expected).hasCause(failure);
        assertThat(created.get()).isDirectory();
        WorktreeTool tool = new WorktreeTool(manager);
        var listing = tool.call(new ToolInput(Map.of("subcommand", "list")), context);
        assertThat(listing.isError()).as(listing.content()).isFalse();
        assertThat(listing.content()).contains(created.get().toString(), "retained", expected);
        var removed = tool.call(new ToolInput(Map.of("subcommand", "remove", "path", created.get().toString())), context);
        assertThat(removed.isError()).as(removed.content()).isFalse();
        assertThat(created.get()).doesNotExist();
    }

    @Test
    void branchDeletionFailureReportsOnlyRemainingBranchAndExplicitCleanupDoesNotRedeliver() throws Exception {
        var tree = create(context, false);
        Files.writeString(tree.path().resolve("result.txt"), "agent");
        AtomicBoolean reject = new AtomicBoolean(true);
        doAnswer(invocation -> {
            ManagedProcessRunner.Request request = invocation.getArgument(0);
            if (reject.get() && request.command().contains("branch") && request.command().contains("-d")) {
                return result(1, "", false, true);
            }
            return invocation.callRealMethod();
        }).when(runner).runRawGit(any());
        var delivered = manager.finishWorktree(tree.path(), true);
        assertThat(delivered.success()).isTrue();
        assertThat(delivered.cleanupWarning()).isNotEmpty();
        assertThat(delivered.summary()).contains("directory already removed").doesNotContain("retained at");
        assertThat(tree.path()).doesNotExist();
        assertThat(git(root, "branch", "--list", tree.branch())).isNotBlank();
        String target = git(root, "rev-parse", "HEAD");
        reject.set(false);
        assertThat(manager.removeWorktree(tree.path(), context).success()).isTrue();
        assertThat(git(root, "rev-parse", "HEAD")).isEqualTo(target);
        assertThat(manager.getActiveCount()).isZero();
    }

    @Test
    void manualBranchOnlyCleanupDoesNotRequireSessionBackgroundToStop() throws Exception {
        var tree = create(context, true);
        AtomicBoolean rejectBranchRemoval = new AtomicBoolean(true);
        doAnswer(invocation -> {
            ManagedProcessRunner.Request request = invocation.getArgument(0);
            if (rejectBranchRemoval.get() && request.command().contains("branch") && request.command().contains("-d")) {
                return result(1, "", false, true);
            }
            return invocation.callRealMethod();
        }).when(runner).runRawGit(any());
        var partial = manager.removeWorktree(tree.path(), context);
        assertThat(partial.success()).isTrue();
        assertThat(partial.cleanupWarning()).isNotEmpty();
        assertThat(tree.path()).doesNotExist();
        assertThat(git(root, "branch", "--list", tree.branch())).isNotBlank();

        rejectBranchRemoval.set(false);
        when(runner.currentSessionBackground("session"))
                .thenReturn(new ManagedProcessRunner.CancelSummary(1, 0, 1));
        var removed = manager.removeWorktree(tree.path(), context);
        assertThat(removed.success()).as(removed.summary()).isTrue();
        assertThat(removed.cleanupWarning()).isEmpty();
        assertThat(git(root, "branch", "--list", tree.branch())).isBlank();
    }

    @Test
    @Timeout(90)
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void singleCpuVirtualThreadCanDeliverAndRemoveWorktrees() throws Exception {
        // Scheduler parallelism is fixed at JVM startup; changing a property in this test JVM is too late.
        Path fork = Files.createDirectories(temp.resolve("single-cpu-jvm"));
        Path forkHome = Files.createDirectories(fork.resolve("home"));
        Path forkTemp = Files.createDirectories(fork.resolve("tmp"));
        Path log = fork.resolve("output.log");
        String classpath = System.getProperty("surefire.test.class.path");
        if (classpath == null || classpath.isBlank()) classpath = System.getProperty("java.class.path");
        Path mockitoAgent = Path.of(org.mockito.Mockito.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        ProcessBuilder builder = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-XX:ActiveProcessorCount=1", "-Djdk.virtualThreadScheduler.parallelism=1",
                "-javaagent:" + mockitoAgent,
                "-Duser.home=" + forkHome, "-Djava.io.tmpdir=" + forkTemp,
                "-cp", classpath, SingleCpuWorktreeProbe.class.getName())
                .directory(fork.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_CONFIG_SYSTEM", "/dev/null");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        Process process = builder.start();
        try {
            assertThat(process.waitFor(60, TimeUnit.SECONDS))
                    .as("Single-CPU child JVM timed out:\n%s", Files.readString(log)).isTrue();
            assertThat(process.exitValue()).as("Single-CPU child JVM:\n%s", Files.readString(log)).isZero();
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    /** Forked entry point uses real Git and invokes finalization itself on a virtual thread. */
    public static final class SingleCpuWorktreeProbe {
        public static void main(String[] args) throws Exception {
            assertThat(Runtime.getRuntime().availableProcessors()).isEqualTo(1);
            assertThat(System.getProperty("jdk.virtualThreadScheduler.parallelism")).isEqualTo("1");
            WorktreeManagerTest fixture = new WorktreeManagerTest();
            fixture.temp = Files.createTempDirectory("worktree-single-cpu-");
            try {
                fixture.setUp();
                String baseline = git(fixture.root, "rev-parse", "HEAD");
                var unchanged = fixture.create(fixture.context, false);
                assertCleaned(onVirtualThread(() -> fixture.manager.finishWorktree(unchanged.path(), true)), unchanged);
                assertThat(git(fixture.root, "rev-parse", "HEAD")).isEqualTo(baseline);
                System.out.println("PASS: virtual-thread delivery without changes");

                var changed = fixture.create(fixture.context, false);
                Files.writeString(changed.path().resolve("result.txt"), "isolated result\n");
                var delivered = onVirtualThread(() -> fixture.manager.finishWorktree(changed.path(), true));
                assertCleaned(delivered, changed);
                assertThat(delivered.targetMayHaveChanged()).isTrue();
                assertThat(Files.readString(fixture.root.resolve("result.txt"))).isEqualTo("isolated result\n");
                assertThat(git(fixture.root, "status", "--porcelain")).isEmpty();
                assertThat(git(fixture.root, "branch", "--list", changed.branch())).isBlank();
                System.out.println("PASS: virtual-thread delivery with changes");

                var manual = fixture.create(fixture.context, true);
                assertCleaned(onVirtualThread(() -> fixture.manager.removeWorktree(manual.path(), fixture.context)), manual);
                assertThat(git(fixture.root, "branch", "--list", manual.branch())).isBlank();
                assertThat(fixture.manager.getActiveCount()).isZero();
                System.out.println("PASS: virtual-thread manual cleanup");
            } finally {
                fixture.removeOnlyTestOwnedFixtures();
            }
        }

        private static WorktreeManager.DeliveryResult onVirtualThread(
                Callable<WorktreeManager.DeliveryResult> operation) throws Exception {
            CompletableFuture<WorktreeManager.DeliveryResult> result = new CompletableFuture<>();
            Thread.ofVirtual().name("worktree-single-cpu-finalization").start(() -> {
                try {
                    assertThat(Thread.currentThread().isVirtual()).isTrue();
                    result.complete(operation.call());
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
            return result.get(20, TimeUnit.SECONDS);
        }

        private static void assertCleaned(WorktreeManager.DeliveryResult result,
                                          WorktreeManager.ManagedWorktree tree) {
            assertThat(result.success()).as(result.summary()).isTrue();
            assertThat(result.cleanupWarning()).isEmpty();
            assertThat(tree.path()).doesNotExist();
        }
    }

    private WorktreeManager.ManagedWorktree create(ToolUseContext ctx, boolean manual) {
        var tree = manager.createWorktree("agent / 中文", ctx, manual);
        fixtureWorktrees.add(tree.path());
        return tree;
    }

    private Path repository(String name) throws Exception {
        Path repo = Files.createDirectory(temp.resolve(name));
        Path empty = Files.createDirectories(temp.resolve("empty-config"));
        git(repo, "-c", "init.templateDir=" + empty, "init", "-q", "-b", "main");
        git(repo, "config", "user.name", "Worktree Test");
        git(repo, "config", "user.email", "worktree-test@example.invalid");
        git(repo, "config", "commit.gpgsign", "false");
        git(repo, "config", "core.hooksPath", empty.toString());
        Path excludes = temp.resolve("empty-excludes");
        if (!Files.exists(excludes)) Files.writeString(excludes, "");
        git(repo, "config", "core.excludesFile", excludes.toString());
        Files.writeString(repo.resolve("base.txt"), "base\n");
        git(repo, "add", "."); git(repo, "commit", "-qm", "initial");
        return repo.toRealPath();
    }

    private static ManagedProcessRunner.Result result(int code, String output, boolean truncated, boolean stopped) {
        return new ManagedProcessRunner.Result(code, output, "fixture", truncated, false, false, false, stopped, 1, false);
    }

    private static String git(Path cwd, String... args) throws Exception {
        List<String> command = new ArrayList<>(); command.add("git"); command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true);
        pb.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        pb.environment().put("GIT_CONFIG_SYSTEM", "/dev/null");
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (!process.waitFor(Duration.ofSeconds(30).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
            process.destroyForcibly(); throw new AssertionError("fixture Git timeout");
        }
        if (process.exitValue() != 0) throw new AssertionError(command + "\n" + output);
        return output;
    }
}
