package com.aicodeassistant.tool.task;

import com.aicodeassistant.model.TaskStatus;
import com.aicodeassistant.tool.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * TaskListTool — 列出当前会话的后台任务。
 *
 */
@Component
public class TaskListTool implements Tool {

    private final TaskCoordinator taskCoordinator;

    public TaskListTool(TaskCoordinator taskCoordinator) {
        this.taskCoordinator = taskCoordinator;
    }

    @Override
    public String getName() {
        return "TaskList";
    }

    @Override
    public String getDescription() {
        return "List background task records in the current session, newest first, optionally filtered by status.";
    }

    @Override
    public String prompt() {
        return """
                List background task records for the current session, ordered by creation time \
                from newest to oldest. Use TodoWrite for a planning or progress checklist.

                ## Optional Filter
                status accepts PENDING, RUNNING, IN_PROGRESS, COMPLETED, FAILED, CANCELLED, \
                or KILLED, ignoring case. Omit status to list all recorded tasks in this session.

                ## Output
                Each entry contains the recorded status, task ID, description, and an output \
                preview when output has been stored. Use the task ID with TaskGet to inspect \
                the full record. Cancellation requested is shown separately until execution exits. \
                COMPLETED means execution reported success, not independent verification of its work.
                """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "status", Map.of(
                                "type", "string",
                                "description", "Filter by recorded status, ignoring case: PENDING, RUNNING, IN_PROGRESS, COMPLETED, FAILED, CANCELLED, KILLED")
                )
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
        TaskStatus filter = null;
        String requestedStatus = input.getOptionalString("status").orElse(null);
        if (requestedStatus != null) {
            try {
                filter = TaskStatus.valueOf(requestedStatus.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException failure) {
                return ToolResult.validationError("TASK_STATUS_INVALID", "Invalid task status: " + requestedStatus);
            }
        }
        List<TaskState.Snapshot> tasks = taskCoordinator.listTaskSnapshots(context.sessionId(), filter);

        if (tasks.isEmpty()) {
            return ToolResult.success("No tasks found.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Tasks (").append(tasks.size()).append("):\n");
        for (TaskState.Snapshot t : tasks) {
            sb.append(String.format("  [%s] %s — %s%n",
                    t.status(), t.taskId(),
                    t.description() != null ? t.description() : "(no description)"));
            if (t.cancellationRequested()) {
                sb.append("    Cancellation requested; termination confirmed: ")
                        .append(t.terminationConfirmed()).append("\n");
            }
            if (t.output() != null) {
                String preview = t.output().length() > 100
                        ? t.output().substring(0, 100) + "..."
                        : t.output();
                sb.append("    Output: ").append(preview).append("\n");
            }
        }
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
