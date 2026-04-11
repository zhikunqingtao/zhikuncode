package com.aicodeassistant.mcp.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

/**
 * MCP Server REST 入口 — JSON-RPC over HTTP。
 * <p>
 * 实现 MCP 协议的 initialize / ping / shutdown 生命周期方法，
 * 以及 tools/list、tools/call 工具调用端点。
 * <p>
 * 通过 {@code mcp.server.enabled=true} 配置项开启。
 *
 * @see <a href="SPEC §11">MCP Server 入口</a>
 */
@RestController
@RequestMapping("/mcp")
@ConditionalOnProperty(name = "mcp.server.enabled", havingValue = "true", matchIfMissing = false)
public class McpServerEntrypoint {

    private static final Logger log = LoggerFactory.getLogger(McpServerEntrypoint.class);

    private static final String JSONRPC_VERSION = "2.0";
    private static final String MCP_PROTOCOL_VERSION = "2024-11-05";
    private static final String SERVER_NAME = "zhikuncode-mcp-server";
    private static final String SERVER_VERSION = "1.0.0";

    private final McpServerToolHandler toolHandler;
    private final ObjectMapper objectMapper;

    public McpServerEntrypoint(McpServerToolHandler toolHandler, ObjectMapper objectMapper) {
        this.toolHandler = toolHandler;
        this.objectMapper = objectMapper;
    }

    /**
     * JSON-RPC 统一入口 — 根据 method 字段分发到不同处理器。
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ObjectNode handleJsonRpc(@RequestBody JsonNode request) {
        String method = request.path("method").asText("");
        JsonNode id = request.get("id");
        JsonNode params = request.get("params");

        log.debug("MCP JSON-RPC request: method={}, id={}", method, id);

        try {
            ObjectNode result = switch (method) {
                case "initialize" -> handleInitialize(params);
                case "ping" -> handlePing();
                case "shutdown" -> handleShutdown();
                case "tools/list" -> handleToolsList();
                case "tools/call" -> handleToolsCall(params);
                default -> {
                    log.warn("Unknown MCP method: {}", method);
                    yield buildError(-32601, "Method not found: " + method);
                }
            };

            return buildResponse(id, result);
        } catch (Exception e) {
            log.error("MCP JSON-RPC error: method={}, error={}", method, e.getMessage(), e);
            return buildResponse(id, buildError(-32603, "Internal error: " + e.getMessage()));
        }
    }

    // ── 生命周期方法 ──────────────────────────────────────────

    private ObjectNode handleInitialize(JsonNode params) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion", MCP_PROTOCOL_VERSION);

        ObjectNode serverInfo = objectMapper.createObjectNode();
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("version", SERVER_VERSION);
        result.set("serverInfo", serverInfo);

        ObjectNode capabilities = objectMapper.createObjectNode();
        ObjectNode toolsCap = objectMapper.createObjectNode();
        toolsCap.put("listChanged", false);
        capabilities.set("tools", toolsCap);
        result.set("capabilities", capabilities);

        log.info("MCP Server initialized: protocol={}, clientInfo={}",
                MCP_PROTOCOL_VERSION, params != null ? params.path("clientInfo") : "none");
        return result;
    }

    private ObjectNode handlePing() {
        return objectMapper.createObjectNode();
    }

    private ObjectNode handleShutdown() {
        log.info("MCP Server shutdown requested");
        return objectMapper.createObjectNode();
    }

    // ── 工具方法 ──────────────────────────────────────────────

    private ObjectNode handleToolsList() {
        ObjectNode result = objectMapper.createObjectNode();
        result.set("tools", toolHandler.handleToolsList());
        return result;
    }

    private ObjectNode handleToolsCall(JsonNode params) {
        if (params == null) {
            return buildError(-32602, "Missing params for tools/call");
        }

        String toolName = params.path("name").asText(null);
        if (toolName == null || toolName.isBlank()) {
            return buildError(-32602, "Missing 'name' in tools/call params");
        }

        JsonNode arguments = params.has("arguments") ? params.get("arguments") : objectMapper.createObjectNode();
        String sessionId = params.path("_meta").path("sessionId").asText(null);

        return toolHandler.handleToolsCall(toolName, arguments, sessionId);
    }

    // ── JSON-RPC 辅助方法 ─────────────────────────────────────

    private ObjectNode buildResponse(JsonNode id, ObjectNode resultOrError) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", JSONRPC_VERSION);
        if (id != null) {
            response.set("id", id);
        }

        if (resultOrError.has("code") && resultOrError.has("message")) {
            // Error response
            response.set("error", resultOrError);
        } else {
            // Success response
            response.set("result", resultOrError);
        }
        return response;
    }

    private ObjectNode buildError(int code, String message) {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        return error;
    }
}
