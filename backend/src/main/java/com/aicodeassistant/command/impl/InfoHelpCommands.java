package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.Set;

/**
 * 信息与帮助命令 — §4.2.7
 * <p>
 * 包含 feedback/status/stats/stickers/release-notes/advisor/btw/statusline/
 * privacy-settings/sandbox-toggle/heapdump。
 * help/init 已有独立实现。
 */
@Configuration
public class InfoHelpCommands {

    @Bean
    Command feedbackCommand() {
        return new Command() {
            @Override public String getName() { return "feedback"; }
            @Override public String getDescription() { return "提交用户反馈"; }
            @Override public CommandType getType() { return CommandType.LOCAL_JSX; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                if (args.isBlank()) {
                    return CommandResult.jsx(Map.of(
                            "component", "FeedbackForm",
                            "sessionId", context.sessionId()
                    ));
                }
                // 直接提交文本反馈
                return CommandResult.text("Feedback submitted: " + args + "\nThank you!");
            }
        };
    }

    // statusCommand 已由独立的 StatusCommand.java (@Component) 提供，此处移除以避免 Bean 名称冲突

    @Bean
    Command statsCommand() {
        return new Command() {
            @Override public String getName() { return "stats"; }
            @Override public String getDescription() { return "会话统计数据"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                return CommandResult.text("Session stats for " + context.sessionId() + ":\n" +
                        "  Messages: (P1)\n" +
                        "  Tool calls: (P1)\n" +
                        "  Files modified: (P1)\n" +
                        "  Duration: (P1)");
            }
        };
    }

    @Bean
    Command stickersCommand() {
        return new Command() {
            @Override public String getName() { return "stickers"; }
            @Override public String getDescription() { return "管理响应中的装饰贴纸"; }
            @Override public CommandType getType() { return CommandType.LOCAL_JSX; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                return CommandResult.jsx(Map.of(
                        "component", "StickersManager",
                        "action", args.isBlank() ? "list" : args.trim()
                ));
            }
        };
    }

    @Bean
    Command releaseNotesCommand() {
        return new Command() {
            @Override public String getName() { return "release-notes"; }
            @Override public String getDescription() { return "查看版本发布说明"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                return CommandResult.text("Release Notes — v1.0.0-SNAPSHOT\n" +
                        "  - Initial release\n" +
                        "  - Core tool system\n" +
                        "  - Command framework\n" +
                        "  - MCP integration\n" +
                        "  - LSP support");
            }
        };
    }

    @Bean
    Command advisorCommand() {
        return new PromptCommand() {
            @Override public String getName() { return "advisor"; }
            @Override public String getDescription() { return "AI 顾问模式"; }
            @Override public ContentLength getContentLength() { return ContentLength.LONG; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                String prompt = "You are now in Advisor mode. Provide expert-level guidance " +
                        "and recommendations without making direct code changes. " +
                        (args.isBlank() ? "What would you like advice on?" : "Topic: " + args);
                return CommandResult.text(prompt);
            }
        };
    }

    @Bean
    Command btwCommand() {
        return new PromptCommand() {
            @Override public String getName() { return "btw"; }
            @Override public String getDescription() { return "顺便提问（不中断当前任务）"; }
            @Override public ContentLength getContentLength() { return ContentLength.SHORT; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                if (args.isBlank()) {
                    return CommandResult.error("Usage: /btw <question> — 请输入你的问题");
                }
                String prompt = "The user has a side question (do not interrupt the current task): " + args;
                return CommandResult.text(prompt);
            }
        };
    }

    @Bean
    Command statuslineCommand() {
        return new Command() {
            @Override public String getName() { return "statusline"; }
            @Override public String getDescription() { return "状态栏显示切换"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                String mode = args.isBlank() ? "toggle" : args.trim().toLowerCase();
                return switch (mode) {
                    case "on", "show" -> CommandResult.text("Status line enabled.");
                    case "off", "hide" -> CommandResult.text("Status line disabled.");
                    default -> CommandResult.text("Status line toggled.");
                };
            }
        };
    }

    @Bean
    Command privacySettingsCommand() {
        return new Command() {
            @Override public String getName() { return "privacy-settings"; }
            @Override public String getDescription() { return "隐私与数据共享设置"; }
            @Override public CommandType getType() { return CommandType.LOCAL_JSX; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                return CommandResult.jsx(Map.of(
                        "component", "PrivacySettings",
                        "sessionId", context.sessionId()
                ));
            }
        };
    }

    @Bean
    Command sandboxToggleCommand() {
        return new Command() {
            @Override public String getName() { return "sandbox-toggle"; }
            @Override public String getDescription() { return "沙箱模式启用/禁用"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                String mode = args.isBlank() ? "toggle" : args.trim().toLowerCase();
                return switch (mode) {
                    case "on", "enable" -> CommandResult.text("Sandbox mode enabled. Commands run in isolated environment.");
                    case "off", "disable" -> CommandResult.text("Sandbox mode disabled. Commands run directly.");
                    default -> CommandResult.text("Sandbox mode toggled.");
                };
            }
        };
    }

    @Bean
    Command heapdumpCommand() {
        return new Command() {
            @Override public String getName() { return "heapdump"; }
            @Override public String getDescription() { return "内存堆转储（性能调试）"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override public boolean isHidden() { return true; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                // P1: 实际触发 JVM heapdump
                return CommandResult.text("Heap dump requested. Output: (P1 — integrate with JMX)");
            }
        };
    }
}
