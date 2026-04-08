package com.aicodeassistant.state;

import com.aicodeassistant.mcp.McpServerConnection;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具状态 — 启用的工具、进行中的调用、工具池、MCP 服务器。
 *
 * @see <a href="SPEC §3.5.1">AppState 结构 - 工具状态 / MCP 状态</a>
 */
public record ToolState(
        Set<String> enabledTools,
        Set<String> inProgressToolUseIds,
        List<String> toolPool,
        Map<String, Integer> toolCallCounts,
        List<McpServerConnection> mcpServers,
        Map<String, String> mcpServerStatus
) {
    public static ToolState empty() {
        return new ToolState(
                Set.of(), Set.of(), List.of(), Map.of(), List.of(), Map.of()
        );
    }

    public ToolState withEnabledTools(Set<String> tools) {
        return new ToolState(tools, inProgressToolUseIds, toolPool, toolCallCounts, mcpServers, mcpServerStatus);
    }

    public ToolState withInProgressToolUseIds(Set<String> ids) {
        return new ToolState(enabledTools, ids, toolPool, toolCallCounts, mcpServers, mcpServerStatus);
    }

    public ToolState withToolCallCounts(Map<String, Integer> counts) {
        return new ToolState(enabledTools, inProgressToolUseIds, toolPool, counts, mcpServers, mcpServerStatus);
    }

    public ToolState withMcpServers(List<McpServerConnection> servers) {
        return new ToolState(enabledTools, inProgressToolUseIds, toolPool, toolCallCounts, servers, mcpServerStatus);
    }
}
