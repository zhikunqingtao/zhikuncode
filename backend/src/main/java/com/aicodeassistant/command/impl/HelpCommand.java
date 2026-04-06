package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * /help [command_name] — 显示所有可用命令列表或指定命令的详细帮助。
 * <p>
 * 流程:
 * <ol>
 *     <li>commandName 为空 → 返回所有可见命令列表 (按类别分组)</li>
 *     <li>commandName 非空 → 查找命令 → 返回详细用法</li>
 *     <li>命令不存在 → 模糊匹配建议 (Levenshtein)</li>
 * </ol>
 *
 * @see <a href="SPEC §3.3.2">/help 命令</a>
 */
@Component
public class HelpCommand implements Command {

    private final CommandRegistry registry;

    public HelpCommand(@org.springframework.context.annotation.Lazy CommandRegistry registry) {
        this.registry = registry;
    }

    @Override public String getName() { return "help"; }
    @Override public String getDescription() { return "Show available commands"; }
    @Override public CommandType getType() { return CommandType.LOCAL; }

    @Override
    public CommandResult execute(String args, CommandContext context) {
        if (args != null && !args.isBlank()) {
            return showCommandDetail(args.trim());
        }
        return showCommandList();
    }

    private CommandResult showCommandList() {
        List<Command> visible = registry.getVisibleCommands();

        // 按类型分组
        Map<CommandType, List<Command>> grouped = visible.stream()
                .collect(Collectors.groupingBy(Command::getType));

        StringBuilder sb = new StringBuilder();
        sb.append("Available Commands:\n\n");

        // LOCAL 命令
        appendGroup(sb, "Local Commands", grouped.getOrDefault(CommandType.LOCAL, List.of()));
        // LOCAL_JSX 命令
        appendGroup(sb, "Interactive Commands", grouped.getOrDefault(CommandType.LOCAL_JSX, List.of()));
        // PROMPT 命令
        appendGroup(sb, "Prompt Commands", grouped.getOrDefault(CommandType.PROMPT, List.of()));

        sb.append("\nType /help <command> for detailed help on a specific command.");
        return CommandResult.text(sb.toString());
    }

    private void appendGroup(StringBuilder sb, String title, List<Command> commands) {
        if (commands.isEmpty()) return;
        sb.append("  ").append(title).append(":\n");
        for (Command cmd : commands) {
            sb.append("    /").append(String.format("%-20s", cmd.getName()))
                    .append(cmd.getDescription()).append("\n");
            if (!cmd.getAliases().isEmpty()) {
                sb.append("      aliases: ").append(
                        cmd.getAliases().stream().map(a -> "/" + a)
                                .collect(Collectors.joining(", "))).append("\n");
            }
        }
        sb.append("\n");
    }

    private CommandResult showCommandDetail(String commandName) {
        Optional<Command> cmdOpt = registry.findCommand(commandName);
        if (cmdOpt.isEmpty()) {
            String suggestion = registry.suggestCommands(commandName);
            return CommandResult.error("Unknown command: /" + commandName + ". " + suggestion);
        }

        Command cmd = cmdOpt.get();
        StringBuilder sb = new StringBuilder();
        sb.append("/" ).append(cmd.getName()).append(" — ").append(cmd.getDescription()).append("\n\n");
        sb.append("  Type:      ").append(cmd.getType()).append("\n");
        sb.append("  Version:   ").append(cmd.getVersion()).append("\n");

        if (!cmd.getAliases().isEmpty()) {
            sb.append("  Aliases:   ").append(
                    cmd.getAliases().stream().map(a -> "/" + a)
                            .collect(Collectors.joining(", "))).append("\n");
        }

        sb.append("  Immediate: ").append(cmd.isImmediate()).append("\n");
        sb.append("  Hidden:    ").append(cmd.isHidden()).append("\n");

        if (cmd instanceof PromptCommand pc) {
            sb.append("  Content:   ").append(pc.getContentLength()).append("\n");
            if (pc.getAllowedTools() != null) {
                sb.append("  Tools:     ").append(pc.getAllowedTools()).append("\n");
            }
        }

        return CommandResult.text(sb.toString());
    }
}
