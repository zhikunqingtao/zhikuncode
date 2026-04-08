package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * 环境与集成命令 — §4.2.5
 * <p>
 * 包含 add-dir/ide/chrome/desktop/mobile/terminal-setup/remote-env/install-github-app/install-slack-app。
 * doctor 已有独立实现。
 */
@Configuration
public class EnvironmentCommands {

    @Bean
    Command addDirCommand() {
        return new Command() {
            @Override public String getName() { return "add-dir"; }
            @Override public List<String> getAliases() { return List.of("adddir"); }
            @Override public String getDescription() { return "将额外目录加入工作区"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                if (args.isBlank()) {
                    return CommandResult.error("Usage: /add-dir <path> — 请指定目录路径");
                }
                // P1: 将目录添加到工作区配置
                return CommandResult.text("Directory added to workspace: " + args.trim());
            }
        };
    }

    @Bean
    Command ideCommand() {
        return new Command() {
            @Override public String getName() { return "ide"; }
            @Override public String getDescription() { return "IDE 集成管理"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                if (args.isBlank()) {
                    return CommandResult.text("IDE integration status:\n" +
                            "  Bridge: " + (context.isBridgeMode() ? "connected" : "not connected") + "\n" +
                            "  Supported: VS Code, IntelliJ IDEA, Vim/Neovim");
                }
                return CommandResult.text("IDE integration: " + args);
            }
        };
    }

    @Bean
    Command chromeCommand() {
        return new Command() {
            @Override public String getName() { return "chrome"; }
            @Override public String getDescription() { return "Chrome 浏览器集成"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                return CommandResult.text("Chrome integration: " +
                        (args.isBlank() ? "status check (P1 placeholder)" : args));
            }
        };
    }

    @Bean
    Command desktopCommand() {
        return new Command() {
            @Override public String getName() { return "desktop"; }
            @Override public String getDescription() { return "桌面模式切换"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                return CommandResult.text("Desktop mode: " +
                        (args.isBlank() ? "toggled" : args.trim()));
            }
        };
    }

    @Bean
    Command mobileCommand() {
        return new Command() {
            @Override public String getName() { return "mobile"; }
            @Override public String getDescription() { return "移动端模式切换"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                return CommandResult.text("Mobile mode: " +
                        (args.isBlank() ? "toggled" : args.trim()));
            }
        };
    }

    @Bean
    Command terminalSetupCommand() {
        return new Command() {
            @Override public String getName() { return "terminal-setup"; }
            @Override public String getDescription() { return "终端 Shell 集成设置"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                return CommandResult.text("Terminal setup:\n" +
                        "  Shell: detected from environment\n" +
                        "  Integration: (P1 — install shell hooks)");
            }
        };
    }

    @Bean
    Command remoteEnvCommand() {
        return new Command() {
            @Override public String getName() { return "remote-env"; }
            @Override public String getDescription() { return "远程环境配置"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                if (args.isBlank()) {
                    return CommandResult.text("Remote environment: " +
                            (context.isRemoteMode() ? "active" : "not configured") + "\n" +
                            "Usage: /remote-env <connect|disconnect|status> [host]");
                }
                return CommandResult.text("Remote environment: " + args);
            }
        };
    }

    @Bean
    Command installGithubAppCommand() {
        return new Command() {
            @Override public String getName() { return "install-github-app"; }
            @Override public String getDescription() { return "GitHub App 安装引导"; }
            @Override public CommandType getType() { return CommandType.LOCAL_JSX; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                return CommandResult.jsx(Map.of(
                        "component", "GitHubAppInstaller",
                        "step", "start"
                ));
            }
        };
    }

    @Bean
    Command installSlackAppCommand() {
        return new Command() {
            @Override public String getName() { return "install-slack-app"; }
            @Override public String getDescription() { return "Slack App 安装引导"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                return CommandResult.text("Slack App installation guide:\n" +
                        "  1. Visit the Slack App Directory\n" +
                        "  2. Search for 'Qoder'\n" +
                        "  3. Click 'Add to Slack'\n" +
                        "  4. Authorize the app (P1 placeholder)");
            }
        };
    }
}
