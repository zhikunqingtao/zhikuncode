package com.aicodeassistant.tool.impl;

import com.aicodeassistant.tool.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * EnterPlanModeTool — 切换到计划模式。
 * <p>
 * LLM 主动调用此工具进入只读规划阶段。
 * 在 PLAN 模式下，只允许只读工具自动执行，写入工具仍可执行但需要确认。
 *
 * @see <a href="SPEC §3.2.3">EnterPlanModeTool 规范</a>
 */
@Component
public class EnterPlanModeTool implements Tool {

    @Override
    public String getName() {
        return "EnterPlanMode";
    }

    @Override
    public String getDescription() {
        return "Switch to plan mode for read-only planning. "
                + "Write operations will require confirmation in this mode.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "reason", Map.of("type", "string",
                                "description", "Reason for entering plan mode")
                ),
                "required", List.of()
        );
    }

    @Override
    public boolean isReadOnly(ToolInput input) {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(ToolInput input) {
        return true;
    }

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        String reason = input.getOptionalString("reason").orElse("Planning phase");
        // P0: 简单模式切换 — 后续 Round 实现 AppState 权限模式联动
        return ToolResult.success("Entered plan mode. Write operations require confirmation. Reason: " + reason)
                .withMetadata("mode", "plan");
    }
}
