package com.aicodeassistant.mcp;

import com.aicodeassistant.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.TimeUnit;

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

    /**
     * ★ MCP 工具调用结果缓存 — 减少连接失败影响 (S-001)。
     * <p>
     * 策略: 按工具名+参数内容 hash 缓存成功结果，TTL 5分钟，最大 200 条。
     * 仅在连接不可用时返回缓存结果（降级模式），连接正常时始终调用远端。
     * 不缓存实时性工具（通过 isRealtimeTool 判断）。
     */
    private static final Cache<String, String> RESULT_CACHE = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .maximumSize(200)
            .build();

    /** 最大透明重连等待时间 (ms) */
    private static final long RECONNECT_WAIT_MS = 3000;

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
        String cacheKey = buildCacheKey(input);

        // ★ 连接不可用时：等待短暂重连，或降级返回缓存
        if (connection.getStatus() != McpConnectionStatus.CONNECTED) {
            // 尝试等待重连（DEGRADED/PENDING 状态可能正在重连中）
            if (connection.getStatus() == McpConnectionStatus.DEGRADED
                    || connection.getStatus() == McpConnectionStatus.PENDING) {
                if (waitForReconnect()) {
                    log.info("MCP server '{}' reconnected during wait, proceeding with call",
                            connection.getName());
                } else {
                    return fallbackToCacheOrError(cacheKey,
                            "MCP server '" + connection.getName()
                                    + "' not connected after wait (status: " + connection.getStatus() + ")");
                }
            } else {
                return fallbackToCacheOrError(cacheKey,
                        "MCP server '" + connection.getName()
                                + "' is not connected (status: " + connection.getStatus() + ")");
            }
        }

        try {
            // 传输无关 — SSE/HTTP/WS/STDIO 全部走同一路径
            JsonNode result = connection.callTool(originalToolName, input.getRawData(), timeoutMs);

            // 解析 MCP 标准 content 数组
            String content = extractContent(result);
            if (content.length() > MAX_MCP_RESULT_SIZE) {
                content = content.substring(0, MAX_MCP_RESULT_SIZE)
                        + "\n[Truncated: exceeded " + MAX_MCP_RESULT_SIZE + " chars]";
            }

            // ★ 缓存成功结果（非实时性工具）
            if (!isRealtimeTool() && !content.isEmpty()) {
                RESULT_CACHE.put(cacheKey, content);
            }

            return ToolResult.success(content)
                    .withMetadata("mcpServer", connection.getName())
                    .withMetadata("mcpTool", originalToolName);

        } catch (McpProtocolException e) {
            if (e.getCode() == JsonRpcError.REQUEST_TIMEOUT) {
                log.warn("MCP tool call timed out after {}ms: {} on {}",
                        timeoutMs, originalToolName, connection.getName());
                return fallbackToCacheOrError(cacheKey,
                        "MCP tool call timed out after " + timeoutMs + "ms");
            }
            log.error("MCP tool call failed: {} on {}", originalToolName, connection.getName(), e);
            return fallbackToCacheOrError(cacheKey, "MCP error: " + e.getMessage());
        } catch (Exception e) {
            log.error("MCP tool call failed: {} on {}", originalToolName, connection.getName(), e);
            return fallbackToCacheOrError(cacheKey, "MCP tool call failed: " + e.getMessage());
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

    /**
     * ★ 降级策略 — 连接失败时尝试返回缓存结果，否则返回错误。
     * 缓存命中时在结果中标记 [cached]，让 AI 知道这是缓存数据。
     */
    private ToolResult fallbackToCacheOrError(String cacheKey, String errorMsg) {
        if (!isRealtimeTool()) {
            String cached = RESULT_CACHE.getIfPresent(cacheKey);
            if (cached != null) {
                log.info("Returning cached result for MCP tool {} on {} (connection issue: {})",
                        originalToolName, connection.getName(), errorMsg);
                return ToolResult.success("[cached] " + cached)
                        .withMetadata("mcpServer", connection.getName())
                        .withMetadata("mcpTool", originalToolName)
                        .withMetadata("cached", "true");
            }
        }
        return ToolResult.error(errorMsg);
    }

    /**
     * 构建缓存 key — 工具名 + 参数内容 hash。
     */
    private String buildCacheKey(ToolInput input) {
        return originalToolName + ":" + input.getRawData().hashCode();
    }

    /**
     * 判断是否为实时性工具 — 实时工具不使用缓存。
     * WebSearch、实时数据查询等工具需要最新结果。
     */
    private boolean isRealtimeTool() {
        String lower = originalToolName.toLowerCase();
        return lower.contains("search") || lower.contains("web")
                || lower.contains("fetch") || lower.contains("browse")
                || lower.contains("realtime") || lower.contains("live");
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
