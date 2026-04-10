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
 * 输出大小限制 1MB，超过时截断。
 *
 * @see <a href="SPEC §4.1.15">TaskOutputTool</a>
 */
@Component
public class TaskOutputTool implements Tool {

    private static final int MAX_OUTPUT_SIZE = 1024 * 1024; // 1MB

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
                - Maximum output size is 1MB; content exceeding this limit will be truncated
                - The output is written to the parent task's result buffer
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
            return ToolResult.error(
                    "TaskOutput can only be used within a sub-task context.");
        }

        String output = input.getString("output");
        boolean isError = input.getBoolean("isError", false);

        // 2. 输出大小限制 (1MB)
        if (output.length() > MAX_OUTPUT_SIZE) {
            output = output.substring(0, MAX_OUTPUT_SIZE)
                    + "\n[Output truncated at 1MB limit]";
        }

        // 3. 写入父任务的结果缓冲区
        Optional<TaskState> taskOpt = taskCoordinator.getTask(currentTaskId);
        if (taskOpt.isEmpty()) {
            return ToolResult.error("Task not found: " + currentTaskId);
        }
        TaskState task = taskOpt.get();
        task.setOutput(output);
        if (isError) {
            task.setError(output);
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
