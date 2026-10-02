package com.aicodeassistant.tool.task;

import com.aicodeassistant.tool.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * TaskGetTool — 获取单个后台任务的详细信息。
 *
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
        return "Get a background task record by taskId, including stored output and error when available.";
    }

    @Override
    public String prompt() {
        return """
                Retrieve a background task record belonging to the current session using taskId. Use TaskList to find task IDs \
                in the current session, and TodoWrite for a planning or progress checklist.

                ## Output
                Returns the task ID, recorded status, description, creation time, and child task count. \
                Stored output and error are included when available.

                Recorded statuses are PENDING, RUNNING, IN_PROGRESS, COMPLETED, FAILED, CANCELLED, \
                and KILLED. Cancellation requested is reported separately until execution exits; \
                COMPLETED means execution reported success, not independent verification of its work.
                """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "taskId", Map.of(
                                "type", "string",
                                "description", "Background task ID to query")
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
        Optional<TaskState.Snapshot> taskOpt = taskCoordinator.getTask(taskId, context.sessionId());
        if (taskOpt.isEmpty()) {
            return ToolResult.validationError("TASK_NOT_FOUND", "Task not found: " + taskId);
        }
        TaskState.Snapshot task = taskOpt.get();

        StringBuilder sb = new StringBuilder();
        sb.append("Task: ").append(task.taskId()).append("\n");
        sb.append("Status: ").append(task.status()).append("\n");
        sb.append("Cancellation requested: ").append(task.cancellationRequested()).append("\n");
        sb.append("Termination confirmed: ").append(task.terminationConfirmed()).append("\n");
        sb.append("Description: ").append(
                task.description() != null ? task.description() : "(none)").append("\n");
        sb.append("Created: ").append(task.createdAt()).append("\n");
        if (task.output() != null) {
            sb.append("Output:\n").append(task.output()).append("\n");
        }
        if (task.error() != null) {
            sb.append("Error: ").append(task.error()).append("\n");
        }
        sb.append("Child tasks: ").append(task.childTaskIds().size());
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
