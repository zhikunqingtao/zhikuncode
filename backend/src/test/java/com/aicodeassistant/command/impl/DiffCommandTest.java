package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.CommandContext;
import com.aicodeassistant.command.CommandResult;
import com.aicodeassistant.command.CommandResult.ResultType;
import com.aicodeassistant.service.GitService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DiffCommandTest {

    @TempDir
    Path temp;

    // ───── 参数矩阵：精确范围解析（不再子串匹配） ─────

    @Test
    void nullEmptyBlankAndUnstagedArgsAllReadUnstagedScope() {
        for (String args : Arrays.asList(null, "", "   ", "unstaged", "UNSTAGED", "  Unstaged  ")) {
            GitService git = guardPassingGit();
            stubAllScopes(git);

            CommandResult result = new DiffCommand(git).execute(args, context(temp));

            assertThat(result.type()).as("args=%s", args).isEqualTo(ResultType.JSX);
            assertThat(result.data().get("staged")).as("args=%s", args).isEqualTo(false);
            verify(git, times(1)).execGitPublic(any(), eq("diff"), eq("--stat"));
            verify(git, times(1)).execGitPublic(any(), eq("diff"));
            verify(git, never()).execGitPublic(any(), eq("diff"), eq("--cached"), eq("--stat"));
            verify(git, never()).execGitPublic(any(), eq("diff"), eq("--cached"));
        }
    }

    @Test
    void stagedAndDoubleDashStagedArgsReadStagedScopeIgnoringCase() {
        for (String args : List.of("staged", "STAGED", "Staged", "--staged", "--STAGED", "  --Staged  ")) {
            GitService git = guardPassingGit();
            stubAllScopes(git);

            CommandResult result = new DiffCommand(git).execute(args, context(temp));

            assertThat(result.type()).as("args=%s", args).isEqualTo(ResultType.JSX);
            assertThat(result.data().get("staged")).as("args=%s", args).isEqualTo(true);
            verify(git, times(1)).execGitPublic(any(), eq("diff"), eq("--cached"), eq("--stat"));
            verify(git, times(1)).execGitPublic(any(), eq("diff"), eq("--cached"));
            verify(git, never()).execGitPublic(any(), eq("diff"), eq("--stat"));
            verify(git, never()).execGitPublic(any(), eq("diff"));
        }
    }

    @Test
    void unknownOrCombinedArgumentsReturnUsageErrorWithoutGitCalls() {
        for (String args : List.of("foo", "--cached", "unstagedx", "staged foo", "foo staged", "unstaged staged")) {
            GitService git = guardPassingGit();

            CommandResult result = new DiffCommand(git).execute(args, context(temp));

            assertThat(result.isSuccess()).as("args=%s", args).isFalse();
            assertThat(result.type()).as("args=%s", args).isEqualTo(ResultType.ERROR);
            assertThat(result.error()).as("args=%s", args)
                    .isEqualTo("用法：/diff [unstaged|staged|--staged]");
            verify(git, never()).execGitPublic(any(), any(String[].class));
        }
    }

    // ───── 读取失败识别矩阵 ─────

    @Test
    void statReadFailureAloneReturnsExplicitError() {
        GitService git = guardPassingGit();
        stubUnstaged(git, null, "diff --git a/alpha.txt b/alpha.txt\n+alpha-v2");

        CommandResult result = new DiffCommand(git).execute(null, context(temp));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.type()).isEqualTo(ResultType.ERROR);
        assertThat(result.error()).isEqualTo("读取 Git 差异失败（stat），请稍后重试。");
    }

    @Test
    void diffBodyReadFailureAloneReturnsExplicitError() {
        GitService git = guardPassingGit();
        stubUnstaged(git, " alpha.txt | 1 +\n", null);

        CommandResult result = new DiffCommand(git).execute("unstaged", context(temp));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.type()).isEqualTo(ResultType.ERROR);
        assertThat(result.error()).isEqualTo("读取 Git 差异失败（正文），请稍后重试。");
    }

    @Test
    void bothReadsFailingReturnCombinedError() {
        GitService git = guardPassingGit();
        stubUnstaged(git, null, null);

        CommandResult result = new DiffCommand(git).execute(null, context(temp));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.type()).isEqualTo(ResultType.ERROR);
        assertThat(result.error()).isEqualTo("读取 Git 差异失败（stat 与正文），请稍后重试。");
    }

    @Test
    void stagedScopeReadFailureIsIdentifiedAsWell() {
        GitService git = guardPassingGit();
        stubStaged(git, null, null);

        CommandResult result = new DiffCommand(git).execute("--staged", context(temp));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.type()).isEqualTo(ResultType.ERROR);
        assertThat(result.error()).isEqualTo("读取 Git 差异失败（stat 与正文），请稍后重试。");
    }

    // ───── 成功路径：空差异 / JSX / 截断 ─────

    @Test
    void successfulButEmptyReadsReturnNoDiffText() {
        GitService git = guardPassingGit();
        stubUnstaged(git, "", "");

        CommandResult result = new DiffCommand(git).execute(null, context(temp));

        assertThat(result.type()).isEqualTo(ResultType.TEXT);
        assertThat(result.value()).isEqualTo("无差异");
    }

    @Test
    void whitespaceOnlyReadsAlsoReturnNoDiffText() {
        GitService git = guardPassingGit();
        stubUnstaged(git, " \n", "\t  ");

        CommandResult result = new DiffCommand(git).execute("unstaged", context(temp));

        assertThat(result.type()).isEqualTo(ResultType.TEXT);
        assertThat(result.value()).isEqualTo("无差异");
    }

    @Test
    void nonEmptyReadsReturnDiffViewWithScopeAndCounters() {
        GitService git = guardPassingGit();
        String stat = "alpha.txt | 2 +-\nbeta.txt | 1 +\n2 files changed, 3 insertions(+), 1 deletion(-)";
        String diff = "diff --git a/alpha.txt b/alpha.txt\n+alpha-v2";
        stubUnstaged(git, stat, diff);

        CommandResult result = new DiffCommand(git).execute("", context(temp));

        assertThat(result.type()).isEqualTo(ResultType.JSX);
        assertThat(result.data())
                .containsEntry("action", "gitDiffView")
                .containsEntry("staged", false)
                .containsEntry("stat", stat)
                .containsEntry("diff", diff)
                .containsEntry("fileCount", 2L);
    }

    @Test
    void diffBodyOver10000CharsIsTruncatedWithMarker() {
        GitService git = guardPassingGit();
        stubUnstaged(git, " alpha.txt | 1 +\n", "d".repeat(10005));

        CommandResult result = new DiffCommand(git).execute(null, context(temp));

        assertThat(result.type()).isEqualTo(ResultType.JSX);
        assertThat(result.data().get("diff")).isEqualTo("d".repeat(10000) + "\n...(已截断)");
    }

    @Test
    void diffBodyAt10000CharsIsKeptWhole() {
        GitService git = guardPassingGit();
        String diff = "d".repeat(10000);
        stubUnstaged(git, " alpha.txt | 1 +\n", diff);

        CommandResult result = new DiffCommand(git).execute(null, context(temp));

        assertThat(result.type()).isEqualTo(ResultType.JSX);
        assertThat(result.data().get("diff")).isEqualTo(diff);
    }

    @Test
    void blankStatWithNonEmptyDiffCountsZeroFilesInsteadOfMinusOne() {
        GitService git = guardPassingGit();
        stubUnstaged(git, "", "diff --git a/alpha.txt b/alpha.txt\n+alpha-v2");

        CommandResult result = new DiffCommand(git).execute(null, context(temp));

        assertThat(result.type()).isEqualTo(ResultType.JSX);
        assertThat(result.data().get("stat")).isEqualTo("");
        assertThat(result.data().get("fileCount")).isEqualTo(0L);
    }

    @Test
    void oversizedStatIsTruncatedButFileCountStillUsesFullStat() {
        GitService git = guardPassingGit();
        // 每行 2000 字符 × 7 行 = 14000 字符：完整 stat 共 7 行（fileCount=6）
        String stat = ("x".repeat(1999) + "\n").repeat(7);
        stubUnstaged(git, stat, "diff --git a/alpha.txt b/alpha.txt\n+alpha-v2");

        CommandResult result = new DiffCommand(git).execute(null, context(temp));

        assertThat(result.type()).isEqualTo(ResultType.JSX);
        assertThat((String) result.data().get("stat"))
                .endsWith("...(已截断)")
                .hasSize(10000 + "\n...(已截断)".length());
        // 截断后的 stat 只剩 5 行 + 标记行（fileCount=5）；fileCount=6 说明按完整 stat 计算
        assertThat(result.data().get("fileCount")).isEqualTo(6L);
    }

    // ───── 真实临时仓库：暂存/未暂存范围各自只反映对应内容 ─────

    @Test
    void realRepositorySeparatesUnstagedAndStagedScopes() throws Exception {
        Path repository = newRepository("diff-scopes");
        Files.writeString(repository.resolve("alpha.txt"), "alpha-unstaged-v2\n");
        Files.writeString(repository.resolve("beta.txt"), "beta-staged-v2\n");
        runGit(repository, "add", "beta.txt");
        GitService git = new GitService();

        CommandResult unstaged = new DiffCommand(git).execute(null, context(repository));

        assertThat(unstaged.isSuccess()).isTrue();
        assertThat(unstaged.type()).isEqualTo(ResultType.JSX);
        assertThat(unstaged.data().get("staged")).isEqualTo(false);
        assertThat((String) unstaged.data().get("diff"))
                .contains("alpha-unstaged-v2")
                .doesNotContain("beta-staged-v2");
        assertThat((String) unstaged.data().get("stat"))
                .contains("alpha.txt")
                .doesNotContain("beta.txt");

        CommandResult staged = new DiffCommand(git).execute("staged", context(repository));

        assertThat(staged.isSuccess()).isTrue();
        assertThat(staged.type()).isEqualTo(ResultType.JSX);
        assertThat(staged.data().get("staged")).isEqualTo(true);
        assertThat((String) staged.data().get("diff"))
                .contains("beta-staged-v2")
                .doesNotContain("alpha-unstaged-v2");
        assertThat((String) staged.data().get("stat"))
                .contains("beta.txt")
                .doesNotContain("alpha.txt");
    }

    @Test
    void realCleanRepositoryReturnsNoDiffText() throws Exception {
        Path repository = newRepository("diff-clean");

        CommandResult result = new DiffCommand(new GitService()).execute(null, context(repository));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.type()).isEqualTo(ResultType.TEXT);
        assertThat(result.value()).isEqualTo("无差异");
    }

    // ───── 工具方法 ─────

    private GitService guardPassingGit() {
        GitService git = mock(GitService.class);
        when(git.isGitRepositoryRoot(any())).thenReturn(true);
        return git;
    }

    private void stubAllScopes(GitService git) {
        stubUnstaged(git, " alpha.txt | 1 +\n", "unstaged-body");
        stubStaged(git, " beta.txt | 1 +\n", "staged-body");
    }

    private void stubUnstaged(GitService git, String stat, String diff) {
        when(git.execGitPublic(any(), eq("diff"), eq("--stat"))).thenReturn(stat);
        when(git.execGitPublic(any(), eq("diff"))).thenReturn(diff);
    }

    private void stubStaged(GitService git, String stat, String diff) {
        when(git.execGitPublic(any(), eq("diff"), eq("--cached"), eq("--stat"))).thenReturn(stat);
        when(git.execGitPublic(any(), eq("diff"), eq("--cached"))).thenReturn(diff);
    }

    private CommandContext context(Path workDir) {
        return CommandContext.of("session", workDir.toString(), null, null);
    }

    private Path newRepository(String name) throws Exception {
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
        Files.writeString(repository.resolve("alpha.txt"), "alpha-v1\n");
        Files.writeString(repository.resolve("beta.txt"), "beta-v1\n");
        runGit(repository, "add", ".");
        runGit(repository, "commit", "-m", "initial");
        return repository;
    }

    private static void runGit(Path directory, String... arguments) throws Exception {
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
        String output = new String(process.getInputStream().readAllBytes()).trim();
        assertThat(finished)
                .as("git %s timed out after 10s: %s", String.join(" ", arguments), output)
                .isTrue();
        assertThat(process.exitValue())
                .as("git %s failed: %s", String.join(" ", arguments), output)
                .isZero();
    }
}
