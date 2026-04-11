package com.aicodeassistant.mcp.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * MCP Server STDIO 传输层 — 从 stdin 读 JSON-RPC 请求，写 stdout 响应。
 * <p>
 * 用于 CLI 模式下将 ZhikuCode 作为 MCP Server 运行。
 * 每行一个 JSON-RPC 请求，处理后输出一行 JSON-RPC 响应。
 * <p>
 * 通过 {@code mcp.server.stdio=true} 配置项启动。
 *
 * @see <a href="SPEC §11">MCP Server STDIO 传输</a>
 */
@Component
@ConditionalOnProperty(name = "mcp.server.stdio", havingValue = "true", matchIfMissing = false)
public class McpServerStdioTransport {

    private static final Logger log = LoggerFactory.getLogger(McpServerStdioTransport.class);
    private static final String JSONRPC_VERSION = "2.0";
    private static final String MCP_PROTOCOL_VERSION = "2024-11-05";
    private static final String SERVER_NAME = "zhikuncode-mcp-server";
    private static final String SERVER_VERSION = "1.0.0";

    private final McpServerToolHandler toolHandler;
    private final ObjectMapper objectMapper;

    private volatile boolean running = true;

    public McpServerStdioTransport(McpServerToolHandler toolHandler, ObjectMapper objectMapper) {
        this.toolHandler = toolHandler;
        this.objectMapper = objectMapper;
    }

    /**
     * 启动 STDIO 事件循环 — 阻塞式读取 stdin，处理每一行 JSON-RPC 请求。
     * 通常由 Spring ApplicationRunner 或手动触发。
     */
    public void start() {
        start(System.in, System.out);
    }

    /**
     * 可测试的启动方法 — 接受自定义输入输出流。
     */
    public void start(InputStream inputStream, OutputStream outputStream) {
        log.info("MCP STDIO transport starting...");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
             PrintWriter writer = new PrintWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8), true)) {

            String line;
            while (running && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                try {
                    JsonNode request = objectMapper.readTree(line);
                    ObjectNode response = processRequest(request);
                    String responseJson = objectMapper.writeValueAsString(response);
                    writer.println(responseJson);
                    writer.flush();
                } catch (Exception e) {
                    log.error("STDIO processing error: {}", e.getMessage(), e);
                    ObjectNode errorResponse = buildErrorResponse(null, -32700, "Parse error: " + e.getMessage());
                    writer.println(objectMapper.writeValueAsString(errorResponse));
                    writer.flush();
                }
            }
        } catch (IOException e) {
            log.error("STDIO transport I/O error: {}", e.getMessage(), e);
        }
        log.info("MCP STDIO transport stopped.");
    }

    /**
     * 停止 STDIO 事件循环。
     */
    public void stop() {
        running = false;
    }

    // ── 请求处理 ──────────────────────────────────────────────

    private ObjectNode processRequest(JsonNode request) {
        String method = request.path("method").asText("");
        JsonNode id = request.get("id");
        JsonNode params = request.get("params");

        log.debug("STDIO request: method={}, id={}", method, id);

        try {
            ObjectNode result = switch (method) {
                case "initialize" -> handleInitialize(params);
                case "ping" -> objectMapper.createObjectNode();
                case "shutdown" -> {
                    stop();
                    yield objectMapper.createObjectNode();
                }
                case "tools/list" -> {
                    ObjectNode r = objectMapper.createObjectNode();
                    r.set("tools", toolHandler.handleToolsList());
                    yield r;
                }
                case "tools/call" -> handleToolsCall(params);
                default -> buildError(-32601, "Method not found: " + method);
            };

            return buildSuccessOrErrorResponse(id, result);
        } catch (Exception e) {
            log.error("STDIO request error: method={}, error={}", method, e.getMessage(), e);
            return buildErrorResponse(id, -32603, "Internal error: " + e.getMessage());
        }
    }

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

        log.info("STDIO MCP Server initialized");
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

    private ObjectNode buildSuccessOrErrorResponse(JsonNode id, ObjectNode resultOrError) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", JSONRPC_VERSION);
        if (id != null) {
            response.set("id", id);
        }

        if (resultOrError.has("code") && resultOrError.has("message")
                && !resultOrError.has("protocolVersion") && !resultOrError.has("content")) {
            response.set("error", resultOrError);
        } else {
            response.set("result", resultOrError);
        }
        return response;
    }

    private ObjectNode buildErrorResponse(JsonNode id, int code, String message) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", JSONRPC_VERSION);
        if (id != null) {
            response.set("id", id);
        }
        ObjectNode error = objectMapper.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        response.set("error", error);
        return response;
    }

    private ObjectNode buildError(int code, String message) {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        return error;
    }
}
