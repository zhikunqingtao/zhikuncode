package com.aicodeassistant.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * MCP SSE 传输层 — 基于 Server-Sent Events 的双向通信。
 * <p>
 * 接收: SSE stream (GET) → JSON-RPC 消息
 * 发送: HTTP POST → JSON-RPC 请求/通知
 *
 * @see <a href="SPEC §4.3.3">MCP 客户端管理 - SSE 传输</a>
 */
public class McpSseTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(McpSseTransport.class);

    private final String sseUrl;
    private final String postUrl;
    private final String baseOrigin;
    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final AtomicLong requestIdSequence = new AtomicLong(1);
    private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private volatile EventSource eventSource;
    private volatile String sessionEndpoint;
    private Consumer<JsonNode> notificationHandler;

    private static final long DEFAULT_TIMEOUT_MS = 30_000;

    public McpSseTransport(String baseUrl, OkHttpClient httpClient) {
        this.sseUrl = baseUrl + "/sse";
        this.postUrl = baseUrl;
        // 提取 origin (scheme + host + port) 用于 SSE endpoint 拼接
        try {
            java.net.URI uri = new java.net.URI(baseUrl);
            String port = uri.getPort() > 0 ? ":" + uri.getPort() : "";
            this.baseOrigin = uri.getScheme() + "://" + uri.getHost() + port;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid base URL: " + baseUrl, e);
        }
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper();
    }

    public McpSseTransport(String baseUrl) {
        this(baseUrl, new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ZERO)
                .writeTimeout(Duration.ofSeconds(10))
                .build());
    }

    /**
     * 设置通知处理器 — 处理服务器主动推送的通知。
     */
    public void setNotificationHandler(Consumer<JsonNode> handler) {
        this.notificationHandler = handler;
    }

    /**
     * 建立 SSE 连接。
     */
    public CompletableFuture<Void> connect() {
        CompletableFuture<Void> connectFuture = new CompletableFuture<>();

        Request request = new Request.Builder()
                .url(sseUrl)
                .header("Accept", "text/event-stream")
                .build();

        EventSourceListener listener = new EventSourceListener() {
            @Override
            public void onOpen(EventSource eventSource, Response response) {
                connected.set(true);
                log.info("MCP SSE connected: {}", sseUrl);
            }

            @Override
            public void onEvent(EventSource eventSource, String id, String type, String data) {
                handleSseEvent(type, data, connectFuture);
            }

            @Override
            public void onFailure(EventSource eventSource, Throwable t, Response response) {
                connected.set(false);
                log.error("MCP SSE connection failed: {}", t != null ? t.getMessage() : "unknown");
                if (!connectFuture.isDone()) {
                    connectFuture.completeExceptionally(
                            t != null ? t : new IOException("SSE connection failed"));
                }
            }

            @Override
            public void onClosed(EventSource eventSource) {
                connected.set(false);
                log.info("MCP SSE connection closed");
            }
        };

        this.eventSource = EventSources.createFactory(httpClient)
                .newEventSource(request, listener);

        return connectFuture;
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
        if (!connected.get()) {
            throw new McpProtocolException(new JsonRpcError(
                    JsonRpcError.SERVER_NOT_INITIALIZED, "SSE not connected"));
        }

        long id = requestIdSequence.getAndIncrement();
        String idStr = String.valueOf(id);
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingRequests.put(idStr, future);

        try {
            JsonRpcMessage.Request rpcRequest = new JsonRpcMessage.Request(id, method, params);
            String json = objectMapper.writeValueAsString(rpcRequest);

            String targetUrl = sessionEndpoint != null ? sessionEndpoint : postUrl;
            RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));
            Request httpRequest = new Request.Builder()
                    .url(targetUrl)
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(httpRequest).execute()) {
                if (!response.isSuccessful()) {
                    throw new McpProtocolException(new JsonRpcError(
                            JsonRpcError.INTERNAL_ERROR,
                            "HTTP " + response.code() + ": " + response.message()));
                }
            }

            // 等待 SSE 流中的响应
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);

        } catch (McpProtocolException e) {
            throw e;
        } catch (TimeoutException e) {
            throw new McpProtocolException(new JsonRpcError(
                    JsonRpcError.REQUEST_TIMEOUT, "Request timeout: " + method));
        } catch (Exception e) {
            throw new McpProtocolException("SSE request error: " + e.getMessage(), e);
        } finally {
            pendingRequests.remove(idStr);
        }
    }

    /**
     * 发送 JSON-RPC 通知 — 无需等待响应。
     */
    public void sendNotification(String method, Object params) {
        try {
            JsonRpcMessage.Notification notification = new JsonRpcMessage.Notification(method, params);
            String json = objectMapper.writeValueAsString(notification);

            String targetUrl = sessionEndpoint != null ? sessionEndpoint : postUrl;
            RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));
            Request request = new Request.Builder()
                    .url(targetUrl)
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    log.warn("Notification '{}' failed: HTTP {}", method, response.code());
                }
            }
        } catch (Exception e) {
            log.warn("Failed to send notification '{}': {}", method, e.getMessage());
        }
    }

    /**
     * 处理 SSE 事件。
     */
    private void handleSseEvent(String type, String data, CompletableFuture<Void> connectFuture) {
        try {
            if ("endpoint".equals(type)) {
                // MCP SSE 协议: 第一个事件是 endpoint URL
                // data 可能是绝对路径 (/api/...) 或相对路径
                this.sessionEndpoint = data.startsWith("http") ? data
                        : (data.startsWith("/") ? baseOrigin + data : postUrl + "/" + data);
                log.info("MCP SSE endpoint: {}", sessionEndpoint);
                if (!connectFuture.isDone()) {
                    connectFuture.complete(null);
                }
                return;
            }

            JsonNode node = objectMapper.readTree(data);

            // 判断是响应还是通知
            if (node.has("id") && !node.get("id").isNull()) {
                // 响应: 匹配 pending request
                String id = node.get("id").asText();
                CompletableFuture<JsonNode> future = pendingRequests.remove(id);
                if (future != null) {
                    if (node.has("error") && !node.get("error").isNull()) {
                        JsonRpcError error = objectMapper.treeToValue(
                                node.get("error"), JsonRpcError.class);
                        future.completeExceptionally(new McpProtocolException(error));
                    } else {
                        future.complete(node.has("result") ? node.get("result") : null);
                    }
                } else {
                    log.warn("Received response for unknown request id: {}", id);
                }
            } else if (node.has("method")) {
                // 通知: 服务端推送
                if (notificationHandler != null) {
                    notificationHandler.accept(node);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to process SSE event: {}", e.getMessage());
        }
    }

    public boolean isConnected() {
        return connected.get();
    }

    @Override
    public void close() {
        connected.set(false);
        if (eventSource != null) {
            eventSource.cancel();
        }
        // 取消所有 pending requests
        pendingRequests.forEach((id, future) ->
                future.completeExceptionally(new McpProtocolException(
                        new JsonRpcError(JsonRpcError.INTERNAL_ERROR, "Transport closed"))));
        pendingRequests.clear();
    }
}
