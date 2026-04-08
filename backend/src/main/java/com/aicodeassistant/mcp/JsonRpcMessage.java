package com.aicodeassistant.mcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * JSON-RPC 2.0 消息类型 — MCP 协议基础通信单元。
 * <p>
 * 支持 Request、Response、Notification、BatchRequest 四种类型。
 *
 * @see <a href="https://www.jsonrpc.org/specification">JSON-RPC 2.0 规范</a>
 * @see <a href="SPEC §4.3">MCP 集成</a>
 */
public sealed interface JsonRpcMessage {

    String JSONRPC_VERSION = "2.0";

    /**
     * JSON-RPC 请求 — 需要响应。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Request(
            @JsonProperty("jsonrpc") String jsonrpc,
            @JsonProperty("id") Object id,
            @JsonProperty("method") String method,
            @JsonProperty("params") Object params
    ) implements JsonRpcMessage {
        public Request(Object id, String method, Object params) {
            this(JSONRPC_VERSION, id, method, params);
        }

        public Request(Object id, String method) {
            this(JSONRPC_VERSION, id, method, null);
        }
    }

    /**
     * JSON-RPC 响应 — 对请求的回复。
     * result 和 error 互斥: 成功时 result 非空, 失败时 error 非空。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Response(
            @JsonProperty("jsonrpc") String jsonrpc,
            @JsonProperty("id") Object id,
            @JsonProperty("result") Object result,
            @JsonProperty("error") JsonRpcError error
    ) implements JsonRpcMessage {
        public boolean isError() {
            return error != null;
        }

        public static Response success(Object id, Object result) {
            return new Response(JSONRPC_VERSION, id, result, null);
        }

        public static Response error(Object id, JsonRpcError error) {
            return new Response(JSONRPC_VERSION, id, null, error);
        }
    }

    /**
     * JSON-RPC 通知 — 无需响应 (无 id 字段)。
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Notification(
            @JsonProperty("jsonrpc") String jsonrpc,
            @JsonProperty("method") String method,
            @JsonProperty("params") Object params
    ) implements JsonRpcMessage {
        public Notification(String method, Object params) {
            this(JSONRPC_VERSION, method, params);
        }

        public Notification(String method) {
            this(JSONRPC_VERSION, method, null);
        }
    }

    /**
     * JSON-RPC 批量请求 — 多个请求/通知合并发送。
     */
    record BatchRequest(
            List<JsonRpcMessage> messages
    ) implements JsonRpcMessage {}
}
