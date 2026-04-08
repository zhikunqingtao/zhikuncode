package com.aicodeassistant.mcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * JSON-RPC 2.0 错误对象 — 标准错误码 + 自定义数据。
 *
 * @param code    错误码 (预定义或自定义)
 * @param message 错误描述
 * @param data    附加错误数据 (可选)
 * @see <a href="https://www.jsonrpc.org/specification#error_object">JSON-RPC 2.0 Error Object</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcError(
        @JsonProperty("code") int code,
        @JsonProperty("message") String message,
        @JsonProperty("data") Object data
) {
    // ===== JSON-RPC 2.0 预定义错误码 =====

    /** 解析错误: 收到无效 JSON */
    public static final int PARSE_ERROR = -32700;
    /** 无效请求: JSON 有效但不符合 JSON-RPC 规范 */
    public static final int INVALID_REQUEST = -32600;
    /** 方法未找到 */
    public static final int METHOD_NOT_FOUND = -32601;
    /** 无效参数 */
    public static final int INVALID_PARAMS = -32602;
    /** 内部错误 */
    public static final int INTERNAL_ERROR = -32603;

    // ===== 服务器自定义错误码范围: -32000 ~ -32099 =====

    /** MCP: 服务器初始化失败 */
    public static final int SERVER_NOT_INITIALIZED = -32002;
    /** MCP: 请求超时 */
    public static final int REQUEST_TIMEOUT = -32001;

    /** 便捷构造: 无附加数据 */
    public JsonRpcError(int code, String message) {
        this(code, message, null);
    }

    /** 预定义错误工厂方法 */
    public static JsonRpcError parseError(String detail) {
        return new JsonRpcError(PARSE_ERROR, "Parse error: " + detail);
    }

    public static JsonRpcError invalidRequest(String detail) {
        return new JsonRpcError(INVALID_REQUEST, "Invalid request: " + detail);
    }

    public static JsonRpcError methodNotFound(String method) {
        return new JsonRpcError(METHOD_NOT_FOUND, "Method not found: " + method);
    }

    public static JsonRpcError invalidParams(String detail) {
        return new JsonRpcError(INVALID_PARAMS, "Invalid params: " + detail);
    }

    public static JsonRpcError internalError(String detail) {
        return new JsonRpcError(INTERNAL_ERROR, "Internal error: " + detail);
    }
}
