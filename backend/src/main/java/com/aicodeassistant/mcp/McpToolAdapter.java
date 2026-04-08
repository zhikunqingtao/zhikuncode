package com.aicodeassistant.mcp;

import com.aicodeassistant.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;

/**
 * MCP 工具适配器 — 将 MCP 服务器暴露的工具包装为内部 Tool 接口。
 * <p>
 * 工具名称格式: {@code mcp__<serverName>__<toolName>}
 * <p>
 * 结果截断保护: 超过 1MB 的结果自动截断。
 *
 * @see <a href="SPEC §4.3.4">MCP 工具包装</a>
 */
public class McpToolAdapter implements Tool {

    private static final Logger log = LoggerFactory.getLogger(McpToolAdapter.class);
    static final int MAX_MCP_RESULT_SIZE = 1024 * 1024; // 1MB

    private final String name;
    private final String description;
    private final Map<String, Object> inputSchema;
    private final McpServerConnection connection;
    private final String originalToolName;

    public McpToolAdapter(String name, String description, Map<String, Object> inputSchema,
                          McpServerConnection connection, String originalToolName) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
        this.connection = connection;
        this.originalToolName = originalToolName;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description != null ? description : "MCP tool: " + originalToolName;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return inputSchema != null ? inputSchema : Map.of("type", "object");
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
    public boolean isConcurrencySafe(ToolInput input) {
        return false; // MCP 工具默认不并发安全
    }

    @Override
    public boolean shouldDefer() {
        return true;
    }

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        if (connection.getStatus() != McpConnectionStatus.CONNECTED) {
            return ToolResult.error("MCP server '" + connection.getName()
                    + "' is not connected (status: " + connection.getStatus() + ")");
        }

        try {
            // 1. 构建 JSON-RPC 请求
            Map<String, Object> request = Map.of(
                    "jsonrpc", "2.0",
                    "id", UUID.randomUUID().toString(),
                    "method", "tools/call",
                    "params", Map.of(
                            "name", this.originalToolName,
                            "arguments", input.getRawData()
                    )
            );
            String requestJson = objectMapper.writeValueAsString(request);

            // 2. 发送到 MCP server 的 stdin
            connection.sendRequest(requestJson);

            // 3. 从 stdout 读取 JSON-RPC 响应
            String responseLine = connection.readResponse();
            if (responseLine == null) {
                return ToolResult.error("MCP server '" + connection.getName() + "' closed connection");
            }

            // 4. 解析响应
            JsonNode response = objectMapper.readTree(responseLine);
            if (response.has("error")) {
                JsonNode error = response.get("error");
                String errorMsg = error.has("message") ? error.get("message").asText() : error.toString();
                return ToolResult.error("MCP error: " + errorMsg);
            }

            JsonNode result = response.get("result");
            if (result != null && result.has("content")) {
                // MCP 标准: content 是数组
                StringBuilder sb = new StringBuilder();
                for (JsonNode item : result.get("content")) {
                    if ("text".equals(item.path("type").asText())) {
                        sb.append(item.get("text").asText());
                    }
                }
                String content = sb.toString();

                // 内容截断保护
                if (content.length() > MAX_MCP_RESULT_SIZE) {
                    content = content.substring(0, MAX_MCP_RESULT_SIZE)
                            + "\n[Truncated: result exceeded " + MAX_MCP_RESULT_SIZE + " chars]";
                }

                return ToolResult.success(content)
                        .withMetadata("mcpServer", connection.getName())
                        .withMetadata("mcpTool", originalToolName);
            }

            // 没有 content 字段 — 返回原始 result
            String fallback = result != null ? result.toString() : "{}";
            return ToolResult.success(fallback)
                    .withMetadata("mcpServer", connection.getName())
                    .withMetadata("mcpTool", originalToolName);

        } catch (Exception e) {
            log.error("MCP tool call failed: {} on {}", originalToolName, connection.getName(), e);
            return ToolResult.error("MCP tool call failed: " + e.getMessage());
        }
    }

    /** 获取原始工具名 */
    public String getOriginalToolName() {
        return originalToolName;
    }

    /** 获取所属服务器名 */
    public String getServerName() {
        return connection.getName();
    }
}
