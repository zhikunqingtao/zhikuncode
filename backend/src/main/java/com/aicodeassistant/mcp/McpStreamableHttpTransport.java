package com.aicodeassistant.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * MCP Streamable HTTP 传输层 — 基于 OkHttp 的 HTTP 双向通信。
 * <p>
 * 实现 MCP 2025-03-26 Streamable HTTP 规范:
 * - 请求: POST JSON-RPC → 接收响应（可为 SSE 流或 JSON）
 * - 通知接收: GET → SSE 流
 * - 会话管理: Mcp-Session-Id header
 *
 * @see <a href="SPEC §4.3.3">MCP 客户端管理 - HTTP 传输</a>
 */
public class McpStreamableHttpTransport implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpStreamableHttpTransport.class);

    private final String baseUrl;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AtomicLong requestIdSequence = new AtomicLong(1);
    private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();
    private final AtomicBoolean connected = new AtomicBoolean(false);

    /** MCP 会话 ID — 服务器在 initialize 响应中通过 Mcp-Session-Id header 返回 */
    private volatile String sessionId;

    /** 服务端通知处理器 */
    private Consumer<JsonNode> notificationHandler;

    private static final long DEFAULT_TIMEOUT_MS = 30_000;
    private static final MediaType JSON_MEDIA = MediaType.parse("application/json");

    public McpStreamableHttpTransport(String baseUrl, OkHttpClient httpClient) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper();
    }

    public McpStreamableHttpTransport(String baseUrl) {
        this(baseUrl, new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(60))
                .writeTimeout(Duration.ofSeconds(10))
                .build());
    }

    /**
     * 设置通知处理器。
     */
    public void setNotificationHandler(Consumer<JsonNode> handler) {
        this.notificationHandler = handler;
    }

    /**
     * 建立连接 — 发送 initialize 请求获取 session ID。
     */
    public CompletableFuture<Void> connect(Map<String, Object> clientCapabilities) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Map<String, Object> initParams = Map.of(
                        "protocolVersion", "2025-03-26",
                        "capabilities", clientCapabilities != null ? clientCapabilities : Map.of(),
                        "clientInfo", Map.of("name", "zhikun-mcp-client", "version", "1.0.0")
                );

                JsonNode result = sendRequestInternal("initialize", initParams, DEFAULT_TIMEOUT_MS);
                connected.set(true);
                log.info("MCP Streamable HTTP connected: {} (session={})", baseUrl, sessionId);

                // 发送 initialized 通知
                sendNotification("notifications/initialized", null);
                return null;
            } catch (Exception e) {
                connected.set(false);
                throw new RuntimeException("Failed to connect via Streamable HTTP: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 发送 JSON-RPC 请求并等待响应。
     */
    public JsonNode sendRequest(String method, Object params) throws McpProtocolException {
        return sendRequest(method, params, DEFAULT_TIMEOUT_MS);
    }

    /**
     * 发送 JSON-RPC 请求并等待响应 — 带超时。
     */
    public JsonNode sendRequest(String method, Object params, long timeoutMs) throws McpProtocolException {
        if (!connected.get() && !"initialize".equals(method)) {
            throw new McpProtocolException(new JsonRpcError(
                    JsonRpcError.SERVER_NOT_INITIALIZED, "HTTP transport not connected"));
        }
        return sendRequestInternal(method, params, timeoutMs);
    }

    /**
     * 内部请求发送 — POST JSON-RPC to endpoint。
     * <p>
     * 响应可能是:
     * 1. application/json — 直接 JSON-RPC 响应
     * 2. text/event-stream — SSE 流式响应
     */
    private JsonNode sendRequestInternal(String method, Object params, long timeoutMs) throws McpProtocolException {
        long id = requestIdSequence.getAndIncrement();
        String idStr = String.valueOf(id);

        try {
            JsonRpcMessage.Request rpcRequest = new JsonRpcMessage.Request(id, method, params);
            String json = objectMapper.writeValueAsString(rpcRequest);

            Request.Builder requestBuilder = new Request.Builder()
                    .url(baseUrl)
                    .post(RequestBody.create(json, JSON_MEDIA))
                    .header("Accept", "application/json, text/event-stream");

            if (sessionId != null) {
                requestBuilder.header("Mcp-Session-Id", sessionId);
            }

            try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
                if (!response.isSuccessful()) {
                    throw new McpProtocolException(new JsonRpcError(
                            JsonRpcError.INTERNAL_ERROR,
                            "HTTP " + response.code() + ": " + response.message()));
                }

                // 捕获 session ID
                String newSessionId = response.header("Mcp-Session-Id");
                if (newSessionId != null) {
                    this.sessionId = newSessionId;
                }

                ResponseBody body = response.body();
                if (body == null) {
                    throw new McpProtocolException(new JsonRpcError(
                            JsonRpcError.INTERNAL_ERROR, "Empty response body"));
                }

                String contentType = response.header("Content-Type", "");
                String bodyStr = body.string();

                if (contentType.contains("text/event-stream")) {
                    // SSE 流式响应 — 解析 data: 行
                    return parseSseResponse(bodyStr, idStr);
                } else {
                    // 直接 JSON 响应
                    return parseJsonResponse(bodyStr);
                }
            }
        } catch (McpProtocolException e) {
            throw e;
        } catch (IOException e) {
            throw new McpProtocolException("HTTP communication error: " + e.getMessage(), e);
        }
    }

    /**
     * 解析直接 JSON-RPC 响应。
     */
    private JsonNode parseJsonResponse(String bodyStr) throws McpProtocolException {
        try {
            JsonNode node = objectMapper.readTree(bodyStr);
            if (node.has("error") && !node.get("error").isNull()) {
                JsonRpcError error = objectMapper.treeToValue(node.get("error"), JsonRpcError.class);
                throw new McpProtocolException(error);
            }
            return node.has("result") ? node.get("result") : null;
        } catch (McpProtocolException e) {
            throw e;
        } catch (Exception e) {
            throw new McpProtocolException("Failed to parse JSON response: " + e.getMessage(), e);
        }
    }

    /**
     * 解析 SSE 流式响应 — 提取匹配 id 的 JSON-RPC 响应。
     */
    private JsonNode parseSseResponse(String sseBody, String expectedId) throws McpProtocolException {
        try (BufferedReader reader = new BufferedReader(new StringReader(sseBody))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data: ")) {
                    String data = line.substring(6).trim();
                    if (data.isEmpty()) continue;

                    JsonNode node = objectMapper.readTree(data);

                    // 检查是否是响应（有 id）还是通知
                    if (node.has("id") && expectedId.equals(node.get("id").asText())) {
                        if (node.has("error") && !node.get("error").isNull()) {
                            JsonRpcError error = objectMapper.treeToValue(
                                    node.get("error"), JsonRpcError.class);
                            throw new McpProtocolException(error);
                        }
                        return node.has("result") ? node.get("result") : null;
                    } else if (node.has("method") && notificationHandler != null) {
                        // 服务端通知
                        notificationHandler.accept(node);
                    }
                }
            }
            throw new McpProtocolException(new JsonRpcError(
                    JsonRpcError.INTERNAL_ERROR, "No matching response in SSE stream"));
        } catch (McpProtocolException e) {
            throw e;
        } catch (Exception e) {
            throw new McpProtocolException("Failed to parse SSE response: " + e.getMessage(), e);
        }
    }

    /**
     * 发送 JSON-RPC 通知 — 无需等待响应。
     */
    public void sendNotification(String method, Object params) {
        try {
            JsonRpcMessage.Notification notification = new JsonRpcMessage.Notification(method, params);
            String json = objectMapper.writeValueAsString(notification);

            Request.Builder requestBuilder = new Request.Builder()
                    .url(baseUrl)
                    .post(RequestBody.create(json, JSON_MEDIA));

            if (sessionId != null) {
                requestBuilder.header("Mcp-Session-Id", sessionId);
            }

            try (Response response = httpClient.newCall(requestBuilder.build()).execute()) {
                if (!response.isSuccessful()) {
                    log.warn("Notification '{}' failed: HTTP {}", method, response.code());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to send notification '{}': {}", method, e.getMessage());
        }
    }

    public boolean isConnected() {
        return connected.get();
    }

    public String getSessionId() {
        return sessionId;
    }

    @Override
    public void close() {
        if (connected.compareAndSet(true, false)) {
            // 发送 session 终止通知
            if (sessionId != null) {
                try {
                    Request request = new Request.Builder()
                            .url(baseUrl)
                            .delete()
                            .header("Mcp-Session-Id", sessionId)
                            .build();
                    try (Response ignored = httpClient.newCall(request).execute()) {
                        // 忽略响应
                    }
                } catch (Exception e) {
                    log.debug("Failed to send session close: {}", e.getMessage());
                }
            }
            log.info("MCP Streamable HTTP transport closed (session={})", sessionId);
        }
        // 取消 pending requests
        pendingRequests.forEach((id, future) ->
                future.completeExceptionally(new McpProtocolException(
                        new JsonRpcError(JsonRpcError.INTERNAL_ERROR, "Transport closed"))));
        pendingRequests.clear();
    }
}
