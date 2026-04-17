package com.aicodeassistant.tool.task;

import com.aicodeassistant.tool.*;
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

/**
 * TaskCreateTool — 创建后台执行的子任务。
 * <p>
 * 任务类型:
 * <ul>
 *   <li>shell: 执行 Shell 命令并监控</li>
 *   <li>agent: 创建子代理执行复杂任务（默认）</li>
 *   <li>remote_agent: 远程代理任务</li>
 *   <li>in_process_teammate: 进程内协作者</li>
 *   <li>local_workflow: 基于脚本定义的自动化流程</li>
 *   <li>monitor_mcp: 长期运行的 MCP 服务监控任务</li>
 *   <li>dream: 后台梦境式思考任务</li>
 * </ul>
 *
 * @see <a href="SPEC §4.1.3">TaskCreateTool</a>
 * @see <a href="SPEC §4.1.3a">TaskCoordinator</a>
 */
@Component
public class TaskCreateTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(TaskCreateTool.class);

    private final TaskCoordinator taskCoordinator;
    private final SubAgentExecutor subAgentExecutor;
    private final ToolRegistry toolRegistry;

    public TaskCreateTool(TaskCoordinator taskCoordinator,
                          SubAgentExecutor subAgentExecutor,
                          @Lazy ToolRegistry toolRegistry) {
        this.taskCoordinator = taskCoordinator;
        this.subAgentExecutor = subAgentExecutor;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public String getName() {
        return "TaskCreate";
    }

    @Override
    public String getDescription() {
        return "Create a background task to execute independently. " +
                "Tasks run in their own virtual thread with a dedicated QueryEngine. " +
                "Use this for parallel execution of independent subtasks.";
    }

    @Override
    public String prompt() {
        return """
                Use this tool to create a structured task list for your current coding session. \
                This helps you track progress, organize complex tasks, and demonstrate thoroughness \
                to the user. It also helps the user understand the progress of the task and overall \
                progress of their requests.
                
                ## When to Use This Tool
                
                Use this tool proactively in these scenarios:
                - Complex multi-step tasks - When a task requires 3 or more distinct steps or actions
                - Non-trivial and complex tasks - Tasks that require careful planning or multiple operations
                - Plan mode - When using plan mode, create a task list to track the work
                - User explicitly requests todo list - When the user directly asks you to use the todo list
                - User provides multiple tasks - When users provide a list of things to be done
                - After receiving new instructions - Immediately capture user requirements as tasks
                - When you start working on a task - Mark it as in_progress BEFORE beginning work
                - After completing a task - Mark it as completed and add any new follow-up tasks
                
                ## When NOT to Use This Tool
                
                Skip using this tool when:
                - There is only a single, straightforward task
                - The task is trivial and tracking it provides no organizational benefit
                - The task can be completed in less than 3 trivial steps
                - The task is purely conversational or informational
                
                ## Tips
                - Create tasks with clear, specific subjects that describe the outcome
                - After creating tasks, use TaskUpdate to set up dependencies if needed
                - Check TaskList first to avoid creating duplicate tasks
                """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "description", Map.of(
                                "type", "string",
                                "description", "Task description"),
                        "prompt", Map.of(
                                "type", "string",
                                "description", "Task prompt / instructions"),
                        "taskType", Map.of(
                                "type", "string",
                                "enum", List.of("shell", "agent", "remote_agent",
                                        "in_process_teammate", "local_workflow",
                                        "monitor_mcp", "dream"),
                                "description", "Type of task to create (default: agent)")
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

        String taskId = UUID.randomUUID().toString().substring(0, 8);
        String sessionId = context.sessionId();

        log.info("Creating task: id={}, type={}, description={}", taskId, taskType, description);

        try {
            // 提交任务到 TaskCoordinator — 按 taskType 分发执行
            TaskState taskState = taskCoordinator.submit(taskId, sessionId, description, () -> {
                switch (taskType) {
                    case "agent" -> executeAgentTask(taskId, prompt, context);
                    case "shell" -> executeShellTask(taskId, prompt, context);
                    case "local_workflow" -> executeLocalWorkflowTask(taskId, prompt, context);
                    case "monitor_mcp" -> executeMonitorMcpTask(taskId, prompt, context);
                    case "dream" -> executeDreamTask(taskId, prompt, context);
                    default -> log.warn("Task type '{}' not yet implemented, task {} skipped", taskType, taskId);
                }
            });

            return ToolResult.success(
                    "Task #" + taskId + " created successfully: " + description);

        } catch (IllegalStateException e) {
            return ToolResult.error("Failed to create task: " + e.getMessage());
        }
    }

    // ═══ 任务执行方法 ═══

    private void executeAgentTask(String taskId, String prompt, ToolUseContext context) {
        try {
            log.info("Executing agent task: {}", taskId);
            AgentRequest request = new AgentRequest(
                    "task-" + taskId,          // agentId
                    prompt,                     // prompt
                    null,                       // agentType (default general-purpose)
                    null,                       // model (default)
                    IsolationMode.NONE,         // isolation
                    false                       // runInBackground
            );
            AgentResult result = subAgentExecutor.executeSync(request, context);
            log.info("Agent task {} completed: status={}", taskId,
                    result != null ? result.status() : "null");
        } catch (Exception e) {
            log.error("Agent task {} failed: {}", taskId, e.getMessage(), e);
        }
    }

    private void executeShellTask(String taskId, String prompt, ToolUseContext context) {
        try {
            log.info("Executing shell task: {}", taskId);
            var bashToolOpt = toolRegistry.findByNameOptional("Bash");
            if (bashToolOpt.isEmpty()) {
                log.error("BashTool not found in registry, shell task {} skipped", taskId);
                return;
            }
            var bashTool = bashToolOpt.get();
            ToolInput bashInput = ToolInput.from(Map.of("command", prompt));
            ToolResult result = bashTool.call(bashInput, context);
            log.info("Shell task {} completed: {}", taskId,
                    result != null ? "success" : "null result");
        } catch (Exception e) {
            log.error("Shell task {} failed: {}", taskId, e.getMessage(), e);
        }
    }

    private void executeLocalWorkflowTask(String taskId, String prompt, ToolUseContext context) {
        try {
            log.info("Executing local_workflow task: {}", taskId);
            // local_workflow 本质是脚本驱动的 agent，使用 "workflow" 类型标识
            AgentRequest request = new AgentRequest(
                    "workflow-" + taskId,
                    prompt,
                    "workflow",             // agentType: 标识为工作流代理
                    null,
                    IsolationMode.NONE,
                    false
            );
            AgentResult result = subAgentExecutor.executeSync(request, context);
            log.info("Local workflow task {} completed: status={}", taskId,
                    result != null ? result.status() : "null");
        } catch (Exception e) {
            log.error("Local workflow task {} failed: {}", taskId, e.getMessage(), e);
        }
    }

    private void executeMonitorMcpTask(String taskId, String prompt, ToolUseContext context) {
        try {
            log.info("Executing monitor_mcp task: {}", taskId);
            // 解析监控间隔（从 prompt 中提取，默认 60 秒）
            String effectivePrompt = prompt;
            int intervalSeconds = 60;
            try {
                // 支持格式: "interval=30 <actual_prompt>"
                if (prompt.startsWith("interval=")) {
                    String[] parts = prompt.split("\\s+", 2);
                    intervalSeconds = Integer.parseInt(parts[0].substring("interval=".length()));
                    effectivePrompt = parts.length > 1 ? parts[1] : prompt;
                }
            } catch (NumberFormatException e) {
                log.warn("Invalid interval in monitor_mcp prompt, using default 60s");
            }

            // 使用 agent 模式执行 MCP 监控指令
            AgentRequest request = new AgentRequest(
                    "mcp-monitor-" + taskId,
                    effectivePrompt,
                    "monitor",
                    null,
                    IsolationMode.NONE,
                    true                    // runInBackground: MCP 监控任务后台运行
            );
            AgentResult result = subAgentExecutor.executeSync(request, context);
            log.info("Monitor MCP task {} completed: status={}", taskId,
                    result != null ? result.status() : "null");
        } catch (Exception e) {
            log.error("Monitor MCP task {} failed: {}", taskId, e.getMessage(), e);
        }
    }

    private void executeDreamTask(String taskId, String prompt, ToolUseContext context) {
        try {
            log.info("Executing dream task: {}", taskId);
            // dream 任务: 后台梦境式思考 — 低优先级、非阻塞
            AgentRequest request = new AgentRequest(
                    "dream-" + taskId,
                    prompt,
                    "dream",
                    null,
                    IsolationMode.NONE,     // IsolationMode 无 SNAPSHOT，使用 NONE
                    true                    // runInBackground
            );
            AgentResult result = subAgentExecutor.executeSync(request, context);
            log.info("Dream task {} completed: status={}", taskId,
                    result != null ? result.status() : "null");
        } catch (Exception e) {
            log.error("Dream task {} failed: {}", taskId, e.getMessage(), e);
        }
    }

    @Override
    public boolean isConcurrencySafe(ToolInput input) {
        return true;
    }
}
