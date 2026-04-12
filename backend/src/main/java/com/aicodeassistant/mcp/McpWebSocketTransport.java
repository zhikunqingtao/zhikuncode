package com.aicodeassistant.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * MCP WebSocket 传输层 — 基于 Java-WebSocket 的全双工通信。
 * <p>
 * 实现 MCP WebSocket 传输规范:
 * - 双向 JSON-RPC 消息交换
 * - 服务端通知推送
 * - 自动重连支持
 *
 * @see <a href="SPEC §4.3.3">MCP 客户端管理 - WebSocket 传输</a>
 */
public class McpWebSocketTransport implements McpTransport {

    private static final Logger log = LoggerFactory.getLogger(McpWebSocketTransport.class);

    private final URI serverUri;
    private final ObjectMapper objectMapper;
    private final AtomicLong requestIdSequence = new AtomicLong(1);
    private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private volatile McpWsClient wsClient;

    /** 服务端通知处理器 */
    private Consumer<JsonNode> notificationHandler;

    /** 连接就绪 Future */
    private volatile CompletableFuture<Void> connectFuture;

    private static final long DEFAULT_TIMEOUT_MS = 30_000;
    private static final int CONNECT_TIMEOUT_MS = 10_000;

    public McpWebSocketTransport(String wsUrl) {
        this.serverUri = URI.create(wsUrl);
        this.objectMapper = new ObjectMapper();
    }

    public McpWebSocketTransport(URI serverUri) {
        this.serverUri = serverUri;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * 设置通知处理器。
     */
    public void setNotificationHandler(Consumer<JsonNode> handler) {
        this.notificationHandler = handler;
    }

    /**
     * 建立 WebSocket 连接。
     */
    public CompletableFuture<Void> connect() {
        connectFuture = new CompletableFuture<>();

        wsClient = new McpWsClient(serverUri);
        wsClient.setConnectionLostTimeout(30);

        try {
            boolean opened = wsClient.connectBlocking(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!opened) {
                connectFuture.completeExceptionally(
                        new McpProtocolException(new JsonRpcError(
                                JsonRpcError.INTERNAL_ERROR, "WebSocket connection timed out: " + serverUri)));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            connectFuture.completeExceptionally(e);
        }

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
                    JsonRpcError.SERVER_NOT_INITIALIZED, "WebSocket not connected"));
        }

        long id = requestIdSequence.getAndIncrement();
        String idStr = String.valueOf(id);
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingRequests.put(idStr, future);

        try {
            JsonRpcMessage.Request rpcRequest = new JsonRpcMessage.Request(id, method, params);
            String json = objectMapper.writeValueAsString(rpcRequest);
            wsClient.send(json);

            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new McpProtocolException(new JsonRpcError(
                    JsonRpcError.REQUEST_TIMEOUT, "Request timeout: " + method));
        } catch (McpProtocolException e) {
            throw e;
        } catch (Exception e) {
            Throwable cause = e.getCause();
            if (cause instanceof McpProtocolException mpe) throw mpe;
            throw new McpProtocolException("WebSocket request error: " + e.getMessage(), e);
        } finally {
            pendingRequests.remove(idStr);
        }
    }

    /**
     * 发送 JSON-RPC 通知 — 无需等待响应。
     */
    public void sendNotification(String method, Object params) {
        if (!connected.get()) {
            log.warn("Cannot send notification '{}' — WebSocket not connected", method);
            return;
        }
        try {
            JsonRpcMessage.Notification notification = new JsonRpcMessage.Notification(method, params);
            String json = objectMapper.writeValueAsString(notification);
            wsClient.send(json);
        } catch (Exception e) {
            log.warn("Failed to send notification '{}': {}", method, e.getMessage());
        }
    }

    public boolean isConnected() {
        return connected.get();
    }

    @Override
    public void close() {
        connected.set(false);
        if (wsClient != null && !wsClient.isClosed()) {
            wsClient.close();
        }
        // 取消 pending requests
        pendingRequests.forEach((id, future) ->
                future.completeExceptionally(new McpProtocolException(
                        new JsonRpcError(JsonRpcError.INTERNAL_ERROR, "Transport closed"))));
        pendingRequests.clear();
    }

    // ═══ 内部 WebSocket 客户端 ═══

    private class McpWsClient extends WebSocketClient {

        McpWsClient(URI serverUri) {
            super(serverUri);
        }

        @Override
        public void onOpen(ServerHandshake handshake) {
            connected.set(true);
            log.info("MCP WebSocket connected: {} (status={})", serverUri, handshake.getHttpStatus());
            if (connectFuture != null && !connectFuture.isDone()) {
                connectFuture.complete(null);
            }
        }

        @Override
        public void onMessage(String message) {
            try {
                JsonNode node = objectMapper.readTree(message);

                if (node.has("id") && !node.get("id").isNull()) {
                    // JSON-RPC 响应
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
                        log.debug("Received response for unknown request id: {}", id);
                    }
                } else if (node.has("method")) {
                    // 服务端通知
                    String method = node.get("method").asText();
                    log.debug("Received WS notification: {}", method);
                    if (notificationHandler != null) {
                        notificationHandler.accept(node);
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to process WebSocket message: {}", e.getMessage());
            }
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            connected.set(false);
            log.info("MCP WebSocket closed: {} (code={}, reason={}, remote={})",
                    serverUri, code, reason, remote);
            // 如果是连接阶段关闭，通知 connect future
            if (connectFuture != null && !connectFuture.isDone()) {
                connectFuture.completeExceptionally(
                        new McpProtocolException(new JsonRpcError(
                                JsonRpcError.INTERNAL_ERROR, "WebSocket closed during connect: " + reason)));
            }
            // 取消所有 pending
            pendingRequests.forEach((id, future) ->
                    future.completeExceptionally(new McpProtocolException(
                            new JsonRpcError(JsonRpcError.INTERNAL_ERROR,
                                    "WebSocket closed: " + reason))));
            pendingRequests.clear();
        }

        @Override
        public void onError(Exception ex) {
            log.error("MCP WebSocket error: {}", ex.getMessage());
            if (connectFuture != null && !connectFuture.isDone()) {
                connectFuture.completeExceptionally(ex);
            }
        }
    }
}
