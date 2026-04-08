package com.aicodeassistant.tool.task;

import com.aicodeassistant.tool.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * TaskListTool — 列出当前会话的后台任务。
 *
 * @see <a href="SPEC §4.1.3">TaskListTool</a>
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
        return "List background tasks in the current session, optionally filtered by status.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "status", Map.of(
                                "type", "string",
                                "description", "Filter tasks by status (PENDING/RUNNING/COMPLETED/FAILED/CANCELLED)")
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
        TaskStatus filter = input.getOptionalString("status")
                .map(s -> TaskStatus.valueOf(s.toUpperCase()))
                .orElse(null);

        List<TaskState> tasks = taskCoordinator.listTasks(context.sessionId(), filter);

        if (tasks.isEmpty()) {
            return ToolResult.success("No tasks found.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Tasks (").append(tasks.size()).append("):\n");
        for (TaskState t : tasks) {
            sb.append(String.format("  [%s] %s — %s%n",
                    t.getStatus(), t.getTaskId(),
                    t.getDescription() != null ? t.getDescription() : "(no description)"));
            if (t.getOutput() != null) {
                String preview = t.getOutput().length() > 100
                        ? t.getOutput().substring(0, 100) + "..."
                        : t.getOutput();
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
