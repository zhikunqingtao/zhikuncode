package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import com.aicodeassistant.service.GitService;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@Component
public class GitCommitCommand implements Command {

    private final GitService gitService;

    public GitCommitCommand(GitService gitService) {
        this.gitService = gitService;
    }

    @Override public String getName() { return "commit"; }
    @Override public String getDescription() { return "AI 辅助 Git 提交"; }
    @Override public CommandType getType() { return CommandType.LOCAL; }

    @Override
    public CommandResult execute(String args, CommandContext context) {
        if (context.workingDir() == null || context.workingDir().isBlank()) {
            return CommandResult.error("工作目录未设置");
        }
        Path workDir = Path.of(context.workingDir());

        Path normalizedWorkDir = workDir.toAbsolutePath().normalize();
        String workDirStr = normalizedWorkDir.toString();
        if (workDirStr.equals("/") || workDirStr.startsWith("/etc") || workDirStr.startsWith("/usr")) {
            return CommandResult.error("不允许在系统目录中执行 Git 操作");
        }

        CommandResult denied = GitCommandGuard.requireRepositoryRoot(
                gitService, context);
        if (denied != null) return denied;

        String status = gitService.execGitPublic(workDir, "status", "--porcelain");
        if (status == null) {
            return CommandResult.error("读取 Git 状态失败，请检查仓库后重试。");
        }

        String stagedNames = gitService.execGitRaw(workDir, "diff", "--cached", "--ignore-submodules=none", "--name-only", "-z");
        if (stagedNames == null) {
            return CommandResult.error("读取暂存文件列表失败，请检查仓库后重试。");
        }
        List<String> changedFiles;
        if (stagedNames.isEmpty()) {
            // Git can complete a pending merge even when the index tree is unchanged.
            // Resolve through Git so linked worktrees (where .git is a file) work too.
            String mergeHead = gitService.execGitPublic(workDir, "rev-parse", "--git-path", "MERGE_HEAD");
            if (mergeHead == null || mergeHead.isBlank()) {
                return CommandResult.error("读取 Git 合并状态失败，请检查仓库后重试。");
            }
            if (!Files.isRegularFile(workDir.resolve(mergeHead))) {
                return CommandResult.text("没有已暂存的变更；请先暂存需要提交的文件。");
            }
            changedFiles = List.of();
        } else {
            if (!stagedNames.endsWith("\0")) {
                return CommandResult.error("暂存文件列表不完整，未执行提交；请稍后重试。");
            }
            changedFiles = Arrays.asList(stagedNames.substring(0, stagedNames.length() - 1).split("\0", -1));
        }

        if (args != null && !args.isBlank()) {
            // 消息作为独立参数传递，不经过 shell；引号和换行都是消息正文。
            String commitResult = gitService.execGitPublic(workDir, "commit", "-m", args);
            if (commitResult == null || commitResult.isBlank()) {
                return CommandResult.error("提交可能未生效、也可能已成功（Git 未返回可读输出）：请先核对仓库状态（git log / git status）再决定是否重试；若被 pre-commit 钩子拒绝，请先修复钩子。");
            }
            return CommandResult.text("✅ 已提交:\n" + commitResult);
        }

        String stagedDiff = gitService.execGitPublic(workDir, "diff", "--cached", "--ignore-submodules=none", "--stat");
        String detailedDiff = gitService.execGitPublic(workDir, "diff", "--cached", "--ignore-submodules=none");
        if (stagedDiff == null || detailedDiff == null) {
            String failedParts;
            if (stagedDiff == null && detailedDiff == null) {
                failedParts = "stat 与正文";
            } else if (stagedDiff == null) {
                failedParts = "stat";
            } else {
                failedParts = "正文";
            }
            return CommandResult.error("读取暂存差异失败（" + failedParts + "），未完成预览；请稍后重试。");
        }

        return CommandResult.jsx(Map.of(
            "action", "gitCommitPreview",
            "status", status,
            "stagedDiff", stagedDiff,
            "detailedDiff", truncate(detailedDiff, 5000),
            "changedFiles", changedFiles,
            "fileCount", changedFiles.size()
        ));
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "\n...(已截断)" : text;
    }
}
