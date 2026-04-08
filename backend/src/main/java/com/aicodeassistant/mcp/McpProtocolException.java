package com.aicodeassistant.mcp;

/**
 * MCP 协议异常 — JSON-RPC 错误响应转化为 Java 异常。
 */
public class McpProtocolException extends RuntimeException {

    private final JsonRpcError rpcError;

    public McpProtocolException(JsonRpcError rpcError) {
        super(rpcError.message());
        this.rpcError = rpcError;
    }

    public McpProtocolException(String message, Throwable cause) {
        super(message, cause);
        this.rpcError = JsonRpcError.internalError(message);
    }

    public int getCode() { return rpcError.code(); }
    public JsonRpcError getRpcError() { return rpcError; }
}
