package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.*;
import com.aicodeassistant.mcp.McpClientManager;
import com.aicodeassistant.mcp.McpConnectionStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class McpCommands {

    @Bean
    public Command mcpServersCommand(McpClientManager mcpManager) {
        return new Command() {
            @Override public String getName() { return "mcp-servers"; }
            @Override public List<String> getAliases() { return List.of(); }
            @Override public String getDescription() { return "列出所有已连接的 MCP 服务器"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                var connections = mcpManager.listConnections();
                StringBuilder sb = new StringBuilder("## MCP 服务器 (" + connections.size() + ")\n\n");
                connections.forEach(conn ->
                    sb.append("- **").append(conn.getName()).append("**: ")
                      .append(conn.getStatus() == McpConnectionStatus.CONNECTED ? "✅" : "❌")
                      .append(" (工具: ").append(conn.getTools().size()).append(")\n"));
                return CommandResult.text(sb.toString());
            }
        };
    }

    @Bean
    public Command mcpToolsCommand(McpClientManager mcpManager) {
        return new Command() {
            @Override public String getName() { return "mcp-tools"; }
            @Override public List<String> getAliases() { return List.of(); }
            @Override public String getDescription() { return "列出所有 MCP 提供的工具"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                var tools = mcpManager.discoverAndWrapTools();
                StringBuilder sb = new StringBuilder("## MCP 工具 (" + tools.size() + ")\n\n");
                tools.forEach(t -> sb.append("- **").append(t.getName())
                        .append("**: ").append(t.getDescription()).append("\n"));
                return CommandResult.text(sb.toString());
            }
        };
    }

    @Bean
    public Command mcpResourcesCommand(McpClientManager mcpManager) {
        return new Command() {
            @Override public String getName() { return "mcp-resources"; }
            @Override public List<String> getAliases() { return List.of(); }
            @Override public String getDescription() { return "列出所有 MCP 提供的资源"; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override
            public CommandResult execute(String args, CommandContext context) {
                var connectedServers = mcpManager.getConnectedServers();
                var allResources = connectedServers.stream()
                        .flatMap(c -> c.getResources().stream())
                        .toList();
                StringBuilder sb = new StringBuilder("## MCP 资源 (" + allResources.size() + ")\n\n");
                allResources.forEach(r -> sb.append("- **").append(r.name())
                        .append("**: ").append(r.uri()).append("\n"));
                return CommandResult.text(sb.toString());
            }
        };
    }
}
