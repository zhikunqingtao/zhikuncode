package com.aicodeassistant.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * MCP 传输层统一接口 — 所有传输类型（SSE/HTTP/WS/STDIO）的公共契约。
 * <p>
 * 每种传输实现负责:
 * <ul>
 *   <li>连接建立与生命周期管理</li>
 *   <li>JSON-RPC 请求/响应的序列化与超时控制</li>
 *   <li>服务端通知的异步接收</li>
 * </ul>
 *
 * @see McpSseTransport
 * @see McpStreamableHttpTransport
 * @see McpWebSocketTransport
 * @see McpStdioTransport
 */
public interface McpTransport extends AutoCloseable {

    /** 建立连接，返回连接就绪的 Future */
    CompletableFuture<Void> connect();

    /**
     * 发送 JSON-RPC 请求并同步等待响应。
     *
     * @param method    JSON-RPC 方法名 (如 "tools/call", "tools/list")
     * @param params    方法参数 (序列化为 JSON)
     * @param timeoutMs 超时毫秒数
     * @return result 字段的 JsonNode
     * @throws McpProtocolException 协议错误或超时
     */
    JsonNode sendRequest(String method, Object params, long timeoutMs) throws McpProtocolException;

    /** 发送 JSON-RPC 通知 — 不期望响应 */
    void sendNotification(String method, Object params);

    /** 连接是否存活 */
    boolean isConnected();

    /** 注册服务端通知处理器 */
    void setNotificationHandler(Consumer<JsonNode> handler);

    /** 关闭连接并释放资源 */
    @Override
    void close();
}
