package com.aicodeassistant.mcp;

import com.aicodeassistant.tool.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * ReadMcpResourceTool — 读取指定 MCP 资源的内容。
 * <p>
 * 文本内容截断保护: 超过 512KB 自动截断。
 *
 * @see <a href="SPEC §4.1.19">ReadMcpResourceTool</a>
 */
@Component
public class ReadMcpResourceTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(ReadMcpResourceTool.class);
    static final int MAX_MCP_RESULT_SIZE = 512 * 1024; // 512KB

    private final McpClientManager mcpClientManager;

    public ReadMcpResourceTool(McpClientManager mcpClientManager) {
        this.mcpClientManager = mcpClientManager;
    }

    @Override
    public String getName() {
        return "ReadMcpResource";
    }

    @Override
    public String getDescription() {
        return "Read the content of a specific MCP resource by server name and URI.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "server", Map.of(
                                "type", "string",
                                "description", "MCP server name"),
                        "uri", Map.of(
                                "type", "string",
                                "description", "Resource URI")
                ),
                "required", List.of("server", "uri")
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
        String serverName = input.getString("server");
        String uri = input.getString("uri");

        // 1. 获取目标服务器连接
        var connOpt = mcpClientManager.getConnection(serverName);
        if (connOpt.isEmpty()) {
            return ToolResult.error("MCP server not found: " + serverName);
        }
        McpServerConnection conn = connOpt.get();

        if (conn.getStatus() != McpConnectionStatus.CONNECTED) {
            return ToolResult.error("MCP server '" + serverName
                    + "' is not connected (status: " + conn.getStatus() + ")");
        }

        // 2. 查找资源
        try {
            var resourceOpt = conn.getResources().stream()
                    .filter(r -> uri.equals(r.uri()))
                    .findFirst();

            if (resourceOpt.isEmpty()) {
                return ToolResult.error("Resource not found: " + uri
                        + " on server '" + serverName + "'");
            }

            var resource = resourceOpt.get();

            // 3. P1 简化: 返回资源元信息（实际内容读取需要 MCP SDK）
            String content = String.format(
                    "Resource: %s\nURI: %s\nType: %s\nDescription: %s\n\n"
                            + "[P1 placeholder: actual content reading requires MCP Java SDK integration]",
                    resource.name(), resource.uri(),
                    resource.mimeType() != null ? resource.mimeType() : "unknown",
                    resource.description() != null ? resource.description() : "N/A");

            // 4. 截断保护
            if (content.length() > MAX_MCP_RESULT_SIZE) {
                content = content.substring(0, MAX_MCP_RESULT_SIZE)
                        + "\n[Content truncated at " + MAX_MCP_RESULT_SIZE / 1024 + "KB]";
            }

            return ToolResult.success(content);

        } catch (Exception e) {
            return ToolResult.error("Failed to read resource '" + uri
                    + "' from server '" + serverName + "': " + e.getMessage());
        }
    }
}
