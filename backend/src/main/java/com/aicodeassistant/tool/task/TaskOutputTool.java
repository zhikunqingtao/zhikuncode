package com.aicodeassistant.tool.task;

import com.aicodeassistant.tool.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * TaskOutputTool — 子任务向父任务报告输出结果。
 * <p>
 * 仅在子任务上下文中可用（{@code context.currentTaskId()} 非空）。
 * 输出大小限制 1048576 characters，超过时截断。
 *
 */
@Component
public class TaskOutputTool implements Tool {

    private final TaskCoordinator taskCoordinator;

    public TaskOutputTool(TaskCoordinator taskCoordinator) {
        this.taskCoordinator = taskCoordinator;
    }

    @Override
    public String getName() {
        return "TaskOutput";
    }

    @Override
    public String getDescription() {
        return "Report output from a sub-task to its parent task. " +
                "Can only be used within a sub-task context.";
    }

    @Override
    public String prompt() {
        return """
                Report output from a sub-task to its parent task. Can only be used within \
                a sub-task context.
                
                Parameters:
                - output (required): Output content to report to parent task
                - isError (optional): Whether this output represents an error (default: false)
                
                Notes:
                - Maximum output size is 1048576 characters; content exceeding this limit will be truncated
                - The output is written to the parent task's result buffer
                - After execution ends, only display output is replaced; final status and error are preserved,
                  including when isError is true.
                """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "output", Map.of(
                                "type", "string",
                                "description", "Output content to report to parent task"),
                        "isError", Map.of(
                                "type", "boolean",
                                "description", "Whether this output represents an error (default: false)")
                ),
                "required", List.of("output")
        );
    }

    @Override
    public String getGroup() {
        return "task";
    }

    @Override
    public PermissionRequirement getPermissionRequirement() {
        return PermissionRequirement.NONE;
    }

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        // 1. 仅在子任务上下文中启用
        String currentTaskId = context.currentTaskId();
        if (currentTaskId == null) {
            return ToolResult.validationError("TASK_CONTEXT_REQUIRED",
                    "TaskOutput can only be used within a sub-task context.");
        }

        String output = input.getString("output");
        boolean isError = input.getBoolean("isError", false);

        Optional<TaskState.Snapshot> updated = taskCoordinator.updateOutput(currentTaskId,
                context.sessionId(), output, isError);
        if (updated.isEmpty()) {
            return ToolResult.validationError("TASK_NOT_FOUND", "Task not found: " + currentTaskId);
        }
        output = updated.get().output();

        if (updated.get().status().isTerminal()) {
            return ToolResult.success("Display output updated. Final execution status and error were preserved. "
                    + "Length: " + output.length() + " chars.");
        }

        // 4. 返回确认
        return ToolResult.success("Output " + (isError ? "(error) " : "")
                + "reported to parent task. Length: " + output.length() + " chars.");
    }

    @Override
    public boolean isConcurrencySafe(ToolInput input) {
        return true;
    }
}
