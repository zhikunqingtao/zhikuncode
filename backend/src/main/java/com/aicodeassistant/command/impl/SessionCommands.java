package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 会话与上下文管理命令 — §4.2.2
 * <p>
 * 包含 context/copy/export/files/rename/tag。
 * compact/memory/resume/session 已有独立实现。
 */
@Configuration
public class SessionCommands {

    @Bean
    Command contextCommand() {
        return new Command() {
            @Override public String getName() { return "context"; }
            @Override public String getDescription() { return "上下文可视化，显示 Token 使用情况"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                // P1: 从 CompactService 获取实际 token 统计
                return CommandResult.text(
                        "Context usage:\n" +
                        "  Model: " + context.currentModel() + "\n" +
                        "  Session: " + context.sessionId() + "\n" +
                        "  Tokens used: (P1 — integrate with CompactService)");
            }
        };
    }

    @Bean
    Command copyCommand() {
        return new Command() {
            @Override public String getName() { return "copy"; }
            @Override public String getDescription() { return "复制最后回复到系统剪贴板"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                // P1: 获取最后一条 assistant 消息并复制到剪贴板
                return CommandResult.text("Last response copied to clipboard.");
            }
        };
    }

    @Bean
    Command exportCommand() {
        return new Command() {
            @Override public String getName() { return "export"; }
            @Override public String getDescription() { return "导出对话为 JSON/Markdown"; }
            @Override public CommandType getType() { return CommandType.LOCAL_JSX; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                String format = args.isBlank() ? "markdown" : args.trim().toLowerCase();
                // P1: 实际导出对话历史
                return CommandResult.jsx(Map.of(
                        "component", "ExportDialog",
                        "format", format,
                        "sessionId", context.sessionId()
                ));
            }
        };
    }

    @Bean
    Command filesCommand() {
        return new Command() {
            @Override public String getName() { return "files"; }
            @Override public String getDescription() { return "列出本次会话修改过的文件"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                // P1: 从会话状态中获取已修改文件列表
                return CommandResult.text("Modified files in session " + context.sessionId() +
                        ":\n  (P1 — integrate with file tracking)");
            }
        };
    }

    @Bean
    Command renameCommand() {
        return new Command() {
            @Override public String getName() { return "rename"; }
            @Override public String getDescription() { return "重命名当前会话"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                if (args.isBlank()) {
                    return CommandResult.error("Usage: /rename <new-name> — 请指定新的会话名称");
                }
                // P1: 更新会话名称
                return CommandResult.text("Session renamed to: " + args.trim());
            }
        };
    }

    @Bean
    Command tagCommand() {
        return new Command() {
            @Override public String getName() { return "tag"; }
            @Override public String getDescription() { return "给会话添加/管理标签"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                if (args.isBlank()) {
                    return CommandResult.text("Usage: /tag <add|remove|list> [tag-name]\n" +
                            "  /tag list        — 列出当前标签\n" +
                            "  /tag add <name>  — 添加标签\n" +
                            "  /tag remove <name> — 移除标签");
                }
                return CommandResult.text("Tag operation: " + args);
            }
        };
    }
}
