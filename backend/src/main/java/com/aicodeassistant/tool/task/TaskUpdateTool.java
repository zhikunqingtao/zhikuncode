package com.aicodeassistant.tool.task;

import com.aicodeassistant.tool.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * TaskUpdateTool — 更新所属会话后台任务的输出。
 *
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
        return "Replace the stored output of an existing background task in the current session.";
    }

    @Override
    public String prompt() {
        return """
                Replace stored output of a background task belonging to the current session using taskId. \
                Use TodoWrite for a planning or progress checklist.

                ## Inputs
                - taskId: The existing background task ID.
                - output: Optional replacement text, limited to 1048576 characters before a truncation notice.

                Execution status is managed by the task coordinator. The status parameter is rejected; \
                use TaskStop to request cancellation. This tool does not start or stop execution. \
                During execution, output is a temporary progress note: a non-null final execution output \
                replaces it. After execution ends, output can still be replaced without changing status \
                or error. An empty string clears output. The response includes the task ID and status.
                """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "taskId", Map.of(
                                "type", "string",
                                "description", "Existing background task ID to update"),
                        "output", Map.of(
                                "type", "string",
                                "description", "Replacement text for the task's stored output")
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
        if (taskCoordinator.getTask(taskId, context.sessionId()).isEmpty()) {
            return ToolResult.validationError("TASK_NOT_FOUND", "Task not found: " + taskId);
        }
        // Reject the entire request before any mutation, including an explicit JSON null.
        if (input.has("status")) {
            return ToolResult.validationError("TASK_STATUS_READ_ONLY",
                    "Execution status is managed by the task coordinator. Use TaskStop to request cancellation.");
        }
        Optional<TaskState.Snapshot> updated = taskCoordinator.updateOutput(taskId, context.sessionId(),
                input.getOptionalString("output").orElse(null), false);
        if (updated.isEmpty()) return ToolResult.validationError("TASK_NOT_FOUND", "Task not found: " + taskId);
        return ToolResult.success("Task " + taskId + " updated. Status: " + updated.get().status());
    }

    @Override
    public boolean isConcurrencySafe(ToolInput input) {
        return true;
    }
}
