package com.aicodeassistant.tool.task;

import com.aicodeassistant.tool.*;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * TaskStopTool — 停止/取消一个正在执行的后台任务。
 * <p>
 * 通过 {@link TaskCoordinator#cancelTask(String)} 触发三层中断传播:
 * <ol>
 *   <li>Layer 1: future.cancel(true) → Thread.interrupt()</li>
 *   <li>Layer 2: activeTool.cancel() → 终止子进程</li>
 *   <li>Layer 3: 递归取消子任务</li>
 * </ol>
 *
 * @see <a href="SPEC §4.1.7">TaskStopTool</a>
 */
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
        return "Stop/cancel a running background task. " +
                "Uses three-layer interrupt propagation to cleanly terminate the task.";
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

        Optional<TaskState> taskOpt = taskCoordinator.getTask(taskId);
        if (taskOpt.isEmpty()) {
            return ToolResult.error("Task not found: " + taskId);
        }
        TaskState task = taskOpt.get();

        if (task.getStatus().isTerminal()) {
            return ToolResult.error("Task already in terminal state: " + task.getStatus());
        }

        // 三层中断传播（委托给 TaskCoordinator）
        boolean cancelled = taskCoordinator.cancelTask(taskId);
        if (cancelled) {
            return ToolResult.success(
                    "Task " + taskId + " cancelled. Reason: " + reason);
        } else {
            return ToolResult.error("Failed to cancel task: " + taskId);
        }
    }

    @Override
    public boolean isConcurrencySafe(ToolInput input) {
        return true;
    }
}
