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
    public String prompt() {
        return """
                Reads a specific resource from an MCP server, identified by server name and \
                resource URI.
                
                Parameters:
                - server (required): The name of the MCP server from which to read the resource
                - uri (required): The URI of the resource to read
                
                Usage examples:
                - Read a resource: `readMcpResource({ server: "myserver", uri: "my-resource-uri" })`
                """;
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

            // 3. 调用 resources/read 获取真实内容
            try {
                String content = conn.readResource(uri);
                if (content.length() > MAX_MCP_RESULT_SIZE) {
                    content = content.substring(0, MAX_MCP_RESULT_SIZE)
                            + "\n[Content truncated at " + MAX_MCP_RESULT_SIZE / 1024 + "KB]";
                }
                return ToolResult.success(content);
            } catch (McpProtocolException e) {
                return ToolResult.error("Failed to read resource: " + e.getMessage());
            }

        } catch (Exception e) {
            return ToolResult.error("Failed to read resource '" + uri
                    + "' from server '" + serverName + "': " + e.getMessage());
        }
    }
}
