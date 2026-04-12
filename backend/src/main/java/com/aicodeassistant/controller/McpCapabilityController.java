package com.aicodeassistant.controller;

import com.aicodeassistant.mcp.McpCapabilityDefinition;
import com.aicodeassistant.mcp.McpCapabilityRegistryService;
import com.aicodeassistant.mcp.McpClientManager;
import com.aicodeassistant.mcp.McpConnectionStatus;
import com.aicodeassistant.mcp.McpServerConfig;
import com.aicodeassistant.mcp.McpServerConnection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * MCP 能力注册表管理 Controller — CRUD + 启用/禁用 + 测试。
 * 路径: /api/mcp/capabilities
 */
@RestController
@RequestMapping("/api/mcp/capabilities")
public class McpCapabilityController {

    private final McpCapabilityRegistryService registryService;
    private final McpClientManager mcpManager;

    public McpCapabilityController(McpCapabilityRegistryService registryService,
                                   McpClientManager mcpManager) {
        this.registryService = registryService;
        this.mcpManager = mcpManager;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listCapabilities(
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) Boolean enabled) {
        List<McpCapabilityDefinition> result;
        if (domain != null && !domain.isEmpty()) {
            result = registryService.listByDomain(domain);
        } else if (Boolean.TRUE.equals(enabled)) {
            result = registryService.listEnabled();
        } else {
            result = registryService.listAll();
        }
        return ResponseEntity.ok(Map.of(
                "capabilities", result,
                "total", registryService.size(),
                "enabledCount", registryService.enabledCount()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<McpCapabilityDefinition> getCapability(@PathVariable String id) {
        return registryService.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/{id}")
    public ResponseEntity<McpCapabilityDefinition> updateCapability(
            @PathVariable String id, @RequestBody McpCapabilityDefinition updated) {
        try {
            return ResponseEntity.ok(registryService.updateCapability(id, updated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PatchMapping("/{id}/toggle")
    public ResponseEntity<Map<String, Object>> toggleCapability(
            @PathVariable String id, @RequestParam boolean enabled) {
        try {
            McpCapabilityDefinition def = registryService.toggleEnabled(id, enabled);
            String status;
            if (enabled) {
                McpServerConnection conn = mcpManager.enableFromRegistry(def);
                status = conn.getStatus() == McpConnectionStatus.CONNECTED
                        ? "connected" : conn.getStatus().name().toLowerCase();
            } else {
                // 同服务器多工具保护 — 仅当无其他启用工具时才移除服务器
                String serverKey = def.extractServerKey();
                boolean otherEnabled = registryService.listEnabled().stream()
                        .anyMatch(c -> !c.id().equals(id) && serverKey.equals(c.extractServerKey()));
                if (!otherEnabled) {
                    mcpManager.removeServer(serverKey);
                }
                status = "disabled";
            }
            return ResponseEntity.ok(Map.of("id", id, "enabled", enabled, "status", status));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping
    public ResponseEntity<McpCapabilityDefinition> addCapability(
            @RequestBody McpCapabilityDefinition def) {
        try {
            return ResponseEntity.status(201).body(registryService.addCapability(def));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Boolean>> deleteCapability(@PathVariable String id) {
        boolean removed = registryService.deleteCapability(id);
        return removed ? ResponseEntity.ok(Map.of("success", true))
                       : ResponseEntity.notFound().build();
    }

    @GetMapping("/domains")
    public ResponseEntity<Map<String, List<String>>> listDomains() {
        return ResponseEntity.ok(Map.of("domains", registryService.listDomains()));
    }

    /** 查询指定能力对应服务器的实际工具列表 */
    @GetMapping("/{id}/server-tools")
    public ResponseEntity<Map<String, Object>> listServerTools(@PathVariable String id) {
        return registryService.findById(id).map(def -> {
            try {
                String serverKey = def.extractServerKey();
                var conn = mcpManager.getConnection(serverKey);
                if (conn.isPresent() && conn.get().isAlive()) {
                    // 先初始化 MCP 协议
                    try {
                        var initParams = Map.of(
                                "protocolVersion", "2024-11-05",
                                "capabilities", Map.of(),
                                "clientInfo", Map.of("name", "zhikucode", "version", "1.0.0"));
                        conn.get().request("initialize", initParams, 15000);
                        conn.get().sendNotification("notifications/initialized");
                    } catch (Exception ignored) { /* 已初始化则忽略 */ }
                    JsonNode result = conn.get().request("tools/list", Map.of(), 15000);
                    ObjectMapper om = new ObjectMapper();
                    Object tools = om.treeToValue(result, Object.class);
                    return ResponseEntity.ok(Map.<String, Object>of(
                            "id", id, "serverKey", serverKey, "tools", tools));
                }
                return ResponseEntity.ok(Map.<String, Object>of(
                        "id", id, "serverKey", serverKey, "status", "not_connected"));
            } catch (Exception e) {
                return ResponseEntity.ok(Map.<String, Object>of(
                        "id", id, "status", "error", "error", e.getMessage()));
            }
        }).orElse(ResponseEntity.notFound().build());
    }

    /** 使用临时连接测试，不产生持久副作用 */
    @PostMapping("/{id}/test")
    public ResponseEntity<Map<String, Object>> testCapability(@PathVariable String id) {
        return registryService.findById(id).map(def -> {
            McpServerConnection tempConn = null;
            try {
                McpServerConfig config = mcpManager.buildConfigFromRegistry(def);
                tempConn = new McpServerConnection(config);
                tempConn.connect();
                boolean alive = tempConn.isAlive();
                return ResponseEntity.ok(Map.<String, Object>of(
                        "id", id,
                        "status", alive ? "reachable" : "unreachable",
                        "serverKey", def.extractServerKey()));
            } catch (Exception e) {
                return ResponseEntity.ok(Map.<String, Object>of(
                        "id", id, "status", "error", "error", e.getMessage()));
            } finally {
                if (tempConn != null) tempConn.close();
            }
        }).orElse(ResponseEntity.notFound().build());
    }

    /** 优先使用持久连接调用 MCP 工具，回退到临时连接 */
    @PostMapping("/{id}/invoke")
    public ResponseEntity<Map<String, Object>> invokeCapability(
            @PathVariable String id, @RequestBody Map<String, Object> body) {
        return registryService.findById(id).map(def -> {
            try {
                // 从请求体中提取实际参数和超时
                @SuppressWarnings("unchecked")
                Map<String, Object> arguments = body.containsKey("arguments")
                        ? (Map<String, Object>) body.get("arguments") : body;
                long timeout = body.containsKey("timeout")
                        ? ((Number) body.get("timeout")).longValue()
                        : (def.timeoutMs() > 0 ? def.timeoutMs() : 30000);
                String serverKey = def.extractServerKey();
                // 优先使用已建立的持久连接
                var existingConn = mcpManager.getConnection(serverKey);
                if (existingConn.isPresent() && existingConn.get().isAlive()) {
                    McpServerConnection conn = existingConn.get();
                    // 确保 MCP 协议已初始化
                    try {
                        conn.request("initialize", Map.of(
                                "protocolVersion", "2024-11-05",
                                "capabilities", Map.of(),
                                "clientInfo", Map.of("name", "zhikucode", "version", "1.0.0")), 15000);
                        conn.sendNotification("notifications/initialized");
                    } catch (Exception ignored) { }
                    JsonNode result = conn.callTool(def.toolName(), arguments, timeout);
                    ObjectMapper om = new ObjectMapper();
                    Object resultObj = om.treeToValue(result, Object.class);
                    return ResponseEntity.ok(Map.<String, Object>of(
                            "id", id, "status", "success",
                            "toolName", def.toolName(),
                            "connectionType", "persistent",
                            "result", resultObj));
                }
                // 回退到临时连接
                McpServerConnection tempConn = null;
                try {
                    McpServerConfig config = mcpManager.buildConfigFromRegistry(def);
                    tempConn = new McpServerConnection(config);
                    tempConn.connect();
                    // SSE 连接需要等待握手
                    Thread.sleep(2000);
                    if (!tempConn.isAlive()) {
                        return ResponseEntity.ok(Map.<String, Object>of(
                                "id", id, "status", "error", "error", "Connection not alive after handshake"));
                    }
                    JsonNode result = tempConn.callTool(def.toolName(), arguments, timeout);
                    ObjectMapper om = new ObjectMapper();
                    Object resultObj = om.treeToValue(result, Object.class);
                    return ResponseEntity.ok(Map.<String, Object>of(
                            "id", id, "status", "success",
                            "toolName", def.toolName(),
                            "connectionType", "temporary",
                            "result", resultObj));
                } finally {
                    if (tempConn != null) tempConn.close();
                }
            } catch (Exception e) {
                return ResponseEntity.ok(Map.<String, Object>of(
                        "id", id, "status", "error", "error", e.getMessage()));
            }
        }).orElse(ResponseEntity.notFound().build());
    }
}
