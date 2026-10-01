package com.aicodeassistant.tool.agent;

import com.aicodeassistant.authorization.AuthorizationSubject;
import com.aicodeassistant.authorization.AuthorizationSubjectResolver;
import com.aicodeassistant.service.GitService;
import com.aicodeassistant.tool.ToolUseContext;
import com.aicodeassistant.tool.process.ManagedProcessRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@EnabledOnOs({OS.MAC, OS.LINUX})
class WorktreeDeliveryEdgesTest {
    @TempDir Path temp;

    @Test
    void hiddenUntrackedResultAndRenameAreDeliveredButIgnoredOutputIsNotAdded() throws Exception {
        Path root = repository("root");
        Files.writeString(root.resolve(".gitignore"), "build-output/\n");
        git(root, "add", "."); git(root, "commit", "-qm", "ignore");
        git(root, "config", "status.showUntrackedFiles", "no");
        var manager = manager(root);
        var tree = manager.createWorktree("hidden", context(root), false);
        try {
            Files.writeString(tree.path().resolve("新结果 with space.txt"), "delivered");
            Files.createDirectory(tree.path().resolve("build-output"));
            Files.writeString(tree.path().resolve("build-output/cache"), "ignored");
            git(tree.path(), "mv", "base.txt", "renamed\nfile.txt");
            assertThat(manager.inspectPendingDelivery(tree.path())).isEqualTo(WorktreeManager.PendingDelivery.PENDING);
            var outcome = manager.finishWorktree(tree.path(), true);
            assertThat(outcome.success()).as(outcome.summary()).isTrue();
            assertThat(Files.readString(root.resolve("新结果 with space.txt"))).isEqualTo("delivered");
            assertThat(root.resolve("renamed\nfile.txt")).exists();
            assertThat(git(root, "ls-files", "build-output")).isEmpty();
        } finally { removeFixture(root, tree); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"diff", "local", "gitmodules"})
    void submoduleIgnoreConfigurationCannotHideUncommittedInternalContent(String mode) throws Exception {
        Path sub = repository("sub");
        Path root = repository("root");
        git(root, "-c", "protocol.file.allow=always", "submodule", "add", sub.toString(), "sm");
        if (mode.equals("gitmodules")) git(root, "config", "-f", ".gitmodules", "submodule.sm.ignore", "all");
        git(root, "add", "."); git(root, "commit", "-qm", "submodule");
        if (mode.equals("diff")) git(root, "config", "diff.ignoreSubmodules", "all");
        if (mode.equals("local")) git(root, "config", "submodule.sm.ignore", "all");
        var manager = manager(root);
        var tree = manager.createWorktree("submodule", context(root), false);
        try {
            git(tree.path(), "-c", "protocol.file.allow=always", "submodule", "update", "--init");
            String initialSubHead = git(tree.path().resolve("sm"), "rev-parse", "HEAD");
            Files.writeString(tree.path().resolve("sm/base.txt"), "keep submodule work");
            Files.writeString(tree.path().resolve("sm/new.txt"), "keep untracked work");
            assertThat(git(tree.path(), "status", "--porcelain")).isEmpty();
            assertThat(manager.inspectPendingDelivery(tree.path())).isEqualTo(WorktreeManager.PendingDelivery.PENDING);
            var outcome = manager.finishWorktree(tree.path(), true);
            assertThat(outcome.success()).as(outcome.summary()).isFalse();
            assertThat(tree.path()).isDirectory();
            assertThat(Files.readString(tree.path().resolve("sm/base.txt"))).isEqualTo("keep submodule work");
            assertThat(git(tree.path().resolve("sm"), "rev-parse", "HEAD")).isEqualTo(initialSubHead);
            assertThat(Files.readString(root.resolve("sm/base.txt"))).isEqualTo("base\n");
        } finally { removeFixture(root, tree); }
    }

    @Test
    void parallelDeliveriesFromOneBaselinePreserveBothResults() throws Exception {
        Path root = repository("parallel");
        var manager = manager(root);
        var first = manager.createWorktree("first", context(root), false);
        var second = manager.createWorktree("second", context(root), false);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Files.writeString(first.path().resolve("first.txt"), "first");
            Files.writeString(second.path().resolve("second.txt"), "second");
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            var a = pool.submit(() -> { ready.countDown(); start.await(); return manager.finishWorktree(first.path(), true); });
            var b = pool.submit(() -> { ready.countDown(); start.await(); return manager.finishWorktree(second.path(), true); });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            var ar = a.get(30, TimeUnit.SECONDS); var br = b.get(30, TimeUnit.SECONDS);
            assertThat(ar.success()).as(ar.summary()).isTrue();
            assertThat(br.success()).as(br.summary()).isTrue();
            assertThat(Files.readString(root.resolve("first.txt"))).isEqualTo("first");
            assertThat(Files.readString(root.resolve("second.txt"))).isEqualTo("second");
            assertThat(git(root, "status", "--porcelain")).isEmpty();
        } finally { removeFixture(root, first); removeFixture(root, second); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void noPendingDeliveryCompletesWithoutChangingDirtyParent(boolean alreadyIntegrated) throws Exception {
        Path root = repository("dirty-no-delivery");
        var manager = manager(root);
        var tree = manager.createWorktree("no-delivery", context(root), false);
        try {
            if (alreadyIntegrated) {
                Files.writeString(tree.path().resolve("already-integrated.txt"), "agent result\n");
                git(tree.path(), "add", "."); git(tree.path(), "commit", "-qm", "agent result");
                git(root, "merge", "--ff-only", tree.branch());
            }
            Files.writeString(root.resolve("base.txt"), "staged parent change\n");
            git(root, "add", "base.txt");
            Files.writeString(root.resolve("base.txt"), "unstaged parent change\n");
            Files.writeString(root.resolve("parent-untracked.txt"), "untracked parent change\n");
            String head = git(root, "rev-parse", "HEAD");
            String index = git(root, "ls-files", "--stage", "-z");
            String status = git(root, "status", "--porcelain=v1", "-z", "--untracked-files=all");
            byte[] tracked = Files.readAllBytes(root.resolve("base.txt"));
            byte[] untracked = Files.readAllBytes(root.resolve("parent-untracked.txt"));
            assertThat(manager.inspectPendingDelivery(tree.path())).isEqualTo(WorktreeManager.PendingDelivery.NONE);

            var outcome = manager.finishWorktree(tree.path(), true);
            assertThat(outcome.success()).as(outcome.summary()).isTrue();
            assertThat(outcome.targetMayHaveChanged()).isFalse();
            assertThat(outcome.cleanupWarning()).isEmpty();
            assertThat(tree.path()).doesNotExist();
            assertThat(git(root, "rev-parse", "HEAD")).isEqualTo(head);
            assertThat(git(root, "ls-files", "--stage", "-z")).isEqualTo(index);
            assertThat(git(root, "status", "--porcelain=v1", "-z", "--untracked-files=all")).isEqualTo(status);
            assertThat(Files.readAllBytes(root.resolve("base.txt"))).isEqualTo(tracked);
            assertThat(Files.readAllBytes(root.resolve("parent-untracked.txt"))).isEqualTo(untracked);
            if (alreadyIntegrated) {
                assertThat(Files.readString(root.resolve("already-integrated.txt"))).isEqualTo("agent result\n");
            }
        } finally { removeFixture(root, tree); }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void dirtyParentStillRejectsActualPendingDelivery(boolean committed) throws Exception {
        Path root = repository("dirty-pending-delivery");
        var manager = manager(root);
        var tree = manager.createWorktree("pending", context(root), false);
        try {
            Files.writeString(tree.path().resolve("pending.txt"), "agent work to preserve\n");
            if (committed) {
                git(tree.path(), "add", "."); git(tree.path(), "commit", "-qm", "pending agent result");
            }
            Files.writeString(root.resolve("base.txt"), "parent work to preserve\n");
            String targetHead = git(root, "rev-parse", "HEAD");
            String targetIndex = git(root, "ls-files", "--stage", "-z");
            String agentHead = git(tree.path(), "rev-parse", "HEAD");
            String agentStatus = git(tree.path(), "status", "--porcelain=v1", "-z");
            assertThat(manager.inspectPendingDelivery(tree.path())).isEqualTo(WorktreeManager.PendingDelivery.PENDING);

            var outcome = manager.finishWorktree(tree.path(), true);
            assertThat(outcome.success()).isFalse();
            assertThat(outcome.targetMayHaveChanged()).isFalse();
            assertThat(outcome.summary()).contains("Target has uncommitted changes");
            assertThat(tree.path()).isDirectory();
            assertThat(git(root, "rev-parse", "HEAD")).isEqualTo(targetHead);
            assertThat(git(root, "ls-files", "--stage", "-z")).isEqualTo(targetIndex);
            assertThat(Files.readString(root.resolve("base.txt"))).isEqualTo("parent work to preserve\n");
            assertThat(root.resolve("pending.txt")).doesNotExist();
            assertThat(git(tree.path(), "rev-parse", "HEAD")).isEqualTo(agentHead);
            assertThat(git(tree.path(), "status", "--porcelain=v1", "-z")).isEqualTo(agentStatus);
            assertThat(Files.readString(tree.path().resolve("pending.txt"))).isEqualTo("agent work to preserve\n");
        } finally { removeFixture(root, tree); }
    }

    @Test
    void linkedCheckoutAndPathAliasBindTheirOwnTargetNotTheServiceRepository() throws Exception {
        Path root = repository("primary");
        Path linked = temp.resolve("linked");
        git(root, "worktree", "add", "-b", "linked-target", linked.toString(), "HEAD");
        Path alias = temp.resolve("alias");
        Files.createSymbolicLink(alias, linked);
        var manager = manager(alias);
        var tree = manager.createWorktree("linked", context(alias), false);
        try {
            Files.writeString(tree.path().resolve("linked-result"), "belongs to linked target");
            assertThat(manager.listWorktrees(context(alias))).contains(linked.toString());
            var result = manager.finishWorktree(tree.path(), true);
            assertThat(result.success()).as(result.summary()).isTrue();
            assertThat(linked.resolve("linked-result")).exists();
            assertThat(root.resolve("linked-result")).doesNotExist();
            assertThat(git(root, "branch", "--show-current")).isEqualTo("main\n");
        } finally {
            removeFixture(linked, tree);
            git(root, "worktree", "remove", "--force", linked.toString());
        }
    }

    @Test
    void unbornAndDetachedHeadsAreRejectedBeforeAnyManagedTreeExists() throws Exception {
        Path unborn = Files.createDirectory(temp.resolve("unborn"));
        git(unborn, "init", "-q", "-b", "main");
        var manager = manager(unborn);
        assertThatThrownBy(() -> manager.createWorktree("unborn", context(unborn), false))
                .isInstanceOf(IllegalStateException.class);
        assertThat(manager.getActiveCount()).isZero();
        Path detached = repository("detached");
        git(detached, "checkout", "--detach", "-q");
        var detachedManager = manager(detached);
        assertThatThrownBy(() -> detachedManager.createWorktree("detached", context(detached), false))
                .isInstanceOf(IllegalStateException.class);
        assertThat(detachedManager.getActiveCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void targetOrAgentHistoryRollbackIsNeverMerged(boolean agentRollback) throws Exception {
        Path root = repository("rollback");
        Files.writeString(root.resolve("second-base"), "baseline");
        git(root, "add", "."); git(root, "commit", "-qm", "second baseline");
        var manager = manager(root);
        var tree = manager.createWorktree("rollback", context(root), false);
        try {
            // Reset is fixture setup only, never a production recovery action.
            git(agentRollback ? tree.path() : root, "reset", "--hard", "HEAD~1");
            Files.writeString(tree.path().resolve("result"), "must remain here");
            var result = manager.finishWorktree(tree.path(), true);
            assertThat(result.success()).as(result.summary()).isFalse();
            assertThat(tree.path().resolve("result")).exists();
            assertThat(root.resolve("result")).doesNotExist();
        } finally { removeFixture(root, tree); }
    }

    @Test
    void cleanAgentCommitAddedAfterMergeIsRetainedRatherThanDeletedUsingOldTip() throws Exception {
        Path root = repository("post-merge");
        var runner = spy(new ManagedProcessRunner());
        var manager = manager(root, runner);
        var tree = manager.createWorktree("post-merge", context(root), false);
        try {
            Files.writeString(tree.path().resolve("delivered"), "first");
            doAnswer(invocation -> {
                ManagedProcessRunner.Request request = invocation.getArgument(0);
                Object result = invocation.callRealMethod();
                if (request.command().contains("merge") && !request.command().contains("--abort")) {
                    Files.writeString(tree.path().resolve("late"), "new commit after delivery");
                    git(tree.path(), "add", "."); git(tree.path(), "commit", "-qm", "late fixture commit");
                }
                return result;
            }).when(runner).runRawGit(any());
            var result = manager.finishWorktree(tree.path(), true);
            assertThat(result.success()).as(result.summary()).isTrue();
            assertThat(result.cleanupWarning()).contains("changed after delivery");
            assertThat(root.resolve("delivered")).exists();
            assertThat(root.resolve("late")).doesNotExist();
            assertThat(tree.path().resolve("late")).exists();
        } finally { removeFixture(root, tree); }
    }

    @Test
    void abortFailurePreservesConflictAndAgentRecoveryLocation() throws Exception {
        Path root = repository("abort-failure");
        var runner = spy(new ManagedProcessRunner());
        var manager = manager(root, runner);
        var tree = manager.createWorktree("conflict", context(root), false);
        try {
            Files.writeString(tree.path().resolve("base.txt"), "agent\n");
            Files.writeString(root.resolve("base.txt"), "parent\n");
            git(root, "add", "."); git(root, "commit", "-qm", "parent conflict");
            doAnswer(invocation -> {
                ManagedProcessRunner.Request request = invocation.getArgument(0);
                if (request.command().contains("--abort")) {
                    return new ManagedProcessRunner.Result(128, "", "fixture abort failure", false, false,
                            false, false, true, 1, false);
                }
                return invocation.callRealMethod();
            }).when(runner).runRawGit(any());
            var result = manager.finishWorktree(tree.path(), true);
            assertThat(result.success()).isFalse();
            assertThat(result.targetMayHaveChanged()).isTrue();
            assertThat(result.summary()).contains("recovery was not confirmed", tree.path().toString());
            assertThat(root.resolve(".git/MERGE_HEAD")).exists();
            assertThat(git(root, "ls-files", "--unmerged")).isNotEmpty();
            assertThat(tree.path()).isDirectory();
        } finally { removeFixture(root, tree); }
    }

    @Test
    void stagedGitlinkRemainsDeliverableWhenDiffConfigurationHidesSubmodules() throws Exception {
        Path sub = repository("gitlink-sub");
        Path root = repository("gitlink-root");
        git(root, "-c", "protocol.file.allow=always", "submodule", "add", sub.toString(), "sm");
        git(root, "commit", "-qam", "submodule");
        git(root, "submodule", "deinit", "-f", "sm");
        Files.writeString(sub.resolve("next"), "next submodule commit");
        git(sub, "add", "."); git(sub, "commit", "-qm", "next");
        String next = git(sub, "rev-parse", "HEAD").strip();
        git(root, "config", "diff.ignoreSubmodules", "all");
        var manager = manager(root);
        var tree = manager.createWorktree("gitlink", context(root), false);
        try {
            git(tree.path(), "update-index", "--cacheinfo", "160000," + next + ",sm");
            assertThat(git(tree.path(), "diff", "--cached")).isEmpty();
            assertThat(manager.inspectPendingDelivery(tree.path())).isEqualTo(WorktreeManager.PendingDelivery.PENDING);
            var result = manager.finishWorktree(tree.path(), true);
            assertThat(result.success()).as(result.summary()).isTrue();
            assertThat(git(root, "rev-parse", "HEAD:sm").strip()).isEqualTo(next);
        } finally { removeFixture(root, tree); }
    }

    private WorktreeManager manager(Path root) {
        return manager(root, new ManagedProcessRunner());
    }

    private WorktreeManager manager(Path root, ManagedProcessRunner runner) {
        var subjects = mock(AuthorizationSubjectResolver.class);
        when(subjects.resolve("fixture-run")).thenReturn(new AuthorizationSubject(
                "fixture-session", "fixture-run", "fixture-run", "fixture", root));
        return new WorktreeManager(subjects, new GitService(), runner);
    }

    private ToolUseContext context(Path root) {
        return ToolUseContext.of(root.toString(), "fixture-session").withCurrentRunId("fixture-run");
    }

    private Path repository(String name) throws Exception {
        Path root = Files.createDirectory(temp.resolve(name));
        git(root, "init", "-q", "-b", "main");
        git(root, "config", "user.email", "fixture@example.invalid");
        git(root, "config", "user.name", "Fixture");
        git(root, "config", "core.excludesFile", "/dev/null");
        Files.writeString(root.resolve("base.txt"), "base\n");
        git(root, "add", "."); git(root, "commit", "-qm", "base");
        return root.toRealPath();
    }

    private String git(Path root, String... args) throws Exception {
        List<String> argv = new ArrayList<>(List.of("git")); argv.addAll(List.of(args));
        Path output = Files.createTempFile(temp, "git-", ".txt");
        var builder = new ProcessBuilder(argv).directory(root.toFile()).redirectErrorStream(true).redirectOutput(output.toFile());
        builder.environment().put("GIT_CONFIG_GLOBAL", "/dev/null");
        builder.environment().put("GIT_CONFIG_SYSTEM", "/dev/null");
        builder.environment().put("GIT_CONFIG_NOSYSTEM", "1");
        var process = builder.start();
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
            String text = Files.readString(output);
            assertThat(process.exitValue()).as(text).isZero();
            return text;
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    private void removeFixture(Path root, WorktreeManager.ManagedWorktree tree) throws Exception {
        // Only fixture-owned paths: submodule worktrees require force for test teardown.
        if (Files.exists(tree.path())) git(root, "worktree", "remove", "--force", "--force", tree.path().toString());
    }
}
