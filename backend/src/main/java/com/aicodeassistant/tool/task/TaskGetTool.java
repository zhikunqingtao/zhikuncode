package com.aicodeassistant.tool.task;

import com.aicodeassistant.tool.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * TaskGetTool — 获取单个后台任务的详细信息。
 *
 * @see <a href="SPEC §4.1.3">TaskGetTool</a>
 */
@Component
public class TaskGetTool implements Tool {

    private final TaskCoordinator taskCoordinator;

    public TaskGetTool(TaskCoordinator taskCoordinator) {
        this.taskCoordinator = taskCoordinator;
    }

    @Override
    public String getName() {
        return "TaskGet";
    }

    @Override
    public String getDescription() {
        return "Get detailed information about a specific background task.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "taskId", Map.of(
                                "type", "string",
                                "description", "Task ID to query")
                ),
                "required", List.of("taskId")
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
        String taskId = input.getString("taskId");
        Optional<TaskState> taskOpt = taskCoordinator.getTask(taskId);
        if (taskOpt.isEmpty()) {
            return ToolResult.error("Task not found: " + taskId);
        }
        TaskState task = taskOpt.get();

        StringBuilder sb = new StringBuilder();
        sb.append("Task: ").append(task.getTaskId()).append("\n");
        sb.append("Status: ").append(task.getStatus()).append("\n");
        sb.append("Description: ").append(
                task.getDescription() != null ? task.getDescription() : "(none)").append("\n");
        sb.append("Created: ").append(task.getCreatedAt()).append("\n");
        if (task.getOutput() != null) {
            sb.append("Output:\n").append(task.getOutput()).append("\n");
        }
        if (task.getError() != null) {
            sb.append("Error: ").append(task.getError()).append("\n");
        }
        sb.append("Child tasks: ").append(task.getChildTaskIds().size());
        return ToolResult.success(sb.toString());
    }

    @Override
    public boolean isReadOnly(ToolInput input) {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(ToolInput input) {
        return true;
    }
}
