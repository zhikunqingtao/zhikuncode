package com.aicodeassistant.tool.task;

import com.aicodeassistant.tool.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public TaskCreateTool(TaskCoordinator taskCoordinator) {
        this.taskCoordinator = taskCoordinator;
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
            // 提交任务到 TaskCoordinator — 执行体为占位实现
            // 实际执行逻辑由 taskType 决定:
            //   - agent: 创建子 QueryEngine + AgentTool 工具集
            //   - shell: ProcessBuilder 执行命令 + 输出监控
            //   - in_process_teammate: InProcessBackend 启动
            TaskState taskState = taskCoordinator.submit(taskId, sessionId, description, () -> {
                // P1 占位: 实际任务执行体将在集成阶段填充
                log.info("Task {} executing: type={}, prompt={}", taskId, taskType,
                        prompt.length() > 100 ? prompt.substring(0, 100) + "..." : prompt);
            });

            return ToolResult.success(
                    "Task #" + taskId + " created successfully: " + description);

        } catch (IllegalStateException e) {
            return ToolResult.error("Failed to create task: " + e.getMessage());
        }
    }

    @Override
    public boolean isConcurrencySafe(ToolInput input) {
        return true;
    }
}
