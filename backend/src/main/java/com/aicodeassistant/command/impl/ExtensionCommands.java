package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 工具与扩展管理命令 — §4.2.4
 * <p>
 * 包含 mcp/hooks/skills/plugin/reload-plugins/agent/tasks。
 */
@Configuration
public class ExtensionCommands {

    @Bean
    Command mcpCommand() {
        return new Command() {
            @Override public String getName() { return "mcp"; }
            @Override public String getDescription() { return "MCP 服务器管理 (add/remove/restart/logs)"; }
            @Override public CommandType getType() { return CommandType.LOCAL_JSX; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                if (args.isBlank()) {
                    return CommandResult.jsx(Map.of(
                            "component", "McpManager",
                            "action", "list"
                    ));
                }
                String[] parts = args.trim().split("\\s+", 2);
                String action = parts[0].toLowerCase();
                String target = parts.length > 1 ? parts[1] : "";
                return CommandResult.jsx(Map.of(
                        "component", "McpManager",
                        "action", action,
                        "target", target
                ));
            }
        };
    }

    @Bean
    Command hooksCommand() {
        return new Command() {
            @Override public String getName() { return "hooks"; }
            @Override public String getDescription() { return "查看/编辑 Hook 配置"; }
            @Override public CommandType getType() { return CommandType.LOCAL_JSX; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                return CommandResult.jsx(Map.of(
                        "component", "HooksEditor",
                        "mode", args.isBlank() ? "view" : "edit"
                ));
            }
        };
    }

    @Bean
    Command skillsCommand() {
        return new Command() {
            @Override public String getName() { return "skills"; }
            @Override public String getDescription() { return "查看/管理已安装的技能插件"; }
            @Override public CommandType getType() { return CommandType.LOCAL_JSX; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                return CommandResult.jsx(Map.of(
                        "component", "SkillsManager",
                        "action", args.isBlank() ? "list" : args.trim()
                ));
            }
        };
    }

    @Bean
    Command pluginCommand() {
        return new Command() {
            @Override public String getName() { return "plugin"; }
            @Override public String getDescription() { return "插件管理（安装/卸载/列表）"; }
            @Override public CommandType getType() { return CommandType.LOCAL_JSX; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                if (args.isBlank()) {
                    return CommandResult.jsx(Map.of(
                            "component", "PluginManager",
                            "action", "list"
                    ));
                }
                String[] parts = args.trim().split("\\s+", 2);
                return CommandResult.jsx(Map.of(
                        "component", "PluginManager",
                        "action", parts[0],
                        "target", parts.length > 1 ? parts[1] : ""
                ));
            }
        };
    }

    @Bean
    Command reloadPluginsCommand() {
        return new Command() {
            @Override public String getName() { return "reload-plugins"; }
            @Override public String getDescription() { return "重新加载所有插件"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                // P1: 重新扫描并加载插件
                return CommandResult.text("Reloading all plugins... Done. (P1 placeholder)");
            }
        };
    }

    @Bean
    Command agentCommand() {
        return new Command() {
            @Override public String getName() { return "agent"; }
            @Override public String getDescription() { return "查看/管理子代理和后台代理"; }
            @Override public CommandType getType() { return CommandType.LOCAL_JSX; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                return CommandResult.jsx(Map.of(
                        "component", "AgentManager",
                        "action", args.isBlank() ? "list" : args.trim()
                ));
            }
        };
    }

    @Bean
    Command tasksCommand() {
        return new Command() {
            @Override public String getName() { return "tasks"; }
            @Override public String getDescription() { return "任务管理（列出/查看/取消后台任务）"; }
            @Override public CommandType getType() { return CommandType.LOCAL_JSX; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                if (args.isBlank()) {
                    return CommandResult.jsx(Map.of(
                            "component", "TaskManager",
                            "action", "list"
                    ));
                }
                String[] parts = args.trim().split("\\s+", 2);
                return CommandResult.jsx(Map.of(
                        "component", "TaskManager",
                        "action", parts[0],
                        "taskId", parts.length > 1 ? parts[1] : ""
                ));
            }
        };
    }
}
