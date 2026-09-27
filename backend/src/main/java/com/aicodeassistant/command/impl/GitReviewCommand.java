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

        CommandResult denied = GitCommandGuard.requireRepositoryRoot(
                gitService, context);
        if (denied != null) return denied;

        // 带参数时把审查要求原样交给模型，不预取 diff。
        if (args != null && !args.isBlank()) {
            return CommandResult.text("""
                    请按以下用户要求进行代码审查，只审查、不修改文件。
                    以用户指定的比较对象、文件范围和排除项为准。
                    只有未指定比较对象时，才默认审查范围内的当前本地变更：
                    已暂存、未暂存及未跟踪文件；明确排除的部分不纳入。
                    先确定范围，再通过现有工具读取必要内容，不预取排除文件的正文。
                    无法读取指定比较对象时说明原因，不自行换成其他比较对象。
                    工具失败或材料不完整时说明未覆盖部分，不能声称全部审完。

                    本次用户要求：
                    %s
                    """.formatted(args));
        }

        String diff = gitService.execGitPublic(workDir, "diff");
        String stagedDiff = gitService.execGitPublic(workDir, "diff", "--cached");
        if (diff == null || stagedDiff == null) {
            String failed = diff == null && stagedDiff == null ? "未暂存和已暂存差异"
                    : diff == null ? "未暂存差异" : "已暂存差异";
            return CommandResult.error("读取" + failed + "失败，本次尚未完成审查，请重试。");
        }

        // 两段预览共享 8000 字符预算（只计 diff 原文，标题/截断标记不占预算）。
        int diffChars = Math.min(diff.length(), 8000 - Math.min(stagedDiff.length(), 4000));
        int stagedChars = Math.min(stagedDiff.length(), 8000 - diffChars);
        String unstagedSection = diff.isBlank()
                ? "未暂存差异预览:\n该部分未见差异"
                : "未暂存差异预览:\n```diff\n" + truncate(diff, diffChars) + "\n```";
        String stagedSection = stagedDiff.isBlank()
                ? "已暂存差异预览:\n该部分未见差异"
                : "已暂存差异预览:\n```diff\n" + truncate(stagedDiff, stagedChars) + "\n```";

        String prompt = """
            请对以下代码变更进行审查，从以下维度评估:
            以下只是已跟踪文件的未暂存/已暂存差异预览，未跟踪文件尚未核验。
            先核对文件范围；使用 git status --short 与 git ls-files --others --exclude-standard
            等现有工具检查未跟踪文件，再按必要性读取正文。不要自动上传全部未跟踪文件。
            预览标记"已截断"时，按文件继续读取必要 diff；未暂存与已暂存版本分别判断，
            同一文件存在两种版本时，不把工作区文件内容当作暂存区内容。
            只审查、不修改；范围外资料、读取失败和未完成部分必须明确说明。
            仅在相关集合均检查后才能说"没有待审查的变更"或"全部审完"。
            1. 🐛 Bug 风险：空指针、资源泄漏、逻辑错误
            2. 🔒 安全漏洞：注入、越权、敏感数据暴露
            3. ⚡ 性能问题：N+1 查询、内存分配、死循环
            4. 📐 代码规范：命名、结构、重复代码、单一职责
            5. 🧪 测试覆盖建议：缺失的边界场景、回归测试

            对每个发现给出严重级别（高/中/低）和具体修复建议。

            %s

            %s
            """.formatted(unstagedSection, stagedSection);

        return CommandResult.text(prompt);
    }

    private String truncate(String text, int maxLen) {
        return text.length() > maxLen ? text.substring(0, maxLen) + "\n...(已截断)" : text;
    }
}
