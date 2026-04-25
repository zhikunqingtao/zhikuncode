package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Set;

/**
 * Git 与代码管理命令 — §4.2.1
 * <p>
 * 包含 commit/review/commit-push-pr/branch/pr_comments/rewind/security-review。
 * diff 命令已在 {@link DiffCommand} 中实现。
 */
@Configuration
public class GitCommands {

    @Bean
    Command commitPushPrCommand() {
        return new PromptCommand() {
            @Override public String getName() { return "commit-push-pr"; }
            @Override public String getDescription() { return "自动提交 + 推送 + 创建 PR"; }
            @Override public ContentLength getContentLength() { return ContentLength.LONG; }
            @Override public Set<String> getAllowedTools() { return Set.of("Bash", "Read"); }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                String prompt = "Please commit current changes, push to remote, and create a Pull Request. " +
                        "Generate an appropriate commit message and PR description. " +
                        (args.isBlank() ? "" : "PR details: " + args);
                return CommandResult.text(prompt);
            }
        };
    }

    @Bean
    Command branchCommand() {
        return new Command() {
            @Override public String getName() { return "branch"; }
            @Override public String getDescription() { return "创建/切换/列出分支"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                if (args.isBlank()) {
                    return CommandResult.text("Usage: /branch [create|switch|list] [name]\n" +
                            "  /branch list          — 列出所有分支\n" +
                            "  /branch create <name> — 创建新分支\n" +
                            "  /branch switch <name> — 切换分支");
                }
                // P1: 实际 git 分支操作
                return CommandResult.text("Branch operation: " + args);
            }
        };
    }

    @Bean
    Command prCommentsCommand() {
        return new Command() {
            @Override public String getName() { return "pr_comments"; }
            @Override public List<String> getAliases() { return List.of("pr-comments"); }
            @Override public String getDescription() { return "查看 PR 评论"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                // P1: 从 GitHub/GitLab API 获取 PR 评论
                return CommandResult.text("PR comments: fetching from remote... (P1 placeholder)");
            }
        };
    }

    @Bean
    Command rewindCommand() {
        return new Command() {
            @Override public String getName() { return "rewind"; }
            @Override public String getDescription() { return "撤销文件变更到指定检查点"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                if (args.isBlank()) {
                    return CommandResult.error("Usage: /rewind <checkpoint> — 请指定要回退到的检查点");
                }
                // P1: 实际文件回退操作
                return CommandResult.text("Rewinding to checkpoint: " + args);
            }
        };
    }

    @Bean
    Command securityReviewCommand() {
        return new PromptCommand() {
            @Override public String getName() { return "security-review"; }
            @Override public String getDescription() { return "安全审查（分析潜在漏洞）"; }
            @Override public ContentLength getContentLength() { return ContentLength.LONG; }
            @Override public Set<String> getAllowedTools() { return Set.of("Bash", "Read", "Glob"); }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                String prompt = "Please perform a security review of the current code changes. " +
                        "Check for: SQL injection, XSS, CSRF, authentication bypass, " +
                        "sensitive data exposure, insecure dependencies, and other OWASP Top 10 vulnerabilities. " +
                        (args.isBlank() ? "" : "Focus on: " + args);
                return CommandResult.text(prompt);
            }
        };
    }
}
