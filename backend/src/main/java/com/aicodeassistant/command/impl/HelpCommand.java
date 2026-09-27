package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;
import java.util.LinkedHashMap;

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

        // 构建结构化分组数据
        List<Map<String, Object>> groups = new ArrayList<>();
        addGroupData(groups, "Local Commands", "本地命令",
                grouped.getOrDefault(CommandType.LOCAL, List.of()));
        addGroupData(groups, "Interactive Commands", "交互命令",
                grouped.getOrDefault(CommandType.LOCAL_JSX, List.of()));
        addGroupData(groups, "Prompt Commands", "提示词命令",
                grouped.getOrDefault(CommandType.PROMPT, List.of()));

        return CommandResult.jsx(Map.of(
                "action", "helpCommandList",
                "groups", groups,
                "total", visible.size()
        ));
    }

    private void addGroupData(List<Map<String, Object>> groups, String title, String titleZh,
                              List<Command> commands) {
        if (commands.isEmpty()) return;
        List<Map<String, Object>> items = new ArrayList<>();
        for (Command cmd : commands) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", cmd.getName());
            item.put("description", cmd.getDescription());
            item.put("aliases", cmd.getAliases().stream().map(a -> "/" + a)
                    .collect(Collectors.toList()));
            items.add(item);
        }
        groups.add(Map.of("title", title, "titleZh", titleZh, "commands", items));
    }

    private CommandResult showCommandDetail(String commandName) {
        Optional<Command> cmdOpt = registry.findCommand(commandName)
                .filter(Command::isAvailable);
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
