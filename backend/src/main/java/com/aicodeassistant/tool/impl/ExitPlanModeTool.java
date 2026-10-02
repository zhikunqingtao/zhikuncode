package com.aicodeassistant.tool.impl;

import com.aicodeassistant.tool.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * ExitPlanModeTool — 保留兼容入口，本工具尚未接入权限模式退出与恢复。
 * <p>
 * 调用返回能力不可用，不改变权限、不提交审批、不展示计划文件。
 */
@Component
public class ExitPlanModeTool implements Tool {

    @Override
    public String getName() {
        return "ExitPlanMode";
    }

    @Override
    public String getDescription() {
        return "Unavailable: this tool is not wired to exit the PLAN permission mode or restore previous permissions. "
                + "This tool does not change permissions, submit approval, or display a plan file.";
    }

    @Override
    public String prompt() {
        return """
                ExitPlanMode is currently unavailable; this tool is not wired to exit the PLAN \
                permission mode or restore previous permissions.
                The application's PLAN permission mode exists independently of this tool.
                Do not call this tool to change permissions, request approval, or display a plan file.
                Calls return PLAN_MODE_UNAVAILABLE without performing any of those actions. \
                Retrying this tool will not make the capability available.
                """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "plan_summary", Map.of("type", "string",
                                "description", "Optional plan summary echoed in the unavailable response; does not submit approval or display a plan file")
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
        String summary = input.getOptionalString("plan_summary")
                .map(value -> " Supplied plan summary: " + value)
                .orElse("");
        return ToolResult.validationError("PLAN_MODE_UNAVAILABLE",
                "This tool is not wired to exit the PLAN permission mode or restore previous permissions. "
                        + "No permission mode was changed, no approval request was submitted, "
                        + "and no plan file was displayed." + summary);
    }
}
