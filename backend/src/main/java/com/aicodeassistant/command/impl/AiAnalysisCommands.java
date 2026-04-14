package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class AiAnalysisCommands {

    @Bean
    public Command bugHunterCommand() {
        return new PromptCommand() {
            @Override public String getName() { return "bug-hunter"; }
            @Override public List<String> getAliases() { return List.of("bughunt"); }
            @Override public String getDescription() { return "智能扫描当前项目的潜在 Bug"; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                String scope = (args == null || args.isBlank()) ? "." : args;
                return CommandResult.text("Analyze the codebase for potential bugs including "
                    + "null pointer risks, resource leaks, concurrency issues. Scope: " + scope);
            }
        };
    }

    @Bean
    public Command prReviewCommand() {
        return new PromptCommand() {
            @Override public String getName() { return "pr-review"; }
            @Override public List<String> getAliases() { return List.of("review-pr"); }
            @Override public String getDescription() { return "智能审查当前 Git diff 或指定 PR"; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                String target = (args == null || args.isBlank()) ? "HEAD" : args;
                return CommandResult.text("Review the git diff for '" + target
                    + "' and provide code quality feedback.");
            }
        };
    }

    @Bean
    public Command securityScanCommand() {
        return new PromptCommand() {
            @Override public String getName() { return "security-scan"; }
            @Override public List<String> getAliases() { return List.of("sec-scan"); }
            @Override public String getDescription() { return "扫描项目安全漏洞（注入、XSS、硬编码密码等）"; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                String scope = (args == null || args.isBlank()) ? "." : args;
                return CommandResult.text("Perform security scan. Check for: SQL injection, XSS, "
                    + "hardcoded credentials, path traversal. Scope: " + scope);
            }
        };
    }

    @Bean
    public Command explainCommand() {
        return new PromptCommand() {
            @Override public String getName() { return "explain"; }
            @Override public List<String> getAliases() { return List.of(); }
            @Override public String getDescription() { return "解释指定的代码片段或概念"; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                String target = (args == null || args.isBlank()) ? "当前上下文" : args;
                return CommandResult.text("Please explain in detail: " + target);
            }
        };
    }
}
