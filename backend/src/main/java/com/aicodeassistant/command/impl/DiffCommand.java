package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import com.aicodeassistant.service.GitService;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

@Component
public class DiffCommand implements Command {

    private final GitService gitService;

    public DiffCommand(GitService gitService) {
        this.gitService = gitService;
    }

    @Override public String getName() { return "diff"; }
    @Override public String getDescription() { return "显示 Git 差异"; }
    @Override public CommandType getType() { return CommandType.LOCAL_JSX; }

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

        String normalizedArgs = args == null ? "" : args.trim().toLowerCase(Locale.ROOT);
        boolean staged;
        if (normalizedArgs.isEmpty() || normalizedArgs.equals("unstaged")) {
            staged = false;
        } else if (normalizedArgs.equals("staged") || normalizedArgs.equals("--staged")) {
            staged = true;
        } else {
            return CommandResult.error("用法：/diff [unstaged|staged|--staged]");
        }

        String[] statArgs = staged
            ? new String[]{"diff", "--cached", "--stat"}
            : new String[]{"diff", "--stat"};
        String[] diffArgs = staged
            ? new String[]{"diff", "--cached"}
            : new String[]{"diff"};

        String stat = gitService.execGitPublic(workDir, statArgs);
        String diff = gitService.execGitPublic(workDir, diffArgs);

        if (stat == null || diff == null) {
            String failedParts;
            if (stat == null && diff == null) {
                failedParts = "stat 与正文";
            } else if (stat == null) {
                failedParts = "stat";
            } else {
                failedParts = "正文";
            }
            return CommandResult.error("读取 Git 差异失败（" + failedParts + "），请稍后重试。");
        }

        if (stat.isBlank() && diff.isBlank()) {
            return CommandResult.text("无差异");
        }

        // 先按完整 stat 计算文件数（stat 空白/仅表头时为 0），再对 stat 做截断
        long fileCount = Math.max(0, stat.lines().count() - 1);

        return CommandResult.jsx(Map.of(
            "action", "gitDiffView",
            "staged", staged,
            "stat", truncate(stat, 10000),
            "diff", truncate(diff, 10000),
            "fileCount", fileCount
        ));
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "\n...(已截断)" : text;
    }
}
