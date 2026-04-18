package com.aicodeassistant.controller;

import com.aicodeassistant.mcp.*;
import com.aicodeassistant.mcp.McpServerConnection.McpResourceDefinition;
import com.aicodeassistant.mcp.McpServerConnection.McpPromptDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * MCP 服务器管理 Controller — 管理 MCP 服务器连接、资源发现、Prompt 模板。
 *
 * @see <a href="SPEC §6.1.4">MCP 管理 API</a>
 */
@RestController
@RequestMapping("/api/mcp")
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);

    private final McpClientManager mcpManager;

    public McpController(McpClientManager mcpManager) {
        this.mcpManager = mcpManager;
    }

    // ===== 服务器管理端点 (/api/mcp/servers) =====

    /** 列出所有 MCP 服务器 */
    @GetMapping("/servers")
    public ResponseEntity<Map<String, List<McpServerConnection>>> listServers() {
        return ResponseEntity.ok(Map.of("servers", mcpManager.listConnections()));
    }

    /** 添加新的 MCP 服务器 */
    @PostMapping("/servers")
    public ResponseEntity<Map<String, Object>> addServer(
            @RequestBody McpServerConfig config) {
        mcpManager.addServer(config);
        return ResponseEntity.status(201).body(
                Map.of("name", config.name(), "status", "connecting"));
    }

    /** 删除 MCP 服务器 */
    @DeleteMapping("/servers/{name}")
    public ResponseEntity<Map<String, Boolean>> deleteServer(@PathVariable String name) {
        mcpManager.removeServer(name);
        return ResponseEntity.ok(Map.of("success", true));
    }

    /** 重启 MCP 服务器 */
    @PostMapping("/servers/{name}/restart")
    public ResponseEntity<Map<String, String>> restartServer(@PathVariable String name) {
        mcpManager.restartServer(name);
        return ResponseEntity.ok(Map.of("status", "connecting"));
    }

    /** 获取 MCP 服务器日志 */
    @GetMapping("/servers/{name}/logs")
    public ResponseEntity<Map<String, List<String>>> getServerLogs(
            @PathVariable String name,
            @RequestParam(defaultValue = "100") int lines) {
        return ResponseEntity.ok(Map.of("logs", mcpManager.getServerLogs(name, lines)));
    }

    // ===== 资源发现端点 (/api/mcp/resources) =====

    /**
     * 列出所有 MCP 服务器的资源 — 复用 McpServerConnection.discoverResources() 逻辑。
     * <p>
     * 返回按服务器分组的资源列表，每条资源包含 uri、name、description、mimeType、serverName。
     *
     * @param server 可选：仅列出指定服务器的资源
     * @return 资源列表（按服务器分组）
     */
    @GetMapping("/resources")
    public ResponseEntity<Map<String, Object>> listResources(
            @RequestParam(required = false) String server) {
        try {
            List<McpServerConnection> connections;
            if (server != null && !server.isEmpty()) {
                connections = mcpManager.getConnection(server)
                        .map(List::of).orElse(List.of());
            } else {
                connections = mcpManager.getConnectedServers();
            }

            Map<String, List<Map<String, String>>> grouped = new LinkedHashMap<>();
            int totalCount = 0;

            for (McpServerConnection conn : connections) {
                try {
                    List<McpResourceDefinition> resources = conn.discoverResources();
                    List<Map<String, String>> resList = new ArrayList<>();
                    for (McpResourceDefinition res : resources) {
                        Map<String, String> resMap = new LinkedHashMap<>();
                        resMap.put("uri", res.uri());
                        resMap.put("name", res.name());
                        resMap.put("description", res.description() != null ? res.description() : "");
                        resMap.put("mimeType", res.mimeType() != null ? res.mimeType() : "unknown");
                        resMap.put("serverName", conn.getName());
                        resList.add(resMap);
                    }
                    grouped.put(conn.getName(), resList);
                    totalCount += resList.size();
                } catch (Exception e) {
                    log.warn("Failed to discover resources from MCP server '{}': {}",
                            conn.getName(), e.getMessage());
                    grouped.put(conn.getName(), List.of());
                }
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("resources", grouped);
            response.put("totalCount", totalCount);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to list MCP resources: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(
                    Map.of("error", "Failed to list resources: " + e.getMessage()));
        }
    }

    /**
     * 读取指定 MCP 资源的内容 — 复用 McpServerConnection.readResource() 逻辑。
     *
     * @param uri    资源 URI
     * @param server MCP 服务器名称
     * @return 资源内容
     */
    @GetMapping("/resources/read")
    public ResponseEntity<Map<String, Object>> readResource(
            @RequestParam String uri,
            @RequestParam String server) {
        try {
            var connOpt = mcpManager.getConnection(server);
            if (connOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(
                        Map.of("error", "MCP server not found: " + server));
            }
            McpServerConnection conn = connOpt.get();
            if (conn.getStatus() != McpConnectionStatus.CONNECTED) {
                return ResponseEntity.badRequest().body(
                        Map.of("error", "MCP server '" + server + "' not connected (status: " + conn.getStatus() + ")"));
            }

            String content = conn.readResource(uri);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("uri", uri);
            response.put("serverName", server);
            response.put("content", content);
            return ResponseEntity.ok(response);
        } catch (McpProtocolException e) {
            log.error("Failed to read MCP resource '{}' from server '{}': {}", uri, server, e.getMessage());
            return ResponseEntity.internalServerError().body(
                    Map.of("error", "Failed to read resource: " + e.getMessage()));
        } catch (Exception e) {
            log.error("Unexpected error reading MCP resource: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(
                    Map.of("error", "Unexpected error: " + e.getMessage()));
        }
    }

    // ===== Prompt 端点 (/api/mcp/prompts) =====

    /**
     * 列出所有 MCP 服务器的 Prompt 模板。
     * <p>
     * 遍历所有已连接的 MCP 服务器，收集 prompts/list 返回的模板定义。
     *
     * @param server 可选：仅列出指定服务器的 prompt
     * @return prompt 模板列表
     */
    @GetMapping("/prompts")
    public ResponseEntity<Map<String, Object>> listPrompts(
            @RequestParam(required = false) String server) {
        try {
            List<McpServerConnection> connections;
            if (server != null && !server.isEmpty()) {
                connections = mcpManager.getConnection(server)
                        .map(List::of).orElse(List.of());
            } else {
                connections = mcpManager.getConnectedServers();
            }

            Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
            int totalCount = 0;

            for (McpServerConnection conn : connections) {
                try {
                    List<McpPromptDefinition> prompts = conn.listPrompts();
                    List<Map<String, Object>> promptList = new ArrayList<>();
                    for (McpPromptDefinition prompt : prompts) {
                        Map<String, Object> promptMap = new LinkedHashMap<>();
                        promptMap.put("name", prompt.name());
                        promptMap.put("description", prompt.description() != null ? prompt.description() : "");
                        promptMap.put("serverName", conn.getName());
                        List<Map<String, Object>> args = new ArrayList<>();
                        if (prompt.arguments() != null) {
                            for (var arg : prompt.arguments()) {
                                args.add(Map.of(
                                        "name", arg.name(),
                                        "description", arg.description() != null ? arg.description() : "",
                                        "required", arg.required()
                                ));
                            }
                        }
                        promptMap.put("arguments", args);
                        promptList.add(promptMap);
                    }
                    grouped.put(conn.getName(), promptList);
                    totalCount += promptList.size();
                } catch (Exception e) {
                    log.warn("Failed to list prompts from MCP server '{}': {}",
                            conn.getName(), e.getMessage());
                    grouped.put(conn.getName(), List.of());
                }
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("prompts", grouped);
            response.put("totalCount", totalCount);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to list MCP prompts: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(
                    Map.of("error", "Failed to list prompts: " + e.getMessage()));
        }
    }

    /**
     * 执行指定 MCP Prompt 模板 — 通过 McpPromptAdapter 完成参数验证和执行。
     *
     * @param body 请求体，包含 server、promptName、arguments
     * @return prompt 执行结果（包含渲染后的消息列表）
     */
    @PostMapping("/prompts/execute")
    public ResponseEntity<Map<String, Object>> executePrompt(
            @RequestBody Map<String, Object> body) {
        String serverName = (String) body.get("server");
        String promptName = (String) body.get("promptName");

        if (promptName == null || promptName.isBlank()) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "Prompt name is required", "success", false));
        }
        if (serverName == null || serverName.isBlank()) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "Server name is required", "success", false));
        }

        var connOpt = mcpManager.getConnection(serverName);
        if (connOpt.isEmpty()) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "MCP server not found: " + serverName, "success", false));
        }

        McpServerConnection conn = connOpt.get();
        if (conn.getStatus() != McpConnectionStatus.CONNECTED) {
            return ResponseEntity.badRequest().body(
                    Map.of("error", "MCP server '" + serverName + "' not connected", "success", false));
        }

        try {
            // 查找匹配的 prompt 定义
            McpPromptDefinition promptDef = conn.listPrompts().stream()
                    .filter(p -> p.name().equals(promptName))
                    .findFirst()
                    .orElse(null);

            if (promptDef == null) {
                return ResponseEntity.status(404).body(
                        Map.of("error", "Prompt '" + promptName + "' not found on server '" + serverName + "'",
                                "success", false));
            }

            // 构造 adapter 并执行
            McpPromptAdapter adapter = new McpPromptAdapter(serverName, promptDef, conn);

            @SuppressWarnings("unchecked")
            Map<String, String> arguments = body.containsKey("arguments")
                    ? (Map<String, String>) body.get("arguments")
                    : Map.of();

            // 参数验证
            List<String> validationErrors = adapter.validateArguments(arguments);
            if (!validationErrors.isEmpty()) {
                return ResponseEntity.badRequest().body(
                        Map.of("error", "Parameter validation failed",
                                "details", validationErrors, "success", false));
            }

            // 执行 prompt
            List<Map<String, String>> messages = adapter.execute(arguments);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("serverName", serverName);
            response.put("promptName", promptName);
            response.put("messages", messages);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to execute prompt '{}' on server '{}': {}",
                    promptName, serverName, e.getMessage());
            return ResponseEntity.internalServerError().body(
                    Map.of("error", "Failed to execute prompt: " + e.getMessage(), "success", false));
        }
    }

    // ===== 重连端点 (/api/mcp/reconnect) =====

    /**
     * 重连指定 MCP 服务器 — 关闭现有连接并重新建立。
     *
     * @param server MCP 服务器名称
     * @return 重连状态
     */
    @PostMapping("/reconnect")
    public ResponseEntity<Map<String, Object>> reconnectServer(
            @RequestParam String server) {
        try {
            var connOpt = mcpManager.getConnection(server);
            if (connOpt.isEmpty()) {
                return ResponseEntity.badRequest().body(
                        Map.of("error", "MCP server not found: " + server));
            }

            log.info("REST API: reconnecting MCP server '{}'", server);
            mcpManager.restartServer(server);

            McpConnectionStatus newStatus = mcpManager.getConnection(server)
                    .map(McpServerConnection::getStatus)
                    .orElse(McpConnectionStatus.FAILED);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("serverName", server);
            response.put("status", newStatus.name());
            response.put("success", newStatus == McpConnectionStatus.CONNECTED);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to reconnect MCP server '{}': {}", server, e.getMessage());
            return ResponseEntity.internalServerError().body(
                    Map.of("error", "Failed to reconnect: " + e.getMessage()));
        }
    }
}
