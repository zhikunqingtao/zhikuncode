package com.aicodeassistant.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP 服务器连接状态 — 封装连接、工具和资源信息。
 * <p>
 * STDIO 传输: 通过 Process stdin/stdout 进行 JSON-RPC 通信。
 *
 * @see <a href="SPEC §4.3.3">MCP 客户端管理</a>
 */
public class McpServerConnection {

    private static final Logger log = LoggerFactory.getLogger(McpServerConnection.class);

    private final McpServerConfig config;
    private volatile McpConnectionStatus status;
    private volatile List<McpToolDefinition> tools;
    private volatile List<McpResourceDefinition> resources;
    private volatile int reconnectAttempts;

    /** STDIO 传输: 子进程 */
    private volatile Process process;
    /** STDIO 传输: stdout reader (JSON-RPC 响应) */
    private volatile BufferedReader stdoutReader;
    /** STDIO 传输: stdin writer (JSON-RPC 请求) */
    private volatile OutputStream stdinWriter;

    // JSON-RPC 2.0 增强
    private final AtomicLong requestIdSequence = new AtomicLong(1);
    private final ConcurrentHashMap<String, CompletableFuture<JsonRpcMessage.Response>> pendingRequests = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final int MAX_RECONNECT_ATTEMPTS = 3;
    private static final long DEFAULT_REQUEST_TIMEOUT_MS = 30_000;

    /** HTTP Streamable 传输实例 */
    private volatile McpStreamableHttpTransport httpTransport;
    /** WebSocket 传输实例 */
    private volatile McpWebSocketTransport wsTransport;

    public McpServerConnection(McpServerConfig config) {
        this.config = config;
        this.status = McpConnectionStatus.PENDING;
        this.tools = List.of();
        this.resources = List.of();
        this.reconnectAttempts = 0;
    }

    /**
     * 连接到 MCP 服务器 — 根据 McpTransportType 选择传输层。
     */
    public void connect() {
        switch (config.type()) {
            case STDIO -> connectStdio();
            case HTTP -> connectHttp();
            case WS -> connectWebSocket();
            case SSE, SSE_IDE -> {
                // SSE 传输由 McpSseTransport 单独管理
                this.status = McpConnectionStatus.CONNECTED;
            }
            default -> {
                log.warn("Unsupported transport type '{}' for MCP server '{}', marking as connected",
                        config.type(), config.name());
                this.status = McpConnectionStatus.CONNECTED;
            }
        }
    }

    /** STDIO 传输: 启动子进程 */
    private void connectStdio() {
            try {
                List<String> cmd = new ArrayList<>();
                cmd.add(config.command());
                if (config.args() != null) cmd.addAll(config.args());

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(false);
                if (config.env() != null && !config.env().isEmpty()) {
                    pb.environment().putAll(config.env());
                }

                this.process = pb.start();
                this.stdinWriter = process.getOutputStream();
                this.stdoutReader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

                this.status = McpConnectionStatus.CONNECTED;
                log.info("MCP server '{}' connected via STDIO (pid={})",
                        config.name(), process.pid());
        } catch (IOException e) {
            log.error("Failed to start MCP server '{}': {}", config.name(), e.getMessage());
            this.status = McpConnectionStatus.FAILED;
        }
    }

    /** HTTP Streamable 传输: OkHttp 实现 */
    private void connectHttp() {
        if (config.url() == null) {
            log.error("MCP server '{}' HTTP transport requires a URL", config.name());
            this.status = McpConnectionStatus.FAILED;
            return;
        }
        try {
            this.httpTransport = new McpStreamableHttpTransport(config.url());
            httpTransport.connect(null).get(30, java.util.concurrent.TimeUnit.SECONDS);
            this.status = McpConnectionStatus.CONNECTED;
            log.info("MCP server '{}' connected via Streamable HTTP (url={})",
                    config.name(), config.url());
        } catch (Exception e) {
            log.error("Failed to connect MCP server '{}' via HTTP: {}", config.name(), e.getMessage());
            this.status = McpConnectionStatus.FAILED;
        }
    }

    /** WebSocket 传输: Java-WebSocket 实现 */
    private void connectWebSocket() {
        if (config.url() == null) {
            log.error("MCP server '{}' WS transport requires a URL", config.name());
            this.status = McpConnectionStatus.FAILED;
            return;
        }
        try {
            String wsUrl = config.url().replace("http://", "ws://").replace("https://", "wss://");
            this.wsTransport = new McpWebSocketTransport(wsUrl);
            wsTransport.connect().get(30, java.util.concurrent.TimeUnit.SECONDS);
            this.status = McpConnectionStatus.CONNECTED;
            log.info("MCP server '{}' connected via WebSocket (url={})",
                    config.name(), wsUrl);
        } catch (Exception e) {
            log.error("Failed to connect MCP server '{}' via WebSocket: {}", config.name(), e.getMessage());
            this.status = McpConnectionStatus.FAILED;
        }
    }

    /** 关闭连接 — 根据传输类型清理资源 */
    public void close() {
        this.status = McpConnectionStatus.DISABLED;
        this.tools = List.of();
        this.resources = List.of();

        // STDIO 清理
        if (process != null && process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
        try { if (stdoutReader != null) stdoutReader.close(); } catch (Exception ignored) {}
        try { if (stdinWriter != null) stdinWriter.close(); } catch (Exception ignored) {}

        // HTTP 传输清理
        if (httpTransport != null) {
            try { httpTransport.close(); } catch (Exception ignored) {}
        }

        // WebSocket 传输清理
        if (wsTransport != null) {
            try { wsTransport.close(); } catch (Exception ignored) {}
        }
    }

    /**
     * 发送 JSON-RPC 请求到 MCP server stdin。
     */
    public void sendRequest(String jsonRpcRequest) throws IOException {
        if (stdinWriter == null) {
            throw new IOException("MCP server stdin not available");
        }
        stdinWriter.write((jsonRpcRequest + "\n").getBytes(StandardCharsets.UTF_8));
        stdinWriter.flush();
    }

    /**
     * 从 MCP server stdout 读取一行 JSON-RPC 响应。
     */
    public String readResponse() throws IOException {
        if (stdoutReader == null) {
            throw new IOException("MCP server stdout not available");
        }
        return stdoutReader.readLine();
    }

    // ===== JSON-RPC 2.0 增强方法 =====

    /**
     * 发送 JSON-RPC 请求并等待响应 — 支持超时和错误处理。
     *
     * @param method 方法名
     * @param params 参数 (可为 null)
     * @return 响应结果 (result 字段)
     * @throws McpProtocolException JSON-RPC 错误响应
     */
    public Object sendRequestAndWait(String method, Object params) throws McpProtocolException {
        return sendRequestAndWait(method, params, DEFAULT_REQUEST_TIMEOUT_MS);
    }

    /**
     * 发送 JSON-RPC 请求并等待响应 — 带自定义超时。
     */
    public Object sendRequestAndWait(String method, Object params, long timeoutMs) throws McpProtocolException {
        long id = requestIdSequence.getAndIncrement();
        JsonRpcMessage.Request request = new JsonRpcMessage.Request(id, method, params);

        try {
            String json = objectMapper.writeValueAsString(request);
            sendRequest(json);

            // 同步读取响应 (STDIO 是行协议)
            String responseLine = readResponseWithTimeout(timeoutMs);
            if (responseLine == null) {
                throw new McpProtocolException(new JsonRpcError(
                        JsonRpcError.REQUEST_TIMEOUT, "Request timeout: " + method));
            }

            JsonNode responseNode = objectMapper.readTree(responseLine);
            if (responseNode.has("error") && !responseNode.get("error").isNull()) {
                JsonRpcError error = objectMapper.treeToValue(responseNode.get("error"), JsonRpcError.class);
                throw new McpProtocolException(error);
            }

            return responseNode.has("result") ? responseNode.get("result") : null;

        } catch (McpProtocolException e) {
            throw e;
        } catch (IOException e) {
            throw new McpProtocolException("JSON-RPC communication error: " + e.getMessage(), e);
        }
    }

    /**
     * 发送 JSON-RPC 通知 — 不期望响应。
     */
    public void sendNotification(String method, Object params) {
        JsonRpcMessage.Notification notification = new JsonRpcMessage.Notification(method, params);
        try {
            String json = objectMapper.writeValueAsString(notification);
            sendRequest(json);
        } catch (IOException e) {
            log.warn("Failed to send notification '{}' to '{}': {}", method, config.name(), e.getMessage());
        }
    }

    /**
     * 发送 JSON-RPC 通知 — 无参数。
     */
    public void sendNotification(String method) {
        sendNotification(method, null);
    }

    /**
     * 带超时的响应读取。
     */
    private String readResponseWithTimeout(long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (stdoutReader != null && stdoutReader.ready()) {
                return stdoutReader.readLine();
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
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

    /** 检查连接是否存活 — 根据传输类型检查 */
    public boolean isAlive() {
        return switch (config.type()) {
            case STDIO -> process != null && process.isAlive();
            case HTTP -> httpTransport != null && httpTransport.isConnected();
            case WS -> wsTransport != null && wsTransport.isConnected();
            default -> status == McpConnectionStatus.CONNECTED;
        };
    }

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
            Object result = sendRequestAndWait("prompts/list", null);
            if (result instanceof com.fasterxml.jackson.databind.JsonNode jsonNode) {
                com.fasterxml.jackson.databind.JsonNode promptsArray = jsonNode.path("prompts");
                if (!promptsArray.isArray()) return List.of();

                List<McpPromptDefinition> prompts = new ArrayList<>();
                for (com.fasterxml.jackson.databind.JsonNode p : promptsArray) {
                    String name = p.path("name").asText(null);
                    String desc = p.path("description").asText("");
                    List<McpPromptArgument> args = new ArrayList<>();

                    com.fasterxml.jackson.databind.JsonNode argsArray = p.path("arguments");
                    if (argsArray.isArray()) {
                        for (com.fasterxml.jackson.databind.JsonNode a : argsArray) {
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
