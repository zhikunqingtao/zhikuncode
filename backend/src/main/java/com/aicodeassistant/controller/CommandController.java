package com.aicodeassistant.controller;

import com.aicodeassistant.command.Command;
import com.aicodeassistant.command.CommandRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.List;

/**
 * 命令列表 API — 支持前端命令自动补全。
 *
 * @see <a href="SPEC §4.4">命令自动补全</a>
 */
@RestController
public class CommandController {

    private final CommandRegistry commandRegistry;

    public CommandController(CommandRegistry commandRegistry) {
        this.commandRegistry = commandRegistry;
    }

    @GetMapping("/api/commands")
    public List<CommandInfo> listCommands() {
        return commandRegistry.getVisibleCommands().stream()
                .map(cmd -> new CommandInfo(
                        cmd.getName(),
                        cmd.getDescription(),
                        null,
                        cmd.getType().name().toLowerCase()))
                .sorted(Comparator.comparing(CommandInfo::name))
                .toList();
    }

    public record CommandInfo(String name, String description, String usage, String category) {}
}
