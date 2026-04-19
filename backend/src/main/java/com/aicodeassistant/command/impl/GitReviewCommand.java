package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import com.aicodeassistant.service.GitService;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class GitReviewCommand implements Command {

    private final GitService gitService;

    public GitReviewCommand(GitService gitService) {
        this.gitService = gitService;
    }

    @Override public String getName() { return "review"; }
    @Override public String getDescription() { return "AI 代码审查当前变更"; }
    @Override public CommandType getType() { return CommandType.PROMPT; }

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

        String diff = gitService.execGitPublic(workDir, "diff");
        String stagedDiff = gitService.execGitPublic(workDir, "diff", "--cached");
        String fullDiff = (diff != null ? diff : "") + "\n" + (stagedDiff != null ? stagedDiff : "");

        if (fullDiff.isBlank()) {
            return CommandResult.text("没有待审查的变更");
        }

        String prompt = String.format("""
            请对以下代码变更进行审查，从以下维度评估:
            1. 🐛 Bug 风险：空指针、资源泄漏、逻辑错误
            2. 🔒 安全漏洞：注入、越权、敏感数据暴露
            3. ⚡ 性能问题：N+1 查询、内存分配、死循环
            4. 📐 代码规范：命名、结构、重复代码、单一职责
            5. 🧪 测试覆盖建议：缺失的边界场景、回归测试

            对每个发现给出严重级别（高/中/低）和具体修复建议。

            ```diff
            %s
            ```
            """, truncate(fullDiff, 8000));

        return CommandResult.text(prompt);
    }

    private String truncate(String text, int maxLen) {
        return text.length() > maxLen ? text.substring(0, maxLen) + "\n...(已截断)" : text;
    }
}
