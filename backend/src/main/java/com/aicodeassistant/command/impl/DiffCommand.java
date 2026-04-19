package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import com.aicodeassistant.service.GitService;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
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

        if (!gitService.isGitRepository(workDir)) {
            return CommandResult.error("当前目录非 Git 仓库");
        }

        boolean staged = args != null && args.contains("staged");

        String[] statArgs = staged
            ? new String[]{"diff", "--cached", "--stat"}
            : new String[]{"diff", "--stat"};
        String[] diffArgs = staged
            ? new String[]{"diff", "--cached"}
            : new String[]{"diff"};

        String stat = gitService.execGitPublic(workDir, statArgs);
        String diff = gitService.execGitPublic(workDir, diffArgs);

        if ((stat == null || stat.isBlank()) && (diff == null || diff.isBlank())) {
            return CommandResult.text("无差异");
        }

        return CommandResult.jsx(Map.of(
            "action", "gitDiffView",
            "staged", staged,
            "stat", stat != null ? stat : "",
            "diff", truncate(diff, 10000),
            "fileCount", stat != null ? stat.lines().count() - 1 : 0
        ));
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "\n...(已截断)" : text;
    }
}
