package com.aicodeassistant.tool.task;

import com.aicodeassistant.tool.*;
import com.aicodeassistant.model.TaskStatus;
import com.aicodeassistant.tool.agent.SubAgentExecutor;
import com.aicodeassistant.tool.agent.SubAgentExecutor.AgentRequest;
import com.aicodeassistant.tool.agent.SubAgentExecutor.AgentResult;
import com.aicodeassistant.tool.agent.SubAgentExecutor.IsolationMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TaskCreateTool — 创建后台执行的子任务。
 * <p>
 * 任务类型:
 * <ul>
 *   <li>shell: 执行一次 Shell 命令</li>
 *   <li>agent: 创建子代理执行复杂任务（默认）</li>
 *   <li>local_workflow: 使用 workflow 类型执行一次子代理任务</li>
 *   <li>monitor_mcp: 使用 monitor 类型执行一次子代理任务</li>
 *   <li>dream: 使用 dream 类型执行一次子代理任务</li>
 * </ul>
 *
 */
@Component
public class TaskCreateTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(TaskCreateTool.class);
    private static final List<String> SUPPORTED_TASK_TYPES =
            List.of("agent", "shell", "local_workflow", "monitor_mcp", "dream");

    private final TaskCoordinator taskCoordinator;
    private final SubAgentExecutor subAgentExecutor;
    private final ToolRegistry toolRegistry;
    private final TaskShellExecutor shellExecutor;

    public TaskCreateTool(TaskCoordinator taskCoordinator,
                          SubAgentExecutor subAgentExecutor,
                          @Lazy ToolRegistry toolRegistry,
                          @Lazy TaskShellExecutor shellExecutor) {
        this.taskCoordinator = taskCoordinator;
        this.subAgentExecutor = subAgentExecutor;
        this.toolRegistry = toolRegistry;
        this.shellExecutor = shellExecutor;
    }

    @Override
    public String getName() {
        return "TaskCreate";
    }

    @Override
    public String getDescription() {
        return "Submit a background task for immediate execution and return its task ID. " +
                "Use TodoWrite for a planning or progress checklist.";
    }

    @Override
    public String prompt() {
        return """
                Submit an independent task for immediate background execution. Calling this tool \
                starts work; use TodoWrite for a planning or progress checklist.

                ## Inputs
                - description: A short description stored with the task.
                - prompt: Instructions to execute, or the command text for a shell task.
                - taskType: An exact lowercase type name; defaults to agent when omitted.

                ## Task Types
                - agent: Execute one general-purpose sub-agent invocation.
                - shell: Execute one command through the Bash tool.
                - local_workflow: Execute one sub-agent invocation using the workflow compatibility alias (currently general-purpose).
                - monitor_mcp: Execute one sub-agent invocation using the monitor compatibility alias (currently general-purpose); \
                this does not schedule repeated monitoring.
                - dream: Execute one sub-agent invocation using the dream compatibility alias (currently general-purpose) with \
                ordinary execution priority.

                ## Result
                Returns a task ID and a creation acknowledgment, not the execution result. \
                Use TaskList or TaskGet to inspect the task's recorded status and any stored output. \
                Check TaskList before submitting duplicate work.
                """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "description", Map.of(
                                "type", "string",
                                "description", "Short description stored with the background task"),
                        "prompt", Map.of(
                                "type", "string",
                                "description", "Instructions to execute, or command text for a shell task"),
                        "taskType", Map.of(
                                "type", "string",
                                "enum", SUPPORTED_TASK_TYPES,
                                "description", "Exact lowercase execution type (default: agent)")
                ),
                "required", List.of("description", "prompt")
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
        String description = input.getString("description");
        String prompt = input.getString("prompt");
        String taskType = input.getString("taskType", "agent");

        if (!SUPPORTED_TASK_TYPES.contains(taskType)) {
            return ToolResult.validationError("TASK_TYPE_UNSUPPORTED",
                    "Unsupported taskType: " + taskType + ". Supported types: "
                            + String.join(", ", SUPPORTED_TASK_TYPES));
        }

        String taskId = UUID.randomUUID().toString().substring(0, 8);
        String sessionId = context.sessionId();

        log.info("Creating task: id={}, type={}, description={}", taskId, taskType, description);

        try {
            taskCoordinator.submitResult(taskId, sessionId, description, () -> switch (taskType) {
                case "agent" -> executeAgentTask(taskId, prompt, context, null, "task-", false);
                case "shell" -> executeShellTask(taskId, prompt, context);
                case "local_workflow" -> executeAgentTask(taskId, prompt, context, "workflow", "workflow-", false);
                case "monitor_mcp" -> executeAgentTask(taskId, monitorPrompt(prompt), context,
                        "monitor", "mcp-monitor-", true);
                case "dream" -> executeAgentTask(taskId, prompt, context, "dream", "dream-", true);
                default -> throw new IllegalStateException("Unsupported taskType: " + taskType);
            });
            return ToolResult.success("Task #" + taskId + " created successfully: " + description);
        } catch (TaskCoordinator.SubmissionException failure) {
            return ToolResult.failed(ToolResult.ToolFailureType.PROCESS, failure.code(),
                    "Failed to create task: " + failure.getMessage(), ToolResult.Retryability.NEVER,
                    ToolResult.EffectState.NOT_STARTED, null, Map.of());
        } catch (IllegalStateException failure) {
            return ToolResult.internalError("TASK_CREATE_FAILED", "Failed to create task: " + failure.getMessage(),
                    ToolResult.EffectState.UNKNOWN);
        }
    }

    private TaskExecutionResult executeAgentTask(String taskId, String prompt, ToolUseContext context,
                                                 String agentType, String prefix, boolean backgroundFlag) {
        AgentRequest request = new AgentRequest(prefix + taskId, prompt, agentType, null,
                IsolationMode.NONE, backgroundFlag);
        AtomicBoolean returned = new AtomicBoolean();
        taskCoordinator.registerCancellationCleanup(taskId, () -> {
            if (returned.get()) subAgentExecutor.finishTaskCancellation(request, context);
        });
        AgentResult result = subAgentExecutor.executeTaskSync(request, context);
        returned.set(true);
        return fromAgentResult(result);
    }

    private TaskExecutionResult executeShellTask(String taskId, String prompt, ToolUseContext context) {
        var bashTool = toolRegistry.findByNameOptional("Bash");
        if (bashTool.isEmpty()) return TaskExecutionResult.failed(null, "BASH_TOOL_UNAVAILABLE: Bash tool is not registered");
        ToolExecutionResult execution = shellExecutor.execute(bashTool.get(),
                ToolInput.from(Map.of("command", prompt)), taskId, context);
        return fromToolResult(execution == null ? null : execution.result());
    }

    /** Preserve the existing interval-prefix compatibility; this is still a single invocation. */
    private String monitorPrompt(String prompt) {
        if (!prompt.startsWith("interval=")) return prompt;
        String[] parts = prompt.split("\\s+", 2);
        try {
            Integer.parseInt(parts[0].substring("interval=".length()));
            return parts.length > 1 ? parts[1] : prompt;
        } catch (NumberFormatException failure) {
            log.warn("Invalid interval in monitor_mcp prompt; preserving the original prompt");
            return prompt;
        }
    }

    static TaskExecutionResult fromAgentResult(AgentResult result) {
        if (result == null) return TaskExecutionResult.failed(null, "AGENT_RESULT_MISSING: Agent returned no result");
        if (AgentResult.STATUS_COMPLETED.equals(result.status())) return TaskExecutionResult.completed(result.result());
        String error = "Agent ended with status " + result.status()
                + (result.result() == null ? "" : ": " + result.result());
        if (AgentResult.STATUS_INTERRUPTED.equals(result.status())) {
            return TaskExecutionResult.cancelled(result.result(), error);
        }
        return TaskExecutionResult.failed(result.result(), error);
    }

    static TaskExecutionResult fromToolResult(ToolResult result) {
        if (result == null) return TaskExecutionResult.failed(null, "TOOL_RESULT_MISSING: Shell execution returned no result");
        String error = result.isError() ? result.failureCode() + ": " + result.content() : null;
        return new TaskExecutionResult(switch (result.executionStatus()) {
            case SUCCEEDED -> TaskStatus.COMPLETED;
            case CANCELLED -> TaskStatus.CANCELLED;
            case FAILED, TIMED_OUT -> TaskStatus.FAILED;
        }, result.content(), error);
    }

    @Override
    public boolean isConcurrencySafe(ToolInput input) {
        return true;
    }
}
