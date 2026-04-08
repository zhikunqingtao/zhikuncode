package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 条件可用命令 — §4.2.8
 * <p>
 * 需要特性开关或运行时条件才可用的命令：
 * bridge/voice/buddy/passes/torch/fork/peers/workflows/ultrareview。
 */
@Configuration
public class ConditionalCommands {

    @Bean
    Command bridgeCommand() {
        return new Command() {
            @Override public String getName() { return "bridge"; }
            @Override public String getDescription() { return "IDE 桥接管理"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override public CommandAvailability getAvailability() { return CommandAvailability.REQUIRES_BRIDGE; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                if (!context.isBridgeMode()) {
                    return CommandResult.error("Bridge mode is not active. Connect from an IDE first.");
                }
                return CommandResult.text("Bridge status: connected\n" +
                        "  IDE: (P1 — detect IDE type)\n" +
                        "  Commands: " + args);
            }
        };
    }

    @Bean
    Command voiceCommand() {
        return new Command() {
            @Override public String getName() { return "voice"; }
            @Override public String getDescription() { return "语音输入模式"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override public CommandAvailability getAvailability() { return CommandAvailability.REQUIRES_FEATURE; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                String mode = args.isBlank() ? "toggle" : args.trim().toLowerCase();
                return switch (mode) {
                    case "on", "start" -> CommandResult.text("Voice input mode activated. Listening...");
                    case "off", "stop" -> CommandResult.text("Voice input mode deactivated.");
                    default -> CommandResult.text("Voice mode toggled.");
                };
            }
        };
    }

    @Bean
    Command buddyCommand() {
        return new Command() {
            @Override public String getName() { return "buddy"; }
            @Override public String getDescription() { return "伙伴系统交互"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override public CommandAvailability getAvailability() { return CommandAvailability.REQUIRES_FEATURE; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                return CommandResult.text("Buddy system: " +
                        (args.isBlank() ? "status check (P1 placeholder)" : args));
            }
        };
    }

    @Bean
    Command passesCommand() {
        return new Command() {
            @Override public String getName() { return "passes"; }
            @Override public String getDescription() { return "多轮通行证管理"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override public CommandAvailability getAvailability() { return CommandAvailability.REQUIRES_FEATURE; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                return CommandResult.text("Passes:\n" +
                        "  Available: (P1)\n" +
                        "  Used: (P1)\n" +
                        "  Remaining: (P1)");
            }
        };
    }

    @Bean
    Command torchCommand() {
        return new Command() {
            @Override public String getName() { return "torch"; }
            @Override public String getDescription() { return "知识传递"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override public CommandAvailability getAvailability() { return CommandAvailability.REQUIRES_FEATURE; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                if (args.isBlank()) {
                    return CommandResult.text("Usage: /torch <topic> — 传递知识上下文给后续会话");
                }
                return CommandResult.text("Knowledge torch set: " + args.trim());
            }
        };
    }

    @Bean
    Command forkCommand() {
        return new Command() {
            @Override public String getName() { return "fork"; }
            @Override public String getDescription() { return "子代理分叉管理"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override public CommandAvailability getAvailability() { return CommandAvailability.REQUIRES_FEATURE; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                if (args.isBlank()) {
                    return CommandResult.text("Usage: /fork <task-description> — 创建子代理分叉执行任务");
                }
                return CommandResult.text("Forking sub-agent for: " + args.trim());
            }
        };
    }

    @Bean
    Command peersCommand() {
        return new Command() {
            @Override public String getName() { return "peers"; }
            @Override public String getDescription() { return "代理间通信管理"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override public CommandAvailability getAvailability() { return CommandAvailability.REQUIRES_FEATURE; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                return CommandResult.text("Peer agents:\n" +
                        "  Connected: (P1)\n" +
                        "  Messages: (P1)");
            }
        };
    }

    @Bean
    Command workflowsCommand() {
        return new Command() {
            @Override public String getName() { return "workflows"; }
            @Override public String getDescription() { return "工作流脚本管理"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override public CommandAvailability getAvailability() { return CommandAvailability.REQUIRES_FEATURE; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                if (args.isBlank()) {
                    return CommandResult.text("Usage: /workflows <list|run|create> [name]\n" +
                            "  /workflows list          — 列出工作流\n" +
                            "  /workflows run <name>    — 执行工作流\n" +
                            "  /workflows create <name> — 创建工作流");
                }
                return CommandResult.text("Workflow operation: " + args);
            }
        };
    }

    @Bean
    Command ultrareviewCommand() {
        return new PromptCommand() {
            @Override public String getName() { return "ultrareview"; }
            @Override public String getDescription() { return "review 增强版（深度审查）"; }
            @Override public CommandAvailability getAvailability() { return CommandAvailability.REQUIRES_FEATURE; }
            @Override public ContentLength getContentLength() { return ContentLength.LONG; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                String prompt = "Perform an ultra-deep code review. Analyze: " +
                        "architecture, security, performance, maintainability, test coverage, " +
                        "error handling, concurrency safety, and API design. " +
                        (args.isBlank() ? "" : "Focus on: " + args);
                return CommandResult.text(prompt);
            }
        };
    }
}
