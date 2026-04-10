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
    public String prompt() {
        return """
                Use this tool proactively when you're about to start a non-trivial implementation task. \
                Getting user sign-off on your approach before writing code prevents wasted effort and \
                ensures alignment. This tool transitions you into plan mode where you can explore the \
                codebase and design an implementation approach for user approval.
                
                ## When to Use This Tool
                **Prefer using EnterPlanMode** for implementation tasks unless they're simple. \
                Use it when ANY of these conditions apply:
                1. **New Feature Implementation**: Adding meaningful new functionality
                2. **Multiple Valid Approaches**: The task can be solved in several different ways
                3. **Code Modifications**: Changes that affect existing behavior or structure
                4. **Architectural Decisions**: Choosing between patterns or technologies
                5. **Multi-File Changes**: The task will likely touch more than 2-3 files
                6. **Unclear Requirements**: You need to explore before understanding the full scope
                7. **User Preferences Matter**: The implementation could reasonably go multiple ways
                
                ## When NOT to Use This Tool
                Only skip EnterPlanMode for simple tasks:
                - Single-line or few-line fixes (typos, obvious bugs, small tweaks)
                - Adding a single function with clear requirements
                - Tasks where the user has given very specific, detailed instructions
                - Pure research/exploration tasks (use the Agent tool instead)
                
                ## What Happens in Plan Mode
                In plan mode, you'll:
                1. Thoroughly explore the codebase using Glob, Grep, and Read tools
                2. Understand existing patterns and architecture
                3. Design an implementation approach
                4. Present your plan to the user for approval
                5. Use AskUserQuestion if you need to clarify approaches
                6. Exit plan mode with ExitPlanMode when ready to implement
                
                ## Important Notes
                - This tool REQUIRES user approval - they must consent to entering plan mode
                - If unsure whether to use it, err on the side of planning
                """;
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
