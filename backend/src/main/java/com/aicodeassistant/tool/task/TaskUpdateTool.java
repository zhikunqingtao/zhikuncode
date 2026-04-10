package com.aicodeassistant.tool.task;

import com.aicodeassistant.tool.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * TaskUpdateTool — 更新后台任务的状态或输出。
 *
 * @see <a href="SPEC §4.1.3">TaskUpdateTool</a>
 */
@Component
public class TaskUpdateTool implements Tool {

    private final TaskCoordinator taskCoordinator;

    public TaskUpdateTool(TaskCoordinator taskCoordinator) {
        this.taskCoordinator = taskCoordinator;
    }

    @Override
    public String getName() {
        return "TaskUpdate";
    }

    @Override
    public String getDescription() {
        return "Update the status or output of an existing background task.";
    }

    @Override
    public String prompt() {
        return """
                Use this tool to update a task in the task list.
                
                ## When to Use This Tool
                **Mark tasks as resolved:**
                - When you have completed the work described in a task
                - When a task is no longer needed or has been superseded
                - IMPORTANT: Always mark your assigned tasks as resolved when you finish them
                - After resolving, call TaskList to find your next task
                
                - ONLY mark a task as completed when you have FULLY accomplished it
                - If you encounter errors, blockers, or cannot finish, keep the task as in_progress
                - Never mark a task as completed if:
                  - Tests are failing
                  - Implementation is partial
                  - You encountered unresolved errors
                
                **Update task details:**
                - When requirements change or become clearer
                
                ## Status Workflow
                Status progresses: `pending` \u2192 `in_progress` \u2192 `completed`
                
                ## Examples
                Mark task as in progress: {"taskId": "1", "status": "in_progress"}
                Mark task as completed: {"taskId": "1", "status": "completed"}
                """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "taskId", Map.of(
                                "type", "string",
                                "description", "Task ID to update"),
                        "status", Map.of(
                                "type", "string",
                                "description", "New status for the task"),
                        "output", Map.of(
                                "type", "string",
                                "description", "Output content to set")
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

        // 更新状态（如果提供）
        input.getOptionalString("status").ifPresent(statusStr -> {
            TaskStatus newStatus = TaskStatus.valueOf(statusStr.toUpperCase());
            task.setStatus(newStatus);
        });

        // 更新输出（如果提供）
        input.getOptionalString("output").ifPresent(task::setOutput);

        return ToolResult.success("Task " + taskId + " updated. Status: " + task.getStatus());
    }

    @Override
    public boolean isConcurrencySafe(ToolInput input) {
        return true;
    }
}
