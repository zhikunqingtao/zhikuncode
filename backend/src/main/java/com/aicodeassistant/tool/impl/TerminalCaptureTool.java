package com.aicodeassistant.tool.impl;

import com.aicodeassistant.tool.*;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class TerminalCaptureTool implements Tool {

    @Override public String getName() { return "TerminalCapture"; }
    @Override public String getDescription() {
        return "捕获终端可视区域的当前内容";
    }
    @Override public String getGroup() { return "execution"; }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "terminal_id", Map.of("type", "string", "description", "终端 ID，默认活动终端"),
                "lines", Map.of("type", "integer", "description", "捕获行数，默认 50")
            )
        );
    }

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        int lines = input.getInt("lines", 50);
        String terminalId = input.getString("terminal_id", null);
        // Stub 实现 — 需配合 IDE Bridge 获取终端内容
        return ToolResult.success("终端捕获功能需配合 IDE Bridge 实现");
    }

    @Override public PermissionRequirement getPermissionRequirement() {
        return PermissionRequirement.NONE;
    }
}
