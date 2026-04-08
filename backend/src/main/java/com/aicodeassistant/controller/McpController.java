package com.aicodeassistant.controller;

import com.aicodeassistant.mcp.McpClientManager;
import com.aicodeassistant.mcp.McpServerConfig;
import com.aicodeassistant.mcp.McpServerConnection;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * MCP 服务器管理 Controller — 管理 MCP 服务器连接。
 *
 * @see <a href="SPEC §6.1.4">MCP 管理 API</a>
 */
@RestController
@RequestMapping("/api/mcp/servers")
public class McpController {

    private final McpClientManager mcpManager;

    public McpController(McpClientManager mcpManager) {
        this.mcpManager = mcpManager;
    }

    /** 列出所有 MCP 服务器 */
    @GetMapping
    public ResponseEntity<Map<String, List<McpServerConnection>>> listServers() {
        return ResponseEntity.ok(Map.of("servers", mcpManager.listConnections()));
    }

    /** 添加新的 MCP 服务器 */
    @PostMapping
    public ResponseEntity<Map<String, Object>> addServer(
            @RequestBody McpServerConfig config) {
        mcpManager.addServer(config);
        return ResponseEntity.status(201).body(
                Map.of("name", config.name(), "status", "connecting"));
    }

    /** 删除 MCP 服务器 */
    @DeleteMapping("/{name}")
    public ResponseEntity<Map<String, Boolean>> deleteServer(@PathVariable String name) {
        mcpManager.removeServer(name);
        return ResponseEntity.ok(Map.of("success", true));
    }

    /** 重启 MCP 服务器 */
    @PostMapping("/{name}/restart")
    public ResponseEntity<Map<String, String>> restartServer(@PathVariable String name) {
        mcpManager.restartServer(name);
        return ResponseEntity.ok(Map.of("status", "connecting"));
    }

    /** 获取 MCP 服务器日志 */
    @GetMapping("/{name}/logs")
    public ResponseEntity<Map<String, List<String>>> getServerLogs(
            @PathVariable String name,
            @RequestParam(defaultValue = "100") int lines) {
        return ResponseEntity.ok(Map.of("logs", mcpManager.getServerLogs(name, lines)));
    }
}
