package com.aicodeassistant.mcp;

import com.aicodeassistant.tool.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * ListMcpResourcesTool — 列出指定 MCP 服务器暴露的所有资源。
 *
 * @see <a href="SPEC §4.1.18">ListMcpResourcesTool</a>
 */
@Component
public class ListMcpResourcesTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ListMcpResourcesTool.class);

    private final McpClientManager mcpClientManager;

    public ListMcpResourcesTool(McpClientManager mcpClientManager) {
        this.mcpClientManager = mcpClientManager;
    }

    @Override
    public String getName() {
        return "ListMcpResources";
    }

    @Override
    public String getDescription() {
        return "List all resources exposed by MCP servers. " +
                "Optionally filter by server name.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "server", Map.of(
                                "type", "string",
                                "description", "MCP server name (optional, lists all if omitted)")
                )
        );
    }

    @Override
    public String getGroup() {
        return "mcp";
    }

    @Override
    public PermissionRequirement getPermissionRequirement() {
        return PermissionRequirement.NONE;
    }

    @Override
    public boolean isReadOnly(ToolInput input) {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(ToolInput input) {
        return true;
    }

    @Override
    public boolean shouldDefer() {
        return true;
    }

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        String serverFilter = input.getString("server", null);

        // 1. 获取目标连接
        List<McpServerConnection> connections;
        if (serverFilter != null && !serverFilter.isEmpty()) {
            connections = mcpClientManager.getConnection(serverFilter)
                    .map(List::of).orElse(List.of());
        } else {
            connections = mcpClientManager.getConnectedServers();
        }

        if (connections.isEmpty()) {
            return ToolResult.success(serverFilter != null
                    ? "No MCP server found with name: " + serverFilter
                    : "No MCP servers connected.");
        }

        // 2. 遍历连接，收集资源
        List<Map<String, String>> allResources = new ArrayList<>();
        for (McpServerConnection conn : connections) {
            try {
                for (var res : conn.getResources()) {
                    allResources.add(Map.of(
                            "uri", res.uri(),
                            "name", res.name(),
                            "mimeType", res.mimeType() != null ? res.mimeType() : "unknown",
                            "description", res.description() != null ? res.description() : "",
                            "server", conn.getName()
                    ));
                }
            } catch (Exception e) {
                log.warn("Failed to list resources from MCP server '{}': {}",
                        conn.getName(), e.getMessage());
            }
        }

        if (allResources.isEmpty()) {
            return ToolResult.success("No resources found on connected MCP servers.");
        }

        // 3. 格式化输出
        StringBuilder sb = new StringBuilder();
        sb.append("MCP Resources (").append(allResources.size()).append("):\n\n");
        for (Map<String, String> res : allResources) {
            sb.append("- **").append(res.get("name")).append("** (")
                    .append(res.get("server")).append(")\n");
            sb.append("  URI: ").append(res.get("uri")).append("\n");
            sb.append("  Type: ").append(res.get("mimeType")).append("\n");
            String desc = res.get("description");
            if (desc != null && !desc.isEmpty()) {
                sb.append("  ").append(desc).append("\n");
            }
        }
        return ToolResult.success(sb.toString());
    }
}
