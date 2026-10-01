package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.CommandContext;
import com.aicodeassistant.command.CommandResult;
import com.aicodeassistant.command.CommandResult.ResultType;
import com.aicodeassistant.service.GitService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class GitCommitCommandTest {

    @TempDir
    Path temp;

    // ───── status 读取失败识别 ─────

    @Test
    void nullStatusReturnsErrorAndDoesNotRunCommit() {
        GitService git = guardPassingGit();
        when(git.execGitPublic(any(), eq("status"), eq("--porcelain"))).thenReturn(null);

        CommandResult result = new GitCommitCommand(git).execute("msg", context(temp));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.type()).isEqualTo(ResultType.ERROR);
        assertThat(result.error()).isEqualTo("读取 Git 状态失败，请检查仓库后重试。");
        verify(git).isGitRepositoryRoot(any());
        verify(git).execGitPublic(any(), eq("status"), eq("--porcelain"));
        // 状态读取失败后不得再触发 commit / diff 等任何其他 git 调用。
        verifyNoMoreInteractions(git);
    }

    @Test
    void blankStatusAndEmptyIndexWithoutMergeReportsNoStagedChanges() {
        GitService git = guardPassingGit();
        when(git.execGitPublic(any(), eq("status"), eq("--porcelain"))).thenReturn("  \n");
        stubEmptyStagingArea(git);

        CommandResult result = new GitCommitCommand(git).execute(null, context(temp));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.type()).isEqualTo(ResultType.TEXT);
        assertThat(result.value()).contains("没有已暂存的变更");
        verify(git).isGitRepositoryRoot(any());
        verify(git).execGitPublic(any(), eq("status"), eq("--porcelain"));
        verifyStagedNamesRead(git);
        verify(git).execGitPublic(any(), eq("rev-parse"), eq("--git-path"), eq("MERGE_HEAD"));
        verifyNoMoreInteractions(git);
    }

    // ───── 无参预览：暂存差异读取失败不得返回貌似完整的面板 ─────

    @Test
    void previewWithFailedStagedStatReturnsErrorInsteadOfPanel() {
        GitService git = guardPassingGit();
        when(git.execGitPublic(any(), eq("status"), eq("--porcelain"))).thenReturn("M  alpha.txt");
        when(git.execGitPublic(any(), eq("diff"), eq("--cached"), eq("--ignore-submodules=none"), eq("--stat"))).thenReturn(null);
        when(git.execGitPublic(any(), eq("diff"), eq("--cached"), eq("--ignore-submodules=none"))).thenReturn("diff --git a/alpha.txt b/alpha.txt");

        CommandResult result = new GitCommitCommand(git).execute(null, context(temp));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.type()).isEqualTo(ResultType.ERROR);
        assertThat(result.error()).isEqualTo("读取暂存差异失败（stat），未完成预览；请稍后重试。");
        assertThat(result.data()).isEmpty();
        verify(git).isGitRepositoryRoot(any());
        verify(git).execGitPublic(any(), eq("status"), eq("--porcelain"));
        verifyStagedNamesRead(git);
        verify(git).execGitPublic(any(), eq("diff"), eq("--cached"), eq("--ignore-submodules=none"), eq("--stat"));
        verify(git).execGitPublic(any(), eq("diff"), eq("--cached"), eq("--ignore-submodules=none"));
        // 预览失败路径不得触发 commit 或任何其他 git 调用。
        verifyNoMoreInteractions(git);
    }

    @Test
    void previewWithFailedDetailedDiffReturnsErrorInsteadOfPanel() {
        GitService git = guardPassingGit();
        when(git.execGitPublic(any(), eq("status"), eq("--porcelain"))).thenReturn("M  alpha.txt");
        when(git.execGitPublic(any(), eq("diff"), eq("--cached"), eq("--ignore-submodules=none"), eq("--stat"))).thenReturn(" alpha.txt | 1 +\n");
        when(git.execGitPublic(any(), eq("diff"), eq("--cached"), eq("--ignore-submodules=none"))).thenReturn(null);

        CommandResult result = new GitCommitCommand(git).execute(null, context(temp));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.type()).isEqualTo(ResultType.ERROR);
        assertThat(result.error()).isEqualTo("读取暂存差异失败（正文），未完成预览；请稍后重试。");
        assertThat(result.data()).isEmpty();
    }

    @Test
    void previewWithBothStagedDiffsFailedReturnsError() {
        GitService git = guardPassingGit();
        when(git.execGitPublic(any(), eq("status"), eq("--porcelain"))).thenReturn("M  alpha.txt");
        when(git.execGitPublic(any(), eq("diff"), eq("--cached"), eq("--ignore-submodules=none"), eq("--stat"))).thenReturn(null);
        when(git.execGitPublic(any(), eq("diff"), eq("--cached"), eq("--ignore-submodules=none"))).thenReturn(null);

        CommandResult result = new GitCommitCommand(git).execute(null, context(temp));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.type()).isEqualTo(ResultType.ERROR);
        assertThat(result.error()).isEqualTo("读取暂存差异失败（stat 与正文），未完成预览；请稍后重试。");
    }

    @Test
    void unstagedChangesDoNotProduceCommitPanelOrExecuteCommit() {
        GitService git = guardPassingGit();
        when(git.execGitPublic(any(), eq("status"), eq("--porcelain"))).thenReturn("M alpha.txt");
        stubEmptyStagingArea(git);

        CommandResult result = new GitCommitCommand(git).execute(null, context(temp));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.type()).isEqualTo(ResultType.TEXT);
        assertThat(result.value()).contains("没有已暂存的变更");
        verify(git).isGitRepositoryRoot(any());
        verify(git).execGitPublic(any(), eq("status"), eq("--porcelain"));
        verifyStagedNamesRead(git);
        verify(git).execGitPublic(any(), eq("rev-parse"), eq("--git-path"), eq("MERGE_HEAD"));
        verifyNoMoreInteractions(git);
    }

    @ParameterizedTest
    @ValueSource(strings = {"read-failure", "missing-final-nul"})
    void unreadableStagedFileListStopsBeforeCommit(String failure) {
        GitService git = guardPassingGit();
        when(git.execGitPublic(any(), eq("status"), eq("--porcelain"))).thenReturn("M  alpha.txt");
        when(git.execGitRaw(any(), eq("diff"), eq("--cached"), eq("--ignore-submodules=none"), eq("--name-only"), eq("-z")))
                .thenReturn("read-failure".equals(failure) ? null : "alpha.txt");

        CommandResult result = new GitCommitCommand(git).execute("msg", context(temp));

        assertThat(result.type()).isEqualTo(ResultType.ERROR);
        assertThat(result.error()).contains("暂存文件列表");
        verify(git).isGitRepositoryRoot(any());
        verify(git).execGitPublic(any(), eq("status"), eq("--porcelain"));
        verifyStagedNamesRead(git);
        verifyNoMoreInteractions(git);
    }

    @Test
    void failedMergeStateReadIsNotReportedAsNoChanges() {
        GitService git = guardPassingGit();
        when(git.execGitPublic(any(), eq("status"), eq("--porcelain"))).thenReturn("");
        stubEmptyStagingArea(git);
        when(git.execGitPublic(any(), eq("rev-parse"), eq("--git-path"), eq("MERGE_HEAD"))).thenReturn(null);

        CommandResult result = new GitCommitCommand(git).execute("msg", context(temp));

        assertThat(result.type()).isEqualTo(ResultType.ERROR);
        assertThat(result.error()).contains("读取 Git 合并状态失败");
        verify(git, never()).execGitPublic(any(), eq("commit"), eq("-m"), eq("msg"));
    }

    // ───── 有参提交：结果不可读与成功路径 ─────

    @Test
    void unreadableCommitResultReportsUnconfirmedAndCommitsExactlyOnce() {
        GitService git = guardPassingGit();
        when(git.execGitPublic(any(), eq("status"), eq("--porcelain"))).thenReturn("M  alpha.txt");
        when(git.execGitPublic(any(), eq("commit"), eq("-m"), eq("msg"))).thenReturn(null);

        CommandResult result = new GitCommitCommand(git).execute("msg", context(temp));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.type()).isEqualTo(ResultType.ERROR);
        assertThat(result.error())
                .isEqualTo("提交可能未生效、也可能已成功（Git 未返回可读输出）：请先核对仓库状态（git log / git status）再决定是否重试；若被 pre-commit 钩子拒绝，请先修复钩子。");
        // 提交恰执行一次：乱码/null 结果不得触发任何自动重试。
        verify(git, times(1)).execGitPublic(any(), eq("commit"), eq("-m"), eq("msg"));
        verify(git).isGitRepositoryRoot(any());
        verify(git).execGitPublic(any(), eq("status"), eq("--porcelain"));
        verifyStagedNamesRead(git);
        verifyNoMoreInteractions(git);
    }

    @Test
    void blankCommitResultIsAlsoReportedAsUnconfirmed() {
        GitService git = guardPassingGit();
        when(git.execGitPublic(any(), eq("status"), eq("--porcelain"))).thenReturn("M  alpha.txt");
        when(git.execGitPublic(any(), eq("commit"), eq("-m"), eq("msg"))).thenReturn("   ");

        CommandResult result = new GitCommitCommand(git).execute("msg", context(temp));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.type()).isEqualTo(ResultType.ERROR);
        assertThat(result.error())
                .isEqualTo("提交可能未生效、也可能已成功（Git 未返回可读输出）：请先核对仓库状态（git log / git status）再决定是否重试；若被 pre-commit 钩子拒绝，请先修复钩子。");
        verify(git, times(1)).execGitPublic(any(), eq("commit"), eq("-m"), eq("msg"));
    }

    @Test
    void commitSuccessOnlyReadsStatusAndIndexBeforeOneCommitWithoutAutoStaging() {
        GitService git = guardPassingGit();
        when(git.execGitPublic(any(), eq("status"), eq("--porcelain"))).thenReturn("M  alpha.txt");
        when(git.execGitPublic(any(), eq("commit"), eq("-m"), eq("msg")))
                .thenReturn("[main 1234567] msg\n 1 file changed");

        CommandResult result = new GitCommitCommand(git).execute("msg", context(temp));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.type()).isEqualTo(ResultType.TEXT);
        assertThat(result.value()).contains("已提交").contains("[main 1234567] msg");
        verify(git, times(1)).execGitPublic(any(), eq("commit"), eq("-m"), eq("msg"));
        verify(git).isGitRepositoryRoot(any());
        verify(git).execGitPublic(any(), eq("status"), eq("--porcelain"));
        verifyStagedNamesRead(git);
        // 只读状态/暂存文件列表后恰提交一次，不自动暂存、重试或读取预览正文。
        verifyNoMoreInteractions(git);
    }

    // ───── 有参提交：结构化参数原样传递 ─────

    static Stream<String> commitMessages() {
        return Stream.of(
                "ordinary title",
                "\"quoted title\"",
                "Fix \"quoted\" input",
                "\"quoted first line\n\nquoted final line\"",
                "\"\"");
    }

    @ParameterizedTest
    @MethodSource("commitMessages")
    void commitMessageIsPassedAsOneUnchangedArgument(String message) {
        GitService git = guardPassingGit();
        when(git.execGitPublic(any(), eq("status"), eq("--porcelain"))).thenReturn("M  alpha.txt");
        when(git.execGitPublic(any(), eq("commit"), eq("-m"), eq(message)))
                .thenReturn("[main 1234567] " + message);

        CommandResult result = new GitCommitCommand(git).execute(message, context(temp));

        assertThat(result.isSuccess()).isTrue();
        verify(git, times(1)).execGitPublic(any(), eq("commit"), eq("-m"), eq(message));
        verify(git).isGitRepositoryRoot(any());
        verify(git).execGitPublic(any(), eq("status"), eq("--porcelain"));
        verifyStagedNamesRead(git);
        verifyNoMoreInteractions(git);
    }

    // ───── 真实临时仓库：一次提交恰好新增一条 ─────

    @ParameterizedTest
    @MethodSource("commitMessages")
    void realCommitPreservesMessageAddsExactlyOneCommitAndThenReportsCleanTree(String message) throws Exception {
        Path repository = newRepository("commit-real");
        Files.writeString(repository.resolve("beta.txt"), "beta-v2\n");
        runGit(repository, "add", "beta.txt");
        GitService git = new GitService();
        GitCommitCommand command = new GitCommitCommand(git);
        int commitsBefore = commitCount(repository);

        CommandResult preview = command.execute(null, context(repository));

        assertThat(preview.isSuccess()).isTrue();
        assertThat(preview.type()).isEqualTo(ResultType.JSX);
        assertThat((String) preview.data().get("stagedDiff")).contains("beta.txt");
        assertThat((String) preview.data().get("detailedDiff")).contains("beta-v2");
        assertThat(commitCount(repository)).as("预览不得产生提交").isEqualTo(commitsBefore);

        CommandResult committed = command.execute(message, context(repository));

        assertThat(committed.isSuccess()).isTrue();
        assertThat(committed.type()).isEqualTo(ResultType.TEXT);
        assertThat(committed.value()).contains("已提交");
        assertThat(commitCount(repository)).as("提交恰好新增一条").isEqualTo(commitsBefore + 1);
        assertThat(gitOutput(repository, "log", "-1", "--format=%B"))
                .as("提交消息应保留字面引号与内部换行").isEqualTo(message);

        CommandResult clean = command.execute(null, context(repository));

        assertThat(clean.isSuccess()).isTrue();
        assertThat(clean.type()).isEqualTo(ResultType.TEXT);
        assertThat(clean.value()).contains("没有已暂存的变更");
    }

    @Test
    void mixedWorkingTreePreviewsAndCommitsOnlyTheStagedFile() throws Exception {
        Path repository = newRepository("mixed");
        Files.writeString(repository.resolve("alpha.txt"), "staged-alpha\n");
        runGit(repository, "add", "alpha.txt");
        Files.writeString(repository.resolve("beta.txt"), "unstaged-beta\n");
        Files.writeString(repository.resolve("untracked.txt"), "untracked\n");
        GitCommitCommand command = new GitCommitCommand(new GitService());

        CommandResult preview = command.execute(null, context(repository));

        assertThat(preview.type()).isEqualTo(ResultType.JSX);
        assertThat(preview.data()).containsEntry("changedFiles", List.of("alpha.txt"))
                .containsEntry("fileCount", 1);
        assertThat((String) preview.data().get("detailedDiff"))
                .contains("staged-alpha").doesNotContain("unstaged-beta", "untracked");
        assertThat(command.execute("staged only", context(repository)).isSuccess()).isTrue();
        assertThat(gitOutput(repository, "diff-tree", "--no-commit-id", "--name-only", "-r", "HEAD"))
                .isEqualTo("alpha.txt");
        assertThat(gitOutput(repository, "status", "--porcelain"))
                .contains("M beta.txt", "?? untracked.txt").doesNotContain("alpha.txt");
    }

    @ParameterizedTest
    @ValueSource(strings = {"clean", "unstaged", "untracked"})
    void realEmptyStagingAreaDoesNotPreviewOrAttemptCommit(String state) throws Exception {
        Path repository = newRepository("empty-" + state);
        if ("unstaged".equals(state)) Files.writeString(repository.resolve("alpha.txt"), "unstaged\n");
        if ("untracked".equals(state)) Files.writeString(repository.resolve("untracked.txt"), "untracked\n");
        GitService git = spy(new GitService());
        GitCommitCommand command = new GitCommitCommand(git);
        String before = gitOutput(repository, "rev-parse", "HEAD");

        for (String message : new String[]{null, "must not commit"}) {
            CommandResult result = command.execute(message, context(repository));
            assertThat(result.type()).isEqualTo(ResultType.TEXT);
            assertThat(result.value()).contains("没有已暂存的变更");
        }

        verify(git, never()).execGitPublic(repository, "commit", "-m", "must not commit");
        assertThat(gitOutput(repository, "rev-parse", "HEAD")).isEqualTo(before);
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false"})
    void emptyIndexStillCompletesPendingMerge(boolean linkedWorktree, boolean unstaged) throws Exception {
        Path repository = newRepository("merge");
        String originalBranch = gitOutput(repository, "symbolic-ref", "--short", "HEAD");
        runGit(repository, "checkout", "-b", "topic");
        runGit(repository, "commit", "--allow-empty", "-m", "topic");
        String topicHead = gitOutput(repository, "rev-parse", "HEAD");
        runGit(repository, "checkout", originalBranch);
        runGit(repository, "commit", "--allow-empty", "-m", "main side");
        if (linkedWorktree) {
            Path linked = temp.resolve("linked");
            runGit(repository, "worktree", "add", "-b", "linked-branch", linked.toString(), "HEAD");
            repository = linked.toRealPath();
            assertThat(Files.isRegularFile(repository.resolve(".git"))).isTrue();
        }
        runGit(repository, "merge", "--no-commit", "--no-ff", "topic");
        if (unstaged) Files.writeString(repository.resolve("alpha.txt"), "unstaged\n");
        assertThat(gitOutput(repository, "diff", "--cached")).isEmpty();
        String oldHead = gitOutput(repository, "rev-parse", "HEAD");
        GitCommitCommand command = new GitCommitCommand(new GitService());

        CommandResult preview = command.execute(null, context(repository));

        assertThat(preview.type()).isEqualTo(ResultType.JSX);
        assertThat(preview.data()).containsEntry("fileCount", 0).containsEntry("changedFiles", List.of());
        assertThat(command.execute("finish merge", context(repository)).isSuccess()).isTrue();
        assertThat(gitOutput(repository, "log", "-1", "--format=%P"))
                .isEqualTo(oldHead + " " + topicHead);
        assertThat(gitOutput(repository, "show", "HEAD:alpha.txt")).isEqualTo("alpha-v1");
        if (unstaged) assertThat(gitOutput(repository, "diff")).contains("unstaged");
    }

    @Test
    @EnabledOnOs({OS.MAC, OS.LINUX})
    void renameIsOneStagedEntryAndUnusualFileNamesArePreserved() throws Exception {
        Path repository = newRepository("names");
        runGit(repository, "mv", "alpha.txt", "renamed alpha.txt");
        List<String> names = List.of(" leading.txt", "trailing \t", "line\nbreak.txt", "carriage\rreturn.txt", "中文.txt");
        for (String name : names) {
            Files.writeString(repository.resolve(name), "content for " + name + "\n");
            runGit(repository, "add", "--", name);
        }
        GitCommitCommand command = new GitCommitCommand(new GitService());

        CommandResult preview = command.execute(null, context(repository));

        assertThat(preview.type()).isEqualTo(ResultType.JSX);
        assertThat(preview.data()).containsEntry("fileCount", names.size() + 1);
        @SuppressWarnings("unchecked")
        List<String> actualNames = (List<String>) preview.data().get("changedFiles");
        assertThat(actualNames).containsAll(names).contains("renamed alpha.txt").doesNotContain("alpha.txt");
        assertThat(command.execute("rename and paths", context(repository)).isSuccess()).isTrue();
        for (String name : names) {
            assertThat(gitOutput(repository, "show", "HEAD:" + name)).isEqualTo(("content for " + name).trim());
        }
    }

    @Test
    void initialCommitWorksWithoutHead() throws Exception {
        Path repository = initializedRepository("initial");
        Files.writeString(repository.resolve("first.txt"), "first\n");
        runGit(repository, "add", "first.txt");
        GitCommitCommand command = new GitCommitCommand(new GitService());

        assertThat(command.execute(null, context(repository)).data())
                .containsEntry("changedFiles", List.of("first.txt")).containsEntry("fileCount", 1);
        assertThat(command.execute("first commit", context(repository)).isSuccess()).isTrue();
        assertThat(commitCount(repository)).isEqualTo(1);
    }

    @Test
    void unresolvedIndexStillLetsGitRejectExactlyOneCommitAttempt() throws Exception {
        Path repository = newRepository("conflict");
        String originalBranch = gitOutput(repository, "symbolic-ref", "--short", "HEAD");
        runGit(repository, "checkout", "-b", "topic");
        Files.writeString(repository.resolve("alpha.txt"), "topic\n");
        runGit(repository, "commit", "-am", "topic");
        runGit(repository, "checkout", originalBranch);
        Files.writeString(repository.resolve("alpha.txt"), "main\n");
        runGit(repository, "commit", "-am", "main");
        GitService git = spy(new GitService());
        assertThat(git.execGitPublic(repository, "merge", "topic")).isNull();
        String oldHead = gitOutput(repository, "rev-parse", "HEAD");

        CommandResult result = new GitCommitCommand(git).execute("not resolved", context(repository));

        assertThat(result.type()).isEqualTo(ResultType.ERROR);
        verify(git, times(1)).execGitPublic(repository, "commit", "-m", "not resolved");
        assertThat(gitOutput(repository, "rev-parse", "HEAD")).isEqualTo(oldHead);
        assertThat(gitOutput(repository, "ls-files", "--unmerged")).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"diff", "submodule", "gitmodules"})
    void stagedSubmoduleChangesRemainCommittableWhenIgnored(String ignoreSource) throws Exception {
        Path repository = repositoryWithIgnoredSubmodule(ignoreSource);
        Path submodule = repository.resolve("sm");
        runGit(submodule, "checkout", "origin/HEAD");
        runGit(repository, "add", "sm");
        String stagedGitlink = gitOutput(repository, "rev-parse", ":sm");
        assertThat(stagedGitlink).isNotEqualTo(gitOutput(repository, "rev-parse", "HEAD:sm"));
        assertThat(gitOutput(repository, "diff", "--cached", "--name-only"))
                .as("夹具必须实际触发 ignore 配置隐藏已暂存子模块").isEmpty();
        int commitsBefore = commitCount(repository);
        GitService git = spy(new GitService());
        GitCommitCommand command = new GitCommitCommand(git);

        CommandResult preview = command.execute(null, context(repository));

        assertThat(preview.type()).isEqualTo(ResultType.JSX);
        assertThat(preview.data()).containsEntry("changedFiles", List.of("sm")).containsEntry("fileCount", 1);
        assertThat((String) preview.data().get("stagedDiff")).contains("sm");
        assertThat((String) preview.data().get("detailedDiff")).contains("sm");
        assertThat(commitCount(repository)).isEqualTo(commitsBefore);

        CommandResult committed = command.execute("update submodule", context(repository));

        assertThat(committed.type()).isEqualTo(ResultType.TEXT);
        assertThat(committed.value()).contains("已提交");
        verify(git, times(1)).execGitPublic(repository, "commit", "-m", "update submodule");
        assertThat(commitCount(repository)).isEqualTo(commitsBefore + 1);
        assertThat(gitOutput(repository, "rev-parse", "HEAD:sm")).isEqualTo(stagedGitlink);
    }

    @ParameterizedTest
    @ValueSource(strings = {"dirty", "unstaged-gitlink"})
    void ignoredUnstagedSubmoduleChangesDoNotBecomeCommittable(String state) throws Exception {
        Path repository = repositoryWithIgnoredSubmodule("diff");
        Path submodule = repository.resolve("sm");
        if ("dirty".equals(state)) {
            Files.writeString(submodule.resolve("alpha.txt"), "unstaged submodule content\n");
        } else {
            runGit(submodule, "checkout", "origin/HEAD");
        }
        assertThat(gitOutput(repository, "diff", "--ignore-submodules=none", "--name-only"))
                .isEqualTo("sm");
        assertThat(gitOutput(repository, "diff", "--cached", "--ignore-submodules=none", "--name-only"))
                .isEmpty();
        String headBefore = gitOutput(repository, "rev-parse", "HEAD");
        String indexBefore = gitOutput(repository, "rev-parse", ":sm");
        GitService git = spy(new GitService());
        GitCommitCommand command = new GitCommitCommand(git);

        for (String message : new String[]{null, "must not commit"}) {
            CommandResult result = command.execute(message, context(repository));
            assertThat(result.type()).isEqualTo(ResultType.TEXT);
            assertThat(result.value()).contains("没有已暂存的变更");
        }

        verify(git, never()).execGitPublic(repository, "commit", "-m", "must not commit");
        assertThat(gitOutput(repository, "rev-parse", "HEAD")).isEqualTo(headBefore);
        assertThat(gitOutput(repository, "rev-parse", ":sm")).isEqualTo(indexBefore);
    }

    // ───── 工具方法 ─────

    private Path repositoryWithIgnoredSubmodule(String ignoreSource) throws Exception {
        Path source = newRepository("submodule-source");
        Files.writeString(source.resolve("alpha.txt"), "submodule v2\n");
        runGit(source, "commit", "-am", "submodule v2");
        Path repository = newRepository("superproject");
        runGit(repository, "-c", "protocol.file.allow=always", "submodule", "add", source.toString(), "sm");
        runGit(repository.resolve("sm"), "checkout", "HEAD^");
        runGit(repository, "add", "sm");
        runGit(repository, "commit", "-m", "add submodule");
        switch (ignoreSource) {
            case "diff" -> runGit(repository, "config", "diff.ignoreSubmodules", "all");
            case "submodule" -> runGit(repository, "config", "submodule.sm.ignore", "all");
            case "gitmodules" -> {
                runGit(repository, "config", "-f", ".gitmodules", "submodule.sm.ignore", "all");
                runGit(repository, "add", ".gitmodules");
                // Commit metadata first so only the gitlink can make the later index nonempty.
                runGit(repository, "commit", "-m", "ignore submodule display");
            }
            default -> throw new IllegalArgumentException(ignoreSource);
        }
        return repository;
    }

    private GitService guardPassingGit() {
        GitService git = mock(GitService.class);
        when(git.isGitRepositoryRoot(any())).thenReturn(true);
        when(git.execGitRaw(any(), eq("diff"), eq("--cached"), eq("--ignore-submodules=none"), eq("--name-only"), eq("-z")))
                .thenReturn("alpha.txt\0");
        return git;
    }

    private void stubEmptyStagingArea(GitService git) {
        when(git.execGitRaw(any(), eq("diff"), eq("--cached"), eq("--ignore-submodules=none"), eq("--name-only"), eq("-z"))).thenReturn("");
        when(git.execGitPublic(any(), eq("rev-parse"), eq("--git-path"), eq("MERGE_HEAD")))
                .thenReturn(".git/MERGE_HEAD");
    }

    private void verifyStagedNamesRead(GitService git) {
        verify(git).execGitRaw(any(), eq("diff"), eq("--cached"), eq("--ignore-submodules=none"), eq("--name-only"), eq("-z"));
    }

    private CommandContext context(Path workDir) {
        return CommandContext.of("session", workDir.toString(), null, null);
    }

    private Path newRepository(String name) throws Exception {
        Path repository = initializedRepository(name);
        Files.writeString(repository.resolve("alpha.txt"), "alpha-v1\n");
        Files.writeString(repository.resolve("beta.txt"), "beta-v1\n");
        runGit(repository, "add", ".");
        runGit(repository, "commit", "-m", "initial");
        return repository;
    }

    private Path initializedRepository(String name) throws Exception {
        Path repository = Files.createDirectory(temp.resolve(name)).toRealPath();
        // 中和用户全局 git 配置，保证测试封闭性
        Path emptyTemplateDir = Files.createDirectory(temp.resolve(name + "-template"));
        Path emptyHooksDir = Files.createDirectory(temp.resolve(name + "-hooks"));
        runGit(repository, "init", "--template=" + emptyTemplateDir);
        runGit(repository, "config", "user.email", "test@example.com");
        runGit(repository, "config", "user.name", "Test User");
        runGit(repository, "config", "commit.gpgsign", "false");
        runGit(repository, "config", "core.hooksPath", emptyHooksDir.toString());
        runGit(repository, "config", "core.autocrlf", "false");
        runGit(repository, "config", "diff.renames", "true");
        runGit(repository, "config", "merge.gpgsign", "false");
        return repository;
    }

    private static int commitCount(Path repository) throws Exception {
        return Integer.parseInt(gitOutput(repository, "rev-list", "--count", "HEAD"));
    }

    private static String gitOutput(Path directory, String... arguments) throws Exception {
        String[] command = new String[arguments.length + 1];
        command[0] = "git";
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        Process process = new ProcessBuilder(command)
                .directory(directory.toFile())
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor();
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        assertThat(finished)
                .as("git %s timed out after 10s: %s", String.join(" ", arguments), output)
                .isTrue();
        assertThat(process.exitValue())
                .as("git %s failed: %s", String.join(" ", arguments), output)
                .isZero();
        return output;
    }

    private static void runGit(Path directory, String... arguments) throws Exception {
        gitOutput(directory, arguments);
    }
}
