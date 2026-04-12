package com.aicodeassistant.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * MCP 服务器连接状态 — 封装连接、工具和资源信息。
 * <p>
 * 持有统一 McpTransport 实例，消除传输类型 switch 分支。
 *
 * @see McpTransport
 * @see <a href="SPEC §4.3.3">MCP 客户端管理</a>
 */
public class McpServerConnection {

    private static final Logger log = LoggerFactory.getLogger(McpServerConnection.class);

    private final McpServerConfig config;
    private volatile McpConnectionStatus status;
    private volatile List<McpToolDefinition> tools;
    private volatile List<McpResourceDefinition> resources;
    private volatile int reconnectAttempts;

    private static final int MAX_RECONNECT_ATTEMPTS = 3;
    private static final long DEFAULT_REQUEST_TIMEOUT_MS = 30_000;

    /** 统一传输实例 — 替代原来的 process/stdinWriter/stdoutReader/httpTransport/wsTransport */
    private volatile McpTransport transport;

    public McpServerConnection(McpServerConfig config) {
        this.config = config;
        this.status = McpConnectionStatus.PENDING;
        this.tools = List.of();
        this.resources = List.of();
        this.reconnectAttempts = 0;
    }

    /**
     * 连接到 MCP 服务器 — 通过工厂方法创建对应传输实例。
     */
    public void connect() {
        try {
            // 重连安全: 关闭旧 transport 防止资源泄漏 (connectWithRetry 场景)
            if (transport != null) {
                try { transport.close(); } catch (Exception ignored) {}
            }
            this.transport = createTransport(config);
            if (transport == null) {
                // 不支持的传输类型 — 容错标记为 CONNECTED (与原始行为一致)
                this.status = McpConnectionStatus.CONNECTED;
                return;
            }
            transport.connect().get(30, TimeUnit.SECONDS);
            this.status = McpConnectionStatus.CONNECTED;
            log.info("MCP server '{}' connected via {}", config.name(), config.type());
        } catch (Exception e) {
            log.error("Failed to connect MCP server '{}': {}", config.name(), e.getMessage());
            this.status = McpConnectionStatus.FAILED;
        }
    }

    /** 传输工厂 — 根据配置类型创建对应传输实例 */
    private static McpTransport createTransport(McpServerConfig config) {
        return switch (config.type()) {
            case STDIO      -> new McpStdioTransport(config);
            case SSE, SSE_IDE -> createSseTransport(config);
            case HTTP        -> new McpStreamableHttpTransport(config.url());
            case WS, WS_IDE  -> new McpWebSocketTransport(
                    config.url().replace("http://", "ws://").replace("https://", "wss://"));
            default -> {
                // SDK, CLAUDEAI_PROXY 等尚未实现的传输类型 — 容错处理
                log.warn("Unsupported transport type '{}' for MCP server '{}', no transport created",
                        config.type(), config.name());
                yield null;
            }
        };
    }

    /** SSE 传输创建 — 处理 URL 格式 + Headers 注入 */
    private static McpSseTransport createSseTransport(McpServerConfig config) {
        // McpSseTransport 构造需要 baseUrl (不含 /sse)，它会自动追加 /sse
        String baseUrl = config.url().endsWith("/sse")
                ? config.url().substring(0, config.url().length() - 4)
                : config.url();
        // 注入自定义 headers (Authorization 等)
        okhttp3.OkHttpClient.Builder builder = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .readTimeout(java.time.Duration.ZERO)
                .writeTimeout(java.time.Duration.ofSeconds(10));
        if (config.headers() != null && !config.headers().isEmpty()) {
            builder.addInterceptor(chain -> {
                okhttp3.Request.Builder req = chain.request().newBuilder();
                config.headers().forEach(req::header);
                return chain.proceed(req.build());
            });
        }
        return new McpSseTransport(baseUrl, builder.build());
    }

    /**
     * 传输无关的工具调用 — 委托给底层 McpTransport。
     * SSE/HTTP/WS/STDIO 全部通过同一代码路径执行。
     *
     * @param toolName   MCP 工具原始名称
     * @param arguments  工具参数
     * @param timeoutMs  超时 (ms), ≤0 使用默认值
     * @return JSON-RPC result 字段
     * @throws McpProtocolException 通信或协议错误
     */
    public JsonNode callTool(String toolName, Map<String, Object> arguments, long timeoutMs)
            throws McpProtocolException {
        if (status != McpConnectionStatus.CONNECTED || transport == null) {
            throw new McpProtocolException(new JsonRpcError(
                    JsonRpcError.SERVER_NOT_INITIALIZED,
                    "Server '" + config.name() + "' not connected (status: " + status + ")"));
        }
        return transport.sendRequest("tools/call",
                Map.of("name", toolName, "arguments", arguments),
                timeoutMs > 0 ? timeoutMs : DEFAULT_REQUEST_TIMEOUT_MS);
    }

    /**
     * 通用 JSON-RPC 请求 — 替代已删除的 sendRequestAndWait()。
     * 供内部调用 tools/list、resources/list 等 MCP 协议方法。
     */
    public JsonNode request(String method, Object params, long timeoutMs) throws McpProtocolException {
        if (transport == null) {
            throw new McpProtocolException(new JsonRpcError(
                    JsonRpcError.SERVER_NOT_INITIALIZED,
                    "Server '" + config.name() + "' has no transport"));
        }
        return transport.sendRequest(method, params,
                timeoutMs > 0 ? timeoutMs : DEFAULT_REQUEST_TIMEOUT_MS);
    }

    /** 通用请求 — 使用默认超时 */
    public JsonNode request(String method, Object params) throws McpProtocolException {
        return request(method, params, DEFAULT_REQUEST_TIMEOUT_MS);
    }

    /** 发送 JSON-RPC 通知 — 委托到 transport */
    public void sendNotification(String method, Object params) {
        if (transport != null) { transport.sendNotification(method, params); }
    }

    /** 发送 JSON-RPC 通知 — 无参数 */
    public void sendNotification(String method) {
        sendNotification(method, null);
    }

    /** 关闭连接并释放资源 */
    public void close() {
        this.status = McpConnectionStatus.DISABLED;
        this.tools = List.of();
        this.resources = List.of();
        if (transport != null) {
            try { transport.close(); } catch (Exception ignored) {}
        }
    }

    /** 检查连接是否存活 */
    public boolean isAlive() {
        return transport != null && transport.isConnected();
    }

    // ===== 连接重试 =====

    /**
     * 带指数退避的连接重试 — 最多 MAX_RECONNECT_ATTEMPTS 次。
     */
    public void connectWithRetry() {
        for (int attempt = 0; attempt <= MAX_RECONNECT_ATTEMPTS; attempt++) {
            connect();
            if (status == McpConnectionStatus.CONNECTED) {
                resetReconnectAttempts();
                return;
            }
            incrementReconnectAttempts();
            if (attempt < MAX_RECONNECT_ATTEMPTS) {
                long delayMs = (long) (500 * Math.pow(2, attempt));
                log.warn("MCP server '{}' connection attempt {}/{} failed, retrying in {}ms",
                        config.name(), attempt + 1, MAX_RECONNECT_ATTEMPTS + 1, delayMs);
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.error("MCP server '{}' connection failed after {} attempts",
                config.name(), MAX_RECONNECT_ATTEMPTS + 1);
    }

    // ===== Getters/Setters =====

    public McpServerConfig getConfig() { return config; }
    public String getName() { return config.name(); }
    public McpConnectionStatus getStatus() { return status; }
    public void setStatus(McpConnectionStatus status) { this.status = status; }
    public List<McpToolDefinition> getTools() { return tools; }
    public void setTools(List<McpToolDefinition> tools) { this.tools = tools; }
    public List<McpResourceDefinition> getResources() { return resources; }
    public void setResources(List<McpResourceDefinition> resources) { this.resources = resources; }
    public int getReconnectAttempts() { return reconnectAttempts; }
    public void incrementReconnectAttempts() { this.reconnectAttempts++; }
    public void resetReconnectAttempts() { this.reconnectAttempts = 0; }

    /** 工具变更回调 */
    private Runnable toolsChangedCallback;

    /** 注册工具变更监听器 */
    public void onToolsChanged(Runnable callback) {
        this.toolsChangedCallback = callback;
    }

    /** 触发工具变更通知 */
    public void notifyToolsChanged() {
        if (toolsChangedCallback != null) {
            toolsChangedCallback.run();
        }
    }

    /** MCP 工具定义 */
    public record McpToolDefinition(
            String name,
            String description,
            Map<String, Object> inputSchema
    ) {}

    /** MCP 资源定义 */
    public record McpResourceDefinition(
            String uri,
            String name,
            String mimeType,
            String description
    ) {}

    /** MCP Prompt 模板定义 */
    public record McpPromptDefinition(
            String name,
            String description,
            List<McpPromptArgument> arguments
    ) {}

    /** MCP Prompt 参数 */
    public record McpPromptArgument(
            String name,
            String description,
            boolean required
    ) {}

    // ===== Prompt 发现 =====

    /**
     * 列出 MCP 服务器支持的 prompt 模板 — 发送 prompts/list 请求。
     *
     * @return prompt 模板列表
     */
    public List<McpPromptDefinition> listPrompts() {
        if (status != McpConnectionStatus.CONNECTED) {
            log.warn("Cannot list prompts — server '{}' not connected (status={})", config.name(), status);
            return List.of();
        }
        try {
            JsonNode result = request("prompts/list", null);
            if (result != null) {
                JsonNode promptsArray = result.path("prompts");
                if (!promptsArray.isArray()) return List.of();

                List<McpPromptDefinition> prompts = new ArrayList<>();
                for (JsonNode p : promptsArray) {
                    String name = p.path("name").asText(null);
                    String desc = p.path("description").asText("");
                    List<McpPromptArgument> args = new ArrayList<>();

                    JsonNode argsArray = p.path("arguments");
                    if (argsArray.isArray()) {
                        for (JsonNode a : argsArray) {
                            args.add(new McpPromptArgument(
                                    a.path("name").asText(""),
                                    a.path("description").asText(""),
                                    a.path("required").asBoolean(false)
                            ));
                        }
                    }
                    if (name != null) {
                        prompts.add(new McpPromptDefinition(name, desc, args));
                    }
                }
                log.info("Discovered {} prompts from MCP server '{}'", prompts.size(), config.name());
                return prompts;
            }
            return List.of();
        } catch (McpProtocolException e) {
            log.warn("prompts/list not supported by MCP server '{}': {}", config.name(), e.getMessage());
            return List.of();
        }
    }
}
