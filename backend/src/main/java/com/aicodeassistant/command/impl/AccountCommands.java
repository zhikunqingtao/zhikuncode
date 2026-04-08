package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 账户与用量命令 — §4.2.6
 * <p>
 * 包含 usage/extra-usage/rate-limit-options/upgrade/version。
 * login/logout/cost 已有独立实现。
 */
@Configuration
public class AccountCommands {

    @Bean
    Command usageCommand() {
        return new Command() {
            @Override public String getName() { return "usage"; }
            @Override public String getDescription() { return "用量统计报告"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                // P1: 从计费服务获取实际用量
                return CommandResult.text("Usage report:\n" +
                        "  Session tokens: (P1 — integrate with billing)\n" +
                        "  Daily usage: (P1)\n" +
                        "  Monthly usage: (P1)");
            }
        };
    }

    @Bean
    Command extraUsageCommand() {
        return new Command() {
            @Override public String getName() { return "extra-usage"; }
            @Override public String getDescription() { return "额外用量详情"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                return CommandResult.text("Extra usage details:\n" +
                        "  Tool calls: (P1)\n" +
                        "  File operations: (P1)\n" +
                        "  API requests: (P1)");
            }
        };
    }

    @Bean
    Command rateLimitOptionsCommand() {
        return new Command() {
            @Override public String getName() { return "rate-limit-options"; }
            @Override public String getDescription() { return "速率限制选项"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                return CommandResult.text("Rate limit options:\n" +
                        "  Current plan: (P1)\n" +
                        "  Requests/min: (P1)\n" +
                        "  Tokens/day: (P1)\n" +
                        "  Upgrade for higher limits: /upgrade");
            }
        };
    }

    @Bean
    Command upgradeCommand() {
        return new Command() {
            @Override public String getName() { return "upgrade"; }
            @Override public String getDescription() { return "版本升级"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                // P1: 检查最新版本并提示升级
                return CommandResult.text("Checking for updates... (P1 placeholder)\n" +
                        "Current version: (P1)\n" +
                        "Latest version: (P1)");
            }
        };
    }

    @Bean
    Command versionCommand() {
        return new Command() {
            @Override public String getName() { return "version"; }
            @Override public String getDescription() { return "版本信息"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                return CommandResult.text("Qoder AI Code Assistant\n" +
                        "  Version: 1.0.0-SNAPSHOT\n" +
                        "  Java: " + System.getProperty("java.version") + "\n" +
                        "  OS: " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
            }
        };
    }
}
