package com.aicodeassistant.tool.task;

import com.aicodeassistant.tool.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Request cancellation of a task owned by the current session; await actual execution exit. */
@Component
public class TaskStopTool implements Tool {

    private final TaskCoordinator taskCoordinator;

    public TaskStopTool(TaskCoordinator taskCoordinator) {
        this.taskCoordinator = taskCoordinator;
    }

    @Override
    public String getName() {
        return "TaskStop";
    }

    @Override
    public String getDescription() {
        return "Request cancellation of a background task in the current session. " +
                "Cancellation remains pending until its execution and owned resources exit.";
    }

    @Override
    public String prompt() {
        return """
                - Requests cancellation of a background task belonging to the current session
                - Takes a taskId parameter identifying the task to stop
                - Acknowledges the request; use TaskGet to observe terminal status after execution exits
                - Use this tool when you need to terminate a long-running task
                """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "taskId", Map.of(
                                "type", "string",
                                "description", "Task ID to stop"),
                        "reason", Map.of(
                                "type", "string",
                                "description", "Reason for cancellation")
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
        String reason = input.getString("reason", "User requested cancellation");

        return switch (taskCoordinator.cancelTask(taskId, context.sessionId())) {
            case NOT_FOUND -> ToolResult.validationError("TASK_NOT_FOUND", "Task not found: " + taskId);
            case ALREADY_TERMINAL -> ToolResult.validationError("TASK_ALREADY_TERMINAL",
                    "Task already in terminal state: " + taskId);
            case ALREADY_CANCELLED -> ToolResult.success("Task " + taskId
                    + " already cancelled. Termination confirmed; no further action was needed.",
                    Map.of("cancellationRequested", true, "terminationConfirmed", true));
            case REQUESTED -> ToolResult.successWithEffect("Task " + taskId
                    + " cancellation requested. Execution may still be exiting. Reason: " + reason,
                    ToolResult.EffectState.APPLIED, Map.of("cancellationRequested", true));
        };
    }

    @Override
    public boolean isConcurrencySafe(ToolInput input) {
        return true;
    }
}
