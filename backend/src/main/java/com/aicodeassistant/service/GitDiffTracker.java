package com.aicodeassistant.service;

import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.history.FileHistoryService;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.*;

/**
 * Git 变更追踪器 — 统一聚合 Git 状态与文件编辑历史。
 * <p>
 * 整合 GitService（Git 仓库状态）和 FileHistoryService（会话内编辑历史），
 * 提供统一的文件变更查询、历史追溯和变更摘要生成能力。
 */
@Service
public class GitDiffTracker {

    private static final Logger log = LoggerFactory.getLogger(GitDiffTracker.class);

    private final GitService gitService;
    private final FeatureFlagService featureFlagService;
    private final FileHistoryService fileHistoryService;

    public GitDiffTracker(GitService gitService,
                          FeatureFlagService featureFlagService,
                          FileHistoryService fileHistoryService) {
        this.gitService = gitService;
        this.featureFlagService = featureFlagService;
        this.fileHistoryService = fileHistoryService;
    }

    /**
     * 获取当前会话的所有文件变更。
     * <p>
     * 聚合 Git 工作目录变更与 FileHistoryService 会话内编辑历史。
     *
     * @param sessionId 会话 ID
     * @return 文件变更列表
     */
    public List<FileChange> getSessionChanges(String sessionId) {
        List<FileChange> changes = new ArrayList<>();
        try {
            // 从 Git 获取工作目录变更（porcelain 格式）
            String porcelainStatus = gitService.execGitPublic(
                    Path.of(System.getProperty("user.dir")), "status", "--porcelain");
            if (porcelainStatus != null && !porcelainStatus.isEmpty()) {
                changes.addAll(parseGitStatus(porcelainStatus));
            }
        } catch (Exception e) {
            log.warn("[GIT-DIFF] Failed to get git changes: {}", e.getMessage());
        }

        // 合并 FileHistoryService 中会话内编辑过但未被 Git 追踪的文件
        try {
            List<String> editedFiles = fileHistoryService.getChangedFilesSince();
            Set<String> gitTrackedPaths = changes.stream()
                    .map(FileChange::filePath)
                    .collect(Collectors.toSet());
            for (String editedFile : editedFiles) {
                if (!gitTrackedPaths.contains(editedFile)) {
                    changes.add(new FileChange(editedFile, ChangeType.MODIFIED,
                            Instant.now(), "edited in session"));
                }
            }
        } catch (Exception e) {
            log.debug("[GIT-DIFF] Failed to merge file history: {}", e.getMessage());
        }

        return changes;
    }

    /**
     * 获取指定文件的变更历史。
     *
     * @param filePath  文件路径
     * @param sessionId 会话 ID
     * @return 文件变更历史
     */
    public FileChangeHistory getFileHistory(String filePath, String sessionId) {
        List<FileChange> changes = new ArrayList<>();
        try {
            // git log --oneline -10 -- filePath
            String gitLog = gitService.execGitPublic(
                    Path.of(System.getProperty("user.dir")),
                    "log", "--oneline", "-10", "--", filePath);
            if (gitLog != null && !gitLog.isEmpty()) {
                for (String line : gitLog.split("\n")) {
                    if (!line.isBlank()) {
                        changes.add(new FileChange(filePath, ChangeType.MODIFIED,
                                Instant.now(), line.trim()));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[GIT-DIFF] Failed to get file history for {}: {}", filePath, e.getMessage());
        }
        return new FileChangeHistory(filePath, changes, changes.size());
    }

    /**
     * 生成变更摘要（用于上下文注入）。
     *
     * @param sessionId 会话 ID
     * @return 人可读的变更摘要
     */
    public String getChangeSummary(String sessionId) {
        List<FileChange> changes = getSessionChanges(sessionId);
        if (changes.isEmpty()) {
            return "No changes detected.";
        }

        Map<ChangeType, List<FileChange>> grouped = changes.stream()
                .collect(Collectors.groupingBy(FileChange::type));

        StringBuilder sb = new StringBuilder();
        grouped.forEach((type, files) -> {
            sb.append(type.name()).append(": ").append(files.size()).append(" file(s) (");
            sb.append(files.stream()
                    .map(f -> Path.of(f.filePath()).getFileName().toString())
                    .limit(5)
                    .collect(Collectors.joining(", ")));
            if (files.size() > 5) {
                sb.append(", ...");
            }
            sb.append(")\n");
        });
        return sb.toString().trim();
    }

    /**
     * 获取 Git diff 输出。
     *
     * @param workingDir 工作目录路径
     * @return diff 输出，失败返回空字符串
     */
    public String getGitDiff(String workingDir) {
        try {
            return gitService.execGitPublic(Path.of(workingDir), "diff");
        } catch (Exception e) {
            log.warn("[GIT-DIFF] Failed to get diff: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 解析 git status --porcelain 输出为 FileChange 列表。
     */
    private List<FileChange> parseGitStatus(String porcelainOutput) {
        List<FileChange> changes = new ArrayList<>();
        for (String line : porcelainOutput.split("\n")) {
            if (line.length() < 3) continue;
            char index = line.charAt(0);
            char worktree = line.charAt(1);
            String filePath = line.substring(3).trim();

            // 处理重命名：R  old -> new
            if (index == 'R' || worktree == 'R') {
                int arrowIdx = filePath.indexOf(" -> ");
                if (arrowIdx >= 0) {
                    filePath = filePath.substring(arrowIdx + 4);
                }
                changes.add(new FileChange(filePath, ChangeType.RENAMED, Instant.now(), "renamed"));
            } else if (index == 'A' || worktree == '?') {
                changes.add(new FileChange(filePath, ChangeType.ADDED, Instant.now(), "added"));
            } else if (index == 'D' || worktree == 'D') {
                changes.add(new FileChange(filePath, ChangeType.DELETED, Instant.now(), "deleted"));
            } else if (index == 'M' || worktree == 'M') {
                changes.add(new FileChange(filePath, ChangeType.MODIFIED, Instant.now(), "modified"));
            } else {
                // 其他状态（如 '!!' ignored, 'C' copied 等）归为 MODIFIED
                changes.add(new FileChange(filePath, ChangeType.MODIFIED, Instant.now(),
                        "status: " + index + worktree));
            }
        }
        return changes;
    }

    // === 数据结构 ===

    public record FileChange(
            String filePath,
            ChangeType type,
            Instant timestamp,
            String summary
    ) {}

    public record FileChangeHistory(
            String filePath,
            List<FileChange> changes,
            int totalEdits
    ) {}

    public enum ChangeType {
        ADDED, MODIFIED, DELETED, RENAMED
    }
}
