package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 配置与模式切换命令 — §4.2.3
 * <p>
 * 包含 fast/effort/output-style/plan/theme/color/vim/keybindings。
 * config/model/permissions 已有独立实现。
 */
@Configuration
public class ConfigModeCommands {

    @Bean
    Command fastCommand() {
        return new Command() {
            @Override public String getName() { return "fast"; }
            @Override public String getDescription() { return "启用/禁用 FastMode 低延迟模式"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                String mode = args.isBlank() ? "toggle" : args.trim().toLowerCase();
                return switch (mode) {
                    case "on", "enable" -> CommandResult.text("FastMode enabled. Using low-latency model.");
                    case "off", "disable" -> CommandResult.text("FastMode disabled. Using default model.");
                    default -> CommandResult.text("FastMode toggled. Current state: (P1 — read from config)");
                };
            }
        };
    }

    @Bean
    Command effortCommand() {
        return new Command() {
            @Override public String getName() { return "effort"; }
            @Override public String getDescription() { return "调整推理努力等级"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                if (args.isBlank()) {
                    return CommandResult.text("Usage: /effort <low|medium|high>\n" +
                            "  low    — 快速响应，减少推理深度\n" +
                            "  medium — 平衡模式（默认）\n" +
                            "  high   — 深度推理，更全面分析");
                }
                String level = args.trim().toLowerCase();
                return CommandResult.text("Effort level set to: " + level);
            }
        };
    }

    @Bean
    Command outputStyleCommand() {
        return new Command() {
            @Override public String getName() { return "output-style"; }
            @Override public String getDescription() { return "切换输出样式 (default/Explanatory/Learning)"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                if (args.isBlank()) {
                    return CommandResult.text("Usage: /output-style <default|explanatory|learning>\n" +
                            "  default      — 标准输出\n" +
                            "  explanatory  — 详细解释模式\n" +
                            "  learning     — 教学模式，包含步骤说明");
                }
                String style = args.trim().toLowerCase();
                return CommandResult.text("Output style set to: " + style);
            }
        };
    }

    @Bean
    Command planCommand() {
        return new Command() {
            @Override public String getName() { return "plan"; }
            @Override public String getDescription() { return "计划模式切换"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                // P1: 切换到 Plan 模式，只读协作
                return CommandResult.text("Plan mode activated. Read-only collaborative planning enabled.");
            }
        };
    }

    @Bean
    Command themeCommand() {
        return new Command() {
            @Override public String getName() { return "theme"; }
            @Override public String getDescription() { return "主题切换 (light/dark/system)"; }
            @Override public CommandType getType() { return CommandType.LOCAL_JSX; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                if (args.isBlank()) {
                    return CommandResult.jsx(Map.of(
                            "component", "ThemeSelector",
                            "options", java.util.List.of("light", "dark", "system")
                    ));
                }
                String theme = args.trim().toLowerCase();
                return CommandResult.text("Theme set to: " + theme);
            }
        };
    }

    @Bean
    Command colorCommand() {
        return new Command() {
            @Override public String getName() { return "color"; }
            @Override public String getDescription() { return "配置终端颜色方案"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                if (args.isBlank()) {
                    return CommandResult.text("Usage: /color <scheme-name>\n" +
                            "Available schemes: default, solarized, monokai, nord, dracula");
                }
                return CommandResult.text("Color scheme set to: " + args.trim());
            }
        };
    }

    @Bean
    Command vimCommand() {
        return new Command() {
            @Override public String getName() { return "vim"; }
            @Override public String getDescription() { return "启用/禁用 Vim 模式"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                String mode = args.isBlank() ? "toggle" : args.trim().toLowerCase();
                return switch (mode) {
                    case "on", "enable" -> CommandResult.text("Vim mode enabled.");
                    case "off", "disable" -> CommandResult.text("Vim mode disabled.");
                    default -> CommandResult.text("Vim mode toggled.");
                };
            }
        };
    }

    @Bean
    Command keybindingsCommand() {
        return new Command() {
            @Override public String getName() { return "keybindings"; }
            @Override public String getDescription() { return "查看/编辑键盘绑定配置"; }
            @Override public CommandType getType() { return CommandType.LOCAL_JSX; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                return CommandResult.jsx(Map.of(
                        "component", "KeybindingsEditor",
                        "mode", args.isBlank() ? "view" : "edit"
                ));
            }
        };
    }
}
