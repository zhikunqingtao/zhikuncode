package com.aicodeassistant.service;

import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.history.FileHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * GitDiffTracker 单元测试 — 验证变更追踪、摘要生成与 Git diff 代理。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GitDiffTracker 单元测试")
class GitDiffTrackerTest {

    @Mock
    private GitService gitService;
    @Mock
    private FeatureFlagService featureFlagService;
    @Mock
    private FileHistoryService fileHistoryService;

    private GitDiffTracker gitDiffTracker;

    @BeforeEach
    void setUp() {
        gitDiffTracker = new GitDiffTracker(gitService, featureFlagService, fileHistoryService);
    }

    // ═══════════════ getSessionChanges ═══════════════

    @Test
    @DisplayName("getSessionChanges — 解析 git status --porcelain 输出")
    void testGetSessionChanges_ParsesGitStatus() {
        // Given: git status 返回多种状态
        String porcelainOutput = " M src/Main.java\nA  src/NewFile.java\n D src/OldFile.java\n?? src/Untracked.java";
        when(gitService.execGitPublic(any(Path.class), eq("status"), eq("--porcelain")))
                .thenReturn(porcelainOutput);
        when(fileHistoryService.getChangedFilesSince()).thenReturn(List.of());

        // When
        List<GitDiffTracker.FileChange> changes = gitDiffTracker.getSessionChanges("session-1");

        // Then
        assertThat(changes).hasSize(4);
        assertThat(changes.stream().filter(c -> c.type() == GitDiffTracker.ChangeType.MODIFIED).count())
                .isEqualTo(1);
        assertThat(changes.stream().filter(c -> c.type() == GitDiffTracker.ChangeType.ADDED).count())
                .isGreaterThanOrEqualTo(1); // A 和 ?? 都映射为 ADDED
    }

    @Test
    @DisplayName("getSessionChanges — Git 失败时优雅降级")
    void testGetSessionChanges_HandlesGitFailure() {
        // Given: git 命令抛异常
        when(gitService.execGitPublic(any(Path.class), eq("status"), eq("--porcelain")))
                .thenThrow(new RuntimeException("Not a git repository"));
        when(fileHistoryService.getChangedFilesSince()).thenReturn(List.of("src/Edited.java"));

        // When
        List<GitDiffTracker.FileChange> changes = gitDiffTracker.getSessionChanges("session-1");

        // Then: 仍然返回 FileHistoryService 的编辑记录
        assertThat(changes).hasSize(1);
        assertThat(changes.getFirst().filePath()).isEqualTo("src/Edited.java");
    }

    @Test
    @DisplayName("getSessionChanges — 合并 FileHistoryService 中未被 Git 追踪的文件")
    void testGetSessionChanges_MergesFileHistory() {
        // Given: Git 追踪了 A.java，FileHistory 追踪了 A.java 和 B.java
        when(gitService.execGitPublic(any(Path.class), eq("status"), eq("--porcelain")))
                .thenReturn(" M src/A.java");
        when(fileHistoryService.getChangedFilesSince())
                .thenReturn(List.of("src/A.java", "src/B.java"));

        // When
        List<GitDiffTracker.FileChange> changes = gitDiffTracker.getSessionChanges("session-1");

        // Then: A.java 来自 Git，B.java 从 FileHistory 补充，不重复
        assertThat(changes).hasSize(2);
        assertThat(changes.stream().map(GitDiffTracker.FileChange::filePath).toList())
                .containsExactlyInAnyOrder("src/A.java", "src/B.java");
    }

    // ═══════════════ getChangeSummary ═══════════════

    @Test
    @DisplayName("getChangeSummary — 格式化为人可读摘要")
    void testGetChangeSummary_FormatsReadable() {
        // Given
        when(gitService.execGitPublic(any(Path.class), eq("status"), eq("--porcelain")))
                .thenReturn(" M src/Main.java\nA  src/New.java\n D src/Old.java");
        when(fileHistoryService.getChangedFilesSince()).thenReturn(List.of());

        // When
        String summary = gitDiffTracker.getChangeSummary("session-1");

        // Then
        assertThat(summary).isNotEmpty();
        assertThat(summary).doesNotContain("No changes detected");
        // 应包含变更类型和文件名
        assertThat(summary).contains("file(s)");
    }

    @Test
    @DisplayName("getChangeSummary — 无变更时返回提示文本")
    void testGetChangeSummary_NoChanges() {
        // Given
        when(gitService.execGitPublic(any(Path.class), eq("status"), eq("--porcelain")))
                .thenReturn("");
        when(fileHistoryService.getChangedFilesSince()).thenReturn(List.of());

        // When
        String summary = gitDiffTracker.getChangeSummary("session-1");

        // Then
        assertThat(summary).isEqualTo("No changes detected.");
    }

    // ═══════════════ getGitDiff ═══════════════

    @Test
    @DisplayName("getGitDiff — 委托给 GitService 执行 git diff")
    void testGetGitDiff_DelegatesToGitService() {
        // Given
        String expectedDiff = "diff --git a/src/Main.java b/src/Main.java\n@@ -1,3 +1,4 @@\n+import java.util.List;";
        when(gitService.execGitPublic(any(Path.class), eq("diff")))
                .thenReturn(expectedDiff);

        // When
        String diff = gitDiffTracker.getGitDiff("/tmp/project");

        // Then
        assertThat(diff).isEqualTo(expectedDiff);
        verify(gitService).execGitPublic(Path.of("/tmp/project"), "diff");
    }

    @Test
    @DisplayName("getGitDiff — Git 失败时返回空字符串")
    void testGetGitDiff_FailureReturnsEmpty() {
        // Given
        when(gitService.execGitPublic(any(Path.class), eq("diff")))
                .thenThrow(new RuntimeException("git not found"));

        // When
        String diff = gitDiffTracker.getGitDiff("/tmp/project");

        // Then
        assertThat(diff).isEmpty();
    }

    // ═══════════════ getFileHistory ═══════════════

    @Test
    @DisplayName("getFileHistory — 解析 git log 输出为历史记录")
    void testGetFileHistory_ParsesGitLog() {
        // Given
        String gitLog = "abc1234 Fix bug in Main\ndef5678 Add feature\nghi9012 Initial commit";
        when(gitService.execGitPublic(any(Path.class),
                eq("log"), eq("--oneline"), eq("-10"), eq("--"), eq("src/Main.java")))
                .thenReturn(gitLog);

        // When
        GitDiffTracker.FileChangeHistory history = gitDiffTracker.getFileHistory("src/Main.java", "session-1");

        // Then
        assertThat(history.filePath()).isEqualTo("src/Main.java");
        assertThat(history.changes()).hasSize(3);
        assertThat(history.totalEdits()).isEqualTo(3);
    }

    @Test
    @DisplayName("getFileHistory — Git 失败时返回空历史")
    void testGetFileHistory_GitFailure() {
        // Given
        when(gitService.execGitPublic(any(Path.class),
                eq("log"), eq("--oneline"), eq("-10"), eq("--"), eq("src/NoFile.java")))
                .thenThrow(new RuntimeException("fatal: bad revision"));

        // When
        GitDiffTracker.FileChangeHistory history = gitDiffTracker.getFileHistory("src/NoFile.java", "session-1");

        // Then
        assertThat(history.changes()).isEmpty();
        assertThat(history.totalEdits()).isEqualTo(0);
    }
}
