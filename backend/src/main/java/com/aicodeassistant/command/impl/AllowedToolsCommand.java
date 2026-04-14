package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import com.aicodeassistant.tool.ToolRegistry;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AllowedToolsCommand implements Command {

    private final ToolRegistry toolRegistry;

    public AllowedToolsCommand(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @Override public String getName() { return "allowed-tools"; }
    @Override public List<String> getAliases() { return List.of(); }
    @Override public String getDescription() { return "显示当前可用工具列表"; }
    @Override public CommandType getType() { return CommandType.LOCAL; }

    @Override
    public CommandResult execute(String args, CommandContext context) {
        var tools = toolRegistry.getEnabledTools();
        StringBuilder sb = new StringBuilder("## 可用工具 (" + tools.size() + ")\n\n");
        tools.forEach(t -> sb.append("- **").append(t.getName())
                .append("**: ").append(t.getDescription()).append("\n"));
        return CommandResult.text(sb.toString());
    }
}
