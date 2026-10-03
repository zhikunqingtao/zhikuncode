package com.aicodeassistant.mcp;

import com.aicodeassistant.engine.AbortContext;
import com.aicodeassistant.mcp.progress.McpProgressTracker;
import com.aicodeassistant.mcp.schema.SchemaCompressor;
import com.aicodeassistant.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * MCP 工具适配器 — 将 MCP 服务器暴露的工具包装为内部 Tool 接口。
 * <p>
 * 工具名称格式: {@code mcp__<serverName>__<toolName>}
 * <p>
 * 结果截断保护: 超过 1MB 的结果自动截断。
 *
 */
public class McpToolAdapter implements Tool {

    private static final Logger log = LoggerFactory.getLogger(McpToolAdapter.class);
    static final int MAX_MCP_RESULT_SIZE = 1024 * 1024; // 1MB

    /** 最大透明重连等待时间 (ms) */
    private static final long RECONNECT_WAIT_MS = 3000;

    private final String name;
    private final String description;
    private final Map<String, Object> fullInputSchema;     // 原始 schema
    private final Map<String, Object> compressedInputSchema; // 压缩后 schema（用于 LLM 上下文）
    private final McpServerConnection connection;
    private final String originalToolName;
    private final String enhancedDescription;  // 注册表中文描述
    private final long timeoutMs;              // 注册表超时配置

    /** M4 进度追踪器 — 可为 null（未注入时跳过 progressToken 注入）。 */
    private final McpProgressTracker progressTracker;

    /** M4 AbortContext 查找函数 — sessionId → AbortContext，可为 null。 */
    private final Function<String, AbortContext> abortContextLookup;

    /** 原有构造函数 — 保持向后兼容（不做 schema 压缩） */
    public McpToolAdapter(String name, String description, Map<String, Object> inputSchema,
                          McpServerConnection connection, String originalToolName) {
        this(name, description, inputSchema, connection, originalToolName, null, 0, null, null, null);
    }

    /** 增强构造函数 — 支持描述覆盖和超时覆盖（不做 schema 压缩） */
    public McpToolAdapter(String name, String description, Map<String, Object> inputSchema,
                          McpServerConnection connection, String originalToolName,
                          String enhancedDescription, long timeoutMs) {
        this(name, description, inputSchema, connection, originalToolName,
                enhancedDescription, timeoutMs, null, null, null);
    }

    /**
     * M5 构造函数 — 支持 inputSchema 压缩（无 M4 进度接入）。
     * <p>
     * 保留以兼容现有 wrapMcpTools 调用点。
     */
    public McpToolAdapter(String name, String description, Map<String, Object> inputSchema,
                          McpServerConnection connection, String originalToolName,
                          String enhancedDescription, long timeoutMs,
                          SchemaCompressor schemaCompressor) {
        this(name, description, inputSchema, connection, originalToolName,
                enhancedDescription, timeoutMs, schemaCompressor, null, null);
    }

    /**
     * 完整构造函数 — 支持 schema 压缩 + M4 长操作支持（progress / abort）。
     */
    public McpToolAdapter(String name, String description, Map<String, Object> inputSchema,
                          McpServerConnection connection, String originalToolName,
                          String enhancedDescription, long timeoutMs,
                          SchemaCompressor schemaCompressor,
                          McpProgressTracker progressTracker,
                          Function<String, AbortContext> abortContextLookup) {
        this.name = name;
        this.description = description;
        this.fullInputSchema = inputSchema;
        this.compressedInputSchema = (schemaCompressor != null && inputSchema != null)
                ? schemaCompressor.compress(inputSchema)
                : inputSchema;
        this.connection = connection;
        this.originalToolName = originalToolName;
        this.enhancedDescription = enhancedDescription;
        this.timeoutMs = timeoutMs;
        this.progressTracker = progressTracker;
        this.abortContextLookup = abortContextLookup;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public long getMaxExecutionTimeMs() {
        return 300_000L; // 5 minutes for external MCP server calls
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
        return compressedInputSchema != null ? compressedInputSchema : Map.of("type", "object");
    }

    /**
     * 返回原始（未压缩）schema — 供需要完整描述/示例时按需加载。
     */
    public Map<String, Object> getFullSchema() {
        return fullInputSchema != null ? fullInputSchema : Map.of("type", "object");
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
        // 连接不可用时：等待短暂重连，仍不可用则返回错误
        if (connection.getStatus() != McpConnectionStatus.CONNECTED) {
            // 尝试等待重连（DEGRADED/PENDING 状态可能正在重连中）
            if (connection.getStatus() == McpConnectionStatus.DEGRADED
                    || connection.getStatus() == McpConnectionStatus.PENDING) {
                if (waitForReconnect()) {
                    log.info("MCP server '{}' reconnected during wait, proceeding with call",
                            connection.getName());
                } else {
                    return ToolResult.networkError("MCP_CONNECTION_UNAVAILABLE",
                            "MCP server '" + connection.getName()
                                    + "' not connected after wait (status: " + connection.getStatus() + ")",
                            ToolResult.Retryability.NEVER, ToolResult.EffectState.UNKNOWN);
                }
            } else {
                return ToolResult.networkError("MCP_CONNECTION_UNAVAILABLE",
                        "MCP server '" + connection.getName()
                                + "' is not connected (status: " + connection.getStatus() + ")",
                        ToolResult.Retryability.NEVER, ToolResult.EffectState.UNKNOWN);
            }
        }

        // M4: 生成 progressToken 并注册追踪 + abort 回调
        String progressToken = UUID.randomUUID().toString();
        String sessionId = context != null ? context.sessionId() : null;
        boolean tracked = false;
        if (progressTracker != null && sessionId != null) {
            progressTracker.registerProgress(progressToken, sessionId,
                    connection.getName(), originalToolName);
            tracked = true;
        }
        if (abortContextLookup != null && sessionId != null) {
            try {
                AbortContext abortCtx = abortContextLookup.apply(sessionId);
                if (abortCtx != null) {
                    abortCtx.onAbortDo(() ->
                            connection.sendCancelNotification(progressToken, "user_cancelled"));
                }
            } catch (Exception e) {
                log.debug("AbortContext lookup failed for session {}: {}", sessionId, e.getMessage());
            }
        }

        try {
            // 传输无关 — SSE/HTTP/WS/STDIO 全部走同一路径，携带 progressToken。
            JsonNode result = connection.callTool(originalToolName, input.getRawData(),
                    timeoutMs, progressToken);

            // 解析 MCP 标准 content 数组
            String content = extractContent(result);
            JsonNode isError = result != null ? result.get("isError") : null;
            boolean toolReportedError = isError != null && isError.isBoolean() && isError.booleanValue();
            if (toolReportedError) {
                // 在截断前保留错误身份，供仅传递正文的下游请求使用。
                content = "MCP tool reported an error (MCP_TOOL_REPORTED_ERROR); effects are unknown:\n"
                        + content;
            }
            if (content.length() > MAX_MCP_RESULT_SIZE) {
                content = content.substring(0, MAX_MCP_RESULT_SIZE)
                        + "\n[Truncated: exceeded " + MAX_MCP_RESULT_SIZE + " chars]";
            }

            ToolResult toolResult = toolReportedError
                    ? ToolResult.failed(ToolResult.ToolFailureType.PROVIDER, "MCP_TOOL_REPORTED_ERROR",
                            content, ToolResult.Retryability.NEVER, ToolResult.EffectState.UNKNOWN, null, Map.of())
                    : ToolResult.success(content);
            return toolResult
                    .withMetadata("mcpServer", connection.getName())
                    .withMetadata("mcpTool", originalToolName);

        } catch (McpProtocolException e) {
            if (e.getCode() == JsonRpcError.REQUEST_TIMEOUT) {
                log.warn("MCP tool call timed out after {}ms: {} on {}",
                        timeoutMs, originalToolName, connection.getName());
                return ToolResult.networkError("MCP_CALL_DEADLINE_EXCEEDED",
                        "MCP tool call timed out after " + timeoutMs + "ms",
                        ToolResult.Retryability.NEVER, ToolResult.EffectState.UNKNOWN);
            }
            log.error("MCP tool call failed: {} on {}", originalToolName, connection.getName(), e);
            return ToolResult.networkError("MCP_PROTOCOL_ERROR", "MCP error: " + e.getMessage(),
                    ToolResult.Retryability.NEVER, ToolResult.EffectState.UNKNOWN);
        } catch (Exception e) {
            log.error("MCP tool call failed: {} on {}", originalToolName, connection.getName(), e);
            return ToolResult.networkError("MCP_TRANSPORT_ERROR", "MCP tool call failed: " + e.getMessage(),
                    ToolResult.Retryability.NEVER, ToolResult.EffectState.UNKNOWN);
        } finally {
            // M4: 资源清理 — 无论成功/失败/超时都需 unregister
            if (tracked) {
                progressTracker.unregisterProgress(progressToken);
            }
        }
    }

    /**
     * 提取 MCP 响应中的文本内容。
     */
    private String extractContent(JsonNode result) {
        if (result != null && result.has("content")) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : result.get("content")) {
                if ("text".equals(item.path("type").asText())) {
                    sb.append(item.get("text").asText());
                }
            }
            return sb.toString();
        }
        return result != null ? result.toString() : "{}";
    }

    /**
     * ★ 等待短暂重连 — 在 DEGRADED/PENDING 状态时轮询等待连接恢复。
     * 最多等待 RECONNECT_WAIT_MS (3s)，每 200ms 检查一次。
     */
    private boolean waitForReconnect() {
        long deadline = System.currentTimeMillis() + RECONNECT_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (connection.getStatus() == McpConnectionStatus.CONNECTED) {
                return true;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return connection.getStatus() == McpConnectionStatus.CONNECTED;
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
