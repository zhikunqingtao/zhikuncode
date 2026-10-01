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
            @Override public String getDescription() { return "FastMode 低延迟模式（尚未接入）"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                // FastMode 尚未接入：任何参数（含无参 toggle、on/off）都不改变设置
                return CommandResult.text("FastMode is not wired up yet — no settings were changed.");
            }
        };
    }

    @Bean
    Command effortCommand() {
        return new Command() {
            @Override public String getName() { return "effort"; }
            @Override public String getDescription() { return "推理努力等级（尚未接入）"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                // 推理努力等级尚未接入：无虚构默认值，任何参数都不改变设置
                return CommandResult.text("Effort levels are not wired up yet — no settings were changed.");
            }
        };
    }

    @Bean
    Command outputStyleCommand() {
        return new Command() {
            @Override public String getName() { return "output-style"; }
            @Override public String getDescription() { return "输出样式（尚未接入）"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                // 输出样式尚未接入：任何参数都不改变设置
                return CommandResult.text("Output styles are not wired up yet — no settings were changed.");
            }
        };
    }

    // planCommand 已由 PlanCommand.java (@Component) 提供完整实现，此处不再重复注册

    @Bean
    Command themeCommand() {
        return new Command() {
            @Override public String getName() { return "theme"; }
            @Override public String getDescription() { return "主题切换：请使用外观设置"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                if (args.isBlank()) {
                    // 无参也不弹出虚构选择器：主题设置未接线，引导外观设置
                    return CommandResult.text("Theme settings are not wired here — use the appearance settings to change the theme.");
                }
                // 该命令不改变主题：真实主题由前端外观设置写入并持久化
                return CommandResult.text("Theme was not changed. Use the appearance settings to change the theme.");
            }
        };
    }

    @Bean
    Command colorCommand() {
        return new Command() {
            @Override public String getName() { return "color"; }
            @Override public String getDescription() { return "终端颜色方案（尚未接入）"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                // 颜色方案尚未接入：不展示虚构方案清单，任何参数都不改变设置
                return CommandResult.text("Color schemes are not wired up yet — no settings were changed.");
            }
        };
    }

    @Bean
    Command vimCommand() {
        return new Command() {
            @Override public String getName() { return "vim"; }
            @Override public String getDescription() { return "Vim 模式（尚未接入）"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                // Vim 模式尚未接入：任何参数（含无参 toggle、on/off）都不改变设置
                return CommandResult.text("Vim mode is not wired up yet — no settings were changed.");
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
