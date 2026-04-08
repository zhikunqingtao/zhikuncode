package com.aicodeassistant.controller;

import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.ToolRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 工具管理 Controller — 列出、查询和开关工具。
 *
 * @see <a href="SPEC §6.1.6a">ToolController</a>
 */
@RestController
@RequestMapping("/api/tools")
public class ToolController {

    private final ToolRegistry toolRegistry;

    public ToolController(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /** 列出当前可用的所有工具（含 MCP 动态工具） */
    @GetMapping
    public ResponseEntity<ToolListResponse> listTools(
            @RequestParam(required = false) String sessionId) {
        List<ToolInfo> tools = toolRegistry.getAllTools().stream()
                .map(t -> new ToolInfo(
                        t.getName(),
                        t.getDescription(),
                        t.getGroup(),
                        t.getPermissionRequirement().name(),
                        t.isEnabled()))
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
        // P0: 工具启用/禁用需要 ToolRegistry 支持会话级控制
        // 当前返回确认，实际状态管理在 P1 完善
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
