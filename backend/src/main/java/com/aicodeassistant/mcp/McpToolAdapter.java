package com.aicodeassistant.mcp;

import com.aicodeassistant.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

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
    private final String enhancedDescription;  // 注册表中文描述
    private final long timeoutMs;              // 注册表超时配置

    /** 原有构造函数 — 保持向后兼容 */
    public McpToolAdapter(String name, String description, Map<String, Object> inputSchema,
                          McpServerConnection connection, String originalToolName) {
        this(name, description, inputSchema, connection, originalToolName, null, 0);
    }

    /** 增强构造函数 — 支持描述覆盖和超时覆盖 */
    public McpToolAdapter(String name, String description, Map<String, Object> inputSchema,
                          McpServerConnection connection, String originalToolName,
                          String enhancedDescription, long timeoutMs) {
        this.name = name;
        this.description = description;
        this.inputSchema = inputSchema;
        this.connection = connection;
        this.originalToolName = originalToolName;
        this.enhancedDescription = enhancedDescription;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        if (enhancedDescription != null && !enhancedDescription.isEmpty()) {
            return enhancedDescription;
        }
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

    @Override
    public boolean isMcp() {
        return true;
    }

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        if (connection.getStatus() != McpConnectionStatus.CONNECTED) {
            return ToolResult.error("MCP server '" + connection.getName()
                    + "' is not connected (status: " + connection.getStatus() + ")");
        }

        try {
            // 一行代码，传输无关 — SSE/HTTP/WS/STDIO 全部走同一路径
            JsonNode result = connection.callTool(originalToolName, input.getRawData(), timeoutMs);

            // 解析 MCP 标准 content 数组
            if (result != null && result.has("content")) {
                StringBuilder sb = new StringBuilder();
                for (JsonNode item : result.get("content")) {
                    if ("text".equals(item.path("type").asText())) {
                        sb.append(item.get("text").asText());
                    }
                }
                String content = sb.toString();
                if (content.length() > MAX_MCP_RESULT_SIZE) {
                    content = content.substring(0, MAX_MCP_RESULT_SIZE)
                            + "\n[Truncated: exceeded " + MAX_MCP_RESULT_SIZE + " chars]";
                }
                return ToolResult.success(content)
                        .withMetadata("mcpServer", connection.getName())
                        .withMetadata("mcpTool", originalToolName);
            }

            String fallback = result != null ? result.toString() : "{}";
            return ToolResult.success(fallback)
                    .withMetadata("mcpServer", connection.getName())
                    .withMetadata("mcpTool", originalToolName);

        } catch (McpProtocolException e) {
            if (e.getCode() == JsonRpcError.REQUEST_TIMEOUT) {
                log.warn("MCP tool call timed out after {}ms: {} on {}",
                        timeoutMs, originalToolName, connection.getName());
                return ToolResult.error("MCP tool call timed out after " + timeoutMs + "ms");
            }
            log.error("MCP tool call failed: {} on {}", originalToolName, connection.getName(), e);
            return ToolResult.error("MCP error: " + e.getMessage());
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
