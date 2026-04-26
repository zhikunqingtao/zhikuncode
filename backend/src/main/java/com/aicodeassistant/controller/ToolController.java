package com.aicodeassistant.controller;

import com.aicodeassistant.exception.ResourceNotFoundException;
import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.ToolRegistry;
import com.aicodeassistant.tool.ToolSessionState;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 工具管理 Controller — 列出、查询和开关工具。
 *
 */
@RestController
@RequestMapping("/api/tools")
public class ToolController {

    private final ToolRegistry toolRegistry;
    private final ToolSessionState toolSessionState;

    public ToolController(ToolRegistry toolRegistry, ToolSessionState toolSessionState) {
        this.toolRegistry = toolRegistry;
        this.toolSessionState = toolSessionState;
    }

    /** 列出当前可用的所有工具（含 MCP 动态工具） */
    @GetMapping
    public ResponseEntity<ToolListResponse> listTools(
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String toolName) {
        // 单个工具查询时验证存在性
        if (toolName != null && !toolName.isBlank()) {
            toolRegistry.findByNameOptional(toolName)
                .orElseThrow(() -> new ResourceNotFoundException(
                    "TOOL_NOT_FOUND", "Tool not found: " + toolName));
        }
        List<ToolInfo> tools = toolRegistry.getAllTools().stream()
                .map(t -> {
                    boolean enabled = t.isEnabled();
                    // 会话级状态覆盖
                    if (sessionId != null && !sessionId.isBlank()) {
                        Boolean sessionOverride = toolSessionState.getToolState(sessionId, t.getName());
                        if (sessionOverride != null) enabled = sessionOverride;
                    }
                    return new ToolInfo(
                            t.getName(),
                            t.getDescription(),
                            t.getGroup(),
                            t.getPermissionRequirement().name(),
                            enabled);
                })
                .toList();
        return ResponseEntity.ok(new ToolListResponse(tools));
    }

    /** 获取工具详情（含输入 Schema） */
    @GetMapping("/{toolName}")
    public ResponseEntity<ToolDetail> getToolDetail(@PathVariable String toolName) {
        Tool tool = toolRegistry.findByName(toolName);
        return ResponseEntity.ok(new ToolDetail(
                tool.getName(),
                tool.getDescription(),
                tool.getGroup(),
                tool.getPermissionRequirement().name(),
                tool.getInputSchema()));
    }

    /** 启用/禁用指定工具 */
    @PatchMapping("/{toolName}")
    public ResponseEntity<Map<String, Object>> toggleTool(
            @PathVariable String toolName,
            @RequestBody ToggleToolRequest request) {
        // 验证工具存在性
        toolRegistry.findByNameOptional(toolName)
            .orElseThrow(() -> new ResourceNotFoundException(
                "TOOL_NOT_FOUND", "Tool not found: " + toolName));
        // 持久化会话级状态
        String sessionId = request.sessionId();
        if (sessionId != null && !sessionId.isBlank()) {
            toolSessionState.setToolState(sessionId, toolName, request.enabled());
        }
        return ResponseEntity.ok(Map.of(
                "tool", toolName, "enabled", request.enabled()));
    }

    // ═══ DTO Records ═══
    public record ToolListResponse(List<ToolInfo> tools) {}
    public record ToolInfo(String name, String description, String category,
                           String permissionLevel, boolean enabled) {}
    public record ToolDetail(String name, String description, String category,
                             String permissionLevel, Object inputSchema) {}
    public record ToggleToolRequest(String sessionId, boolean enabled) {}
}
