package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.CommandContext;
import com.aicodeassistant.command.CommandResult;
import com.aicodeassistant.service.GitService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GitReviewCommandTest {

    @TempDir
    Path temp;

    // ───── I3-A：带参数分支 ─────

    @Test
    void argsReturnReviewRequirementsWithoutDiffPrefetch() {
        GitService git = guardPassingGit();
        GitReviewCommand command = new GitReviewCommand(git);

        CommandResult result = command.execute("只审暂存区，不要看未跟踪文件", context(temp));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value()).contains(
                "请按以下用户要求进行代码审查，只审查、不修改文件。",
                "以用户指定的比较对象、文件范围和排除项为准。",
                "只有未指定比较对象时，才默认审查范围内的当前本地变更：",
                "已暂存、未暂存及未跟踪文件；明确排除的部分不纳入。",
                "先确定范围，再通过现有工具读取必要内容，不预取排除文件的正文。",
                "无法读取指定比较对象时说明原因，不自行换成其他比较对象。",
                "工具失败或材料不完整时说明未覆盖部分，不能声称全部审完。",
                "本次用户要求：",
                "只审暂存区，不要看未跟踪文件");
        verify(git, never()).execGitPublic(any(), any(String[].class));
    }

    @Test
    void argsPreserveRawFormattingVerbatim() {
        GitService git = guardPassingGit();
        GitReviewCommand command = new GitReviewCommand(git);
        String args = "比较 main...HEAD\n"
                + "排除  docs/keep out.txt\n"
                + "格式: key=value \"带引号的参数\"";

        CommandResult result = command.execute(args, context(temp));

        assertThat(result.value()).contains(args);
        verify(git, never()).execGitPublic(any(), any(String[].class));
    }

    @Test
    void blankArgsFallThroughToDefaultLocalChangesFlow() {
        GitService git = guardPassingGit();
        stubDiffs(git, "", "");

        CommandResult result = new GitReviewCommand(git).execute("   ", context(temp));

        // 空白参数不进入参数分支，仍走默认本地变更流程（含未跟踪核验指引）。
        assertThat(result.value()).contains("未跟踪文件尚未核验");
        verify(git).execGitPublic(any(), eq("diff"));
    }

    @Test
    void nullArgsFallThroughToDefaultLocalChangesFlow() {
        GitService git = guardPassingGit();
        stubDiffs(git, "", "");

        CommandResult result = new GitReviewCommand(git).execute(null, context(temp));

        assertThat(result.value()).contains("未跟踪文件尚未核验");
        verify(git).execGitPublic(any(), eq("diff"));
    }

    // ───── I2-A：diff 读取失败矩阵 ─────

    @Test
    void bothDiffsFailingReturnsError() {
        GitService git = guardPassingGit();
        stubDiffs(git, null, null);

        CommandResult result = new GitReviewCommand(git).execute("", context(temp));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("未暂存和已暂存差异").contains("失败");
        assertThat(result.value()).isNull();
    }

    @Test
    void unstagedDiffFailingWithNonEmptyStagedReturnsError() {
        GitService git = guardPassingGit();
        stubDiffs(git, null, "staged-content");

        CommandResult result = new GitReviewCommand(git).execute("", context(temp));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("未暂存差异").contains("失败");
        assertThat(result.value()).isNull();
    }

    @Test
    void unstagedDiffFailingWithEmptyStagedReturnsError() {
        GitService git = guardPassingGit();
        stubDiffs(git, null, "");

        CommandResult result = new GitReviewCommand(git).execute("", context(temp));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("未暂存差异").contains("失败");
    }

    @Test
    void stagedDiffFailingWithNonEmptyUnstagedReturnsError() {
        GitService git = guardPassingGit();
        stubDiffs(git, "unstaged-content", null);

        CommandResult result = new GitReviewCommand(git).execute("", context(temp));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("已暂存差异").contains("失败");
        assertThat(result.value()).isNull();
    }

    @Test
    void stagedDiffFailingWithEmptyUnstagedReturnsError() {
        GitService git = guardPassingGit();
        stubDiffs(git, "", null);

        CommandResult result = new GitReviewCommand(git).execute("", context(temp));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.error()).contains("已暂存差异").contains("失败");
    }

    // ───── I2-A：空差异不再短路为“没有待审查的变更” ─────

    @Test
    void emptyDiffsProduceReviewPromptWithEmptyPartNotes() {
        GitService git = guardPassingGit();
        stubDiffs(git, "", "");

        CommandResult result = new GitReviewCommand(git).execute("", context(temp));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value())
                .contains("未暂存差异预览")
                .contains("已暂存差异预览")
                .contains("未跟踪文件尚未核验");
        assertThat(countOccurrences(result.value(), "该部分未见差异")).isEqualTo(2);
        // 旧行为：直接短路返回文本“没有待审查的变更”，不再允许出现该整句结论。
        assertThat(result.value().trim()).isNotEqualTo("没有待审查的变更");
    }

    @Test
    void reviewPromptContainsUntrackedGuidanceBeforeDimensions() {
        GitService git = guardPassingGit();
        stubDiffs(git, "unstaged-content", "staged-content");

        CommandResult result = new GitReviewCommand(git).execute("", context(temp));

        String value = result.value();
        assertThat(value).contains(
                "以下只是已跟踪文件的未暂存/已暂存差异预览，未跟踪文件尚未核验。",
                "先核对文件范围；使用 git status --short 与 git ls-files --others --exclude-standard",
                "等现有工具检查未跟踪文件，再按必要性读取正文。不要自动上传全部未跟踪文件。",
                "预览标记\"已截断\"时，按文件继续读取必要 diff；未暂存与已暂存版本分别判断，",
                "同一文件存在两种版本时，不把工作区文件内容当作暂存区内容。",
                "只审查、不修改；范围外资料、读取失败和未完成部分必须明确说明。",
                "仅在相关集合均检查后才能说\"没有待审查的变更\"或\"全部审完\"。");
        int guidanceIndex = value.indexOf("以下只是已跟踪文件的未暂存/已暂存差异预览");
        int dimensionIndex = value.indexOf("1. 🐛 Bug 风险");
        assertThat(guidanceIndex).isGreaterThanOrEqualTo(0);
        assertThat(dimensionIndex).isGreaterThan(guidanceIndex);
        // 既有审查维度与严重级别措辞保持不变。
        assertThat(value).contains(
                "2. 🔒 安全漏洞：注入、越权、敏感数据暴露",
                "3. ⚡ 性能问题：N+1 查询、内存分配、死循环",
                "4. 📐 代码规范：命名、结构、重复代码、单一职责",
                "5. 🧪 测试覆盖建议：缺失的边界场景、回归测试",
                "对每个发现给出严重级别（高/中/低）和具体修复建议。");
    }

    // ───── I2-A：共享 8000 字符预算表 ─────

    @Test
    void sharedBudgetUnstaged7000Staged0() {
        assertBudget(7000, 0, 7000, 0);
    }

    @Test
    void sharedBudgetUnstaged0Staged7000() {
        assertBudget(0, 7000, 0, 7000);
    }

    @Test
    void sharedBudgetUnstaged7000Staged2000() {
        assertBudget(7000, 2000, 6000, 2000);
    }

    @Test
    void sharedBudgetUnstaged7000Staged7000() {
        assertBudget(7000, 7000, 4000, 4000);
    }

    @Test
    void sharedBudgetUnstaged2000Staged7000() {
        assertBudget(2000, 7000, 2000, 6000);
    }

    private void assertBudget(int diffLen, int stagedLen,
                              int expectedDiffChars, int expectedStagedChars) {
        GitService git = guardPassingGit();
        stubDiffs(git, "d".repeat(diffLen), "s".repeat(stagedLen));

        CommandResult result = new GitReviewCommand(git).execute("", context(temp));

        assertThat(result.isSuccess()).isTrue();
        String value = result.value();
        // 文案中不存在超过 50 的连续 d/s，长游程只能来自 diff 原文。
        int probe = Math.max(expectedDiffChars, 1);
        if (expectedDiffChars > 0) {
            assertThat(value).contains("d".repeat(probe));
            assertThat(value).doesNotContain("d".repeat(expectedDiffChars + 1));
        } else {
            assertThat(value).doesNotContain("d".repeat(50));
        }
        if (expectedStagedChars > 0) {
            assertThat(value).contains("s".repeat(Math.max(expectedStagedChars, 1)));
            assertThat(value).doesNotContain("s".repeat(expectedStagedChars + 1));
        } else {
            assertThat(value).doesNotContain("s".repeat(50));
        }
        int expectedMarkers = (expectedDiffChars < diffLen ? 1 : 0)
                + (expectedStagedChars < stagedLen ? 1 : 0);
        assertThat(countOccurrences(value, "...(已截断)")).isEqualTo(expectedMarkers);
        int expectedEmptyNotes = (diffLen == 0 ? 1 : 0) + (stagedLen == 0 ? 1 : 0);
        assertThat(countOccurrences(value, "该部分未见差异")).isEqualTo(expectedEmptyNotes);
        assertThat(value).contains("未跟踪文件尚未核验");
    }

    // ───── 真实 Git 仓库（临时目录） ─────

    @Test
    void stagedOnlyChangesAppearInStagedPreview() throws Exception {
        Path repository = newRepository("staged-only");
        Files.writeString(repository.resolve("tracked.txt"), "v2\n");
        runGit(repository, "add", "tracked.txt");

        CommandResult result = new GitReviewCommand(new GitService())
                .execute("", context(repository));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value())
                .contains("已暂存差异预览")
                .contains("tracked.txt")
                .contains("未跟踪文件尚未核验");
        assertThat(countOccurrences(result.value(), "该部分未见差异")).isEqualTo(1);
    }

    @Test
    void untrackedOnlyChangesProduceEmptyNotesAndPendingVerification() throws Exception {
        Path repository = newRepository("untracked-only");
        Files.writeString(repository.resolve("new-file.txt"), "untracked\n");

        CommandResult result = new GitReviewCommand(new GitService())
                .execute("", context(repository));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value()).contains("未跟踪文件尚未核验");
        assertThat(countOccurrences(result.value(), "该部分未见差异")).isEqualTo(2);
        assertThat(result.value().trim()).isNotEqualTo("没有待审查的变更");
    }

    @Test
    void bothTrackedChangeKindsAppearInBothPreviews() throws Exception {
        Path repository = newRepository("both-kinds");
        Files.writeString(repository.resolve("staged.txt"), "staged-v2\n");
        runGit(repository, "add", "staged.txt");
        Files.writeString(repository.resolve("unstaged.txt"), "unstaged-v2\n");

        CommandResult result = new GitReviewCommand(new GitService())
                .execute("", context(repository));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value())
                .contains("未暂存差异预览")
                .contains("已暂存差异预览")
                .contains("staged.txt")
                .contains("unstaged.txt");
        assertThat(result.value()).doesNotContain("该部分未见差异");
    }

    @Test
    void completelyCleanRepoStillProducesReviewPrompt() throws Exception {
        Path repository = newRepository("clean");

        CommandResult result = new GitReviewCommand(new GitService())
                .execute("", context(repository));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.value()).contains("未跟踪文件尚未核验");
        assertThat(countOccurrences(result.value(), "该部分未见差异")).isEqualTo(2);
        assertThat(result.value().trim()).isNotEqualTo("没有待审查的变更");
    }

    // ───── 工具方法 ─────

    private GitService guardPassingGit() {
        GitService git = mock(GitService.class);
        when(git.isGitRepositoryRoot(any())).thenReturn(true);
        return git;
    }

    private void stubDiffs(GitService git, String unstaged, String staged) {
        when(git.execGitPublic(any(), eq("diff"))).thenReturn(unstaged);
        when(git.execGitPublic(any(), eq("diff"), eq("--cached"))).thenReturn(staged);
    }

    private CommandContext context(Path workDir) {
        return CommandContext.of("session", workDir.toString(), null, null);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        for (int index = 0; (index = text.indexOf(needle, index)) >= 0; index += needle.length()) {
            count++;
        }
        return count;
    }

    private Path newRepository(String name) throws Exception {
        Path repository = Files.createDirectory(temp.resolve(name)).toRealPath();
        runGit(repository, "init");
        runGit(repository, "config", "user.email", "test@example.com");
        runGit(repository, "config", "user.name", "Test User");
        Files.writeString(repository.resolve("tracked.txt"), "v1\n");
        Files.writeString(repository.resolve("staged.txt"), "staged-v1\n");
        Files.writeString(repository.resolve("unstaged.txt"), "unstaged-v1\n");
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
        String output = new String(process.getInputStream().readAllBytes()).trim();
        assertThat(process.waitFor())
                .as("git %s failed: %s", String.join(" ", arguments), output)
                .isZero();
    }
}
