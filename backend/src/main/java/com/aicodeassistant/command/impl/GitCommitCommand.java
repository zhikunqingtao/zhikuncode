package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import com.aicodeassistant.service.GitService;
import org.springframework.stereotype.Component;

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

        if (!gitService.isGitRepository(workDir)) {
            return CommandResult.error("当前目录非 Git 仓库");
        }

        String status = gitService.execGitPublic(workDir, "status", "--porcelain");
        if (status == null || status.isBlank()) {
            return CommandResult.text("没有可提交的变更");
        }

        if (args != null && !args.isBlank()) {
            String commitResult = gitService.execGitPublic(workDir, "commit", "-m", args);
            if (commitResult == null || commitResult.isBlank()) {
                return CommandResult.error("git commit 失败，请检查 Git 输出或本地钩子。");
            }
            return CommandResult.text("✅ 已提交:\n" + commitResult);
        }

        String stagedDiff = gitService.execGitPublic(workDir, "diff", "--cached", "--stat");
        String detailedDiff = gitService.execGitPublic(workDir, "diff", "--cached");

        List<String> changedFiles = new ArrayList<>();
        for (String line : status.split("\n")) {
            if (line.length() > 3) changedFiles.add(line.substring(3).trim());
        }

        return CommandResult.jsx(Map.of(
            "action", "gitCommitPreview",
            "status", status,
            "stagedDiff", stagedDiff != null ? stagedDiff : "",
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
