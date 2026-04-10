package com.aicodeassistant.tool.impl;

import com.aicodeassistant.tool.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * ExitPlanModeTool — 退出计划模式，恢复到之前的权限模式。
 * <p>
 * 如果提供了 plan_summary，记录到消息中。
 *
 * @see <a href="SPEC §3.2.3">ExitPlanModeTool 规范</a>
 */
@Component
public class ExitPlanModeTool implements Tool {

    @Override
    public String getName() {
        return "ExitPlanMode";
    }

    @Override
    public String getDescription() {
        return "Exit plan mode and restore the previous permission mode.";
    }

    @Override
    public String prompt() {
        return """
                Use this tool when you are in plan mode and have finished writing your plan \
                and are ready for user approval.
                
                ## How This Tool Works
                - You should have already written your plan to the plan file
                - This tool simply signals that you're done planning and ready for the user to review
                - The user will see the contents of your plan when they review it
                
                ## When to Use This Tool
                IMPORTANT: Only use this tool when the task requires planning the implementation \
                steps of a task that requires writing code. For research tasks where you're \
                gathering information, searching files, reading files or trying to understand \
                the codebase - do NOT use this tool.
                
                ## Before Using This Tool
                Ensure your plan is complete and unambiguous:
                - If you have unresolved questions about requirements or approach, use \
                AskUserQuestion first
                - Once your plan is finalized, use THIS tool to request approval
                
                **Important:** Do NOT use AskUserQuestion to ask "Is this plan okay?" or \
                "Should I proceed?" - that's exactly what THIS tool does.
                """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "plan_summary", Map.of("type", "string",
                                "description", "Summary of the plan")
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
                .map(s -> " Plan summary: " + s)
                .orElse("");
        // P0: 简单模式切换 — 后续 Round 实现 AppState 权限模式联动
        return ToolResult.success("Exited plan mode." + summary)
                .withMetadata("mode", "default");
    }
}
