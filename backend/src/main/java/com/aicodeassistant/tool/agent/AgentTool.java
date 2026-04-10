package com.aicodeassistant.tool.agent;

import com.aicodeassistant.tool.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

/**
 * AgentTool — 子代理系统核心工具。
 * <p>
 * 创建子代理执行独立任务，复用 QueryEngine 核心循环。
 * 子代理继承父代理权限，但工具集受限（禁止 Agent/Team/Task 工具防止递归）。
 * <p>
 * 输入 Schema:
 * <ul>
 *   <li>{@code prompt} (必需) - 子代理任务描述</li>
 *   <li>{@code description} (可选) - 3-5 词任务简述</li>
 *   <li>{@code subagent_type} (可选) - 代理类型: explore/verification/plan/general-purpose/claude-code-guide</li>
 *   <li>{@code model} (可选) - 模型覆盖: sonnet/opus/haiku</li>
 *   <li>{@code run_in_background} (可选) - 后台运行标志</li>
 *   <li>{@code isolation} (可选) - 隔离模式: none/worktree</li>
 * </ul>
 * <p>
 * 并发控制委托给 {@link AgentConcurrencyController}:
 * 全局≤30 / 会话≤10 / 嵌套≤3。
 *
 * @see <a href="SPEC §4.1.1">AgentTool</a>
 * @see <a href="SPEC §4.1.1c">并发代理硬限制</a>
 * @see <a href="SPEC §4.1.1d">子代理编排服务</a>
 */
@Component
public class AgentTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(AgentTool.class);

    /** 单个子代理 5 分钟超时 */
    private static final Duration PER_AGENT_TIMEOUT = Duration.ofMinutes(5);

    private final SubAgentExecutor subAgentExecutor;

    public AgentTool(SubAgentExecutor subAgentExecutor) {
        this.subAgentExecutor = subAgentExecutor;
    }

    @Override
    public String getName() {
        return "Agent";
    }

    @Override
    public String getDescription() {
        return "Launch a sub-agent to work on a specific task independently. " +
                "The sub-agent has its own conversation with the LLM and can use tools. " +
                "Use this when a task can be broken down into independent subtasks.";
    }

    @Override
    public String prompt() {
        return """
                Launch a new agent to handle complex, multi-step tasks autonomously.
                
                The Agent tool launches specialized agents (subprocesses) that autonomously \
                handle complex tasks. Each agent type has specific capabilities and tools \
                available to it.
                
                When NOT to use the Agent tool:
                - If you want to read a specific file path, use the Read tool or Glob tool \
                instead, to find the match more quickly
                - If you are searching for a specific class definition like "class Foo", use \
                the Glob tool instead, to find the match more quickly
                - If you are searching for code within a specific file or set of 2-3 files, \
                use the Read tool instead, to find the match more quickly
                - Other tasks that are not related to the agent descriptions above
                
                Usage notes:
                - Always include a short description (3-5 words) summarizing what the agent will do
                - Launch multiple agents concurrently whenever possible, to maximize performance; \
                to do that, use a single message with multiple tool uses
                - When the agent is done, it will return a single message back to you. The result \
                returned by the agent is not visible to the user. To show the user the result, \
                you should send a text message back to the user with a concise summary of the result.
                - Each Agent invocation starts fresh \u2014 provide a complete task description.
                - The agent's outputs should generally be trusted
                - Clearly tell the agent whether you expect it to write code or just to do \
                research (search, file reads, web fetches, etc.), since it is not aware of \
                the user's intent
                - If the agent description mentions that it should be used proactively, then \
                you should try your best to use it without the user having to ask for it first.
                - If the user specifies that they want you to run agents "in parallel", you MUST \
                send a single message with multiple Agent tool use content blocks.
                - You can optionally set `isolation: "worktree"` to run the agent in a temporary \
                git worktree, giving it an isolated copy of the repository.
                
                ## Writing the prompt
                
                Brief the agent like a smart colleague who just walked into the room \u2014 it hasn't \
                seen this conversation, doesn't know what you've tried, doesn't understand why \
                this task matters.
                - Explain what you're trying to accomplish and why.
                - Describe what you've already learned or ruled out.
                - Give enough context about the surrounding problem that the agent can make \
                judgment calls rather than just following a narrow instruction.
                - If you need a short response, say so ("report in under 200 words").
                - Lookups: hand over the exact command. Investigations: hand over the question.
                
                **Never delegate understanding.** Don't write "based on your findings, fix the \
                bug" or "based on the research, implement it." Write prompts that prove you \
                understood: include file paths, line numbers, what specifically to change.
                """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "prompt", Map.of(
                                "type", "string",
                                "description", "Complete task description for the sub-agent"),
                        "description", Map.of(
                                "type", "string",
                                "description", "Short 3-5 word description of the task"),
                        "subagent_type", Map.of(
                                "type", "string",
                                "enum", List.of("explore", "verification", "plan",
                                        "general-purpose", "claude-code-guide"),
                                "description", "Type of sub-agent to use"),
                        "model", Map.of(
                                "type", "string",
                                "enum", List.of("sonnet", "opus", "haiku"),
                                "description", "Model override for the sub-agent"),
                        "run_in_background", Map.of(
                                "type", "boolean",
                                "description", "Run the agent in the background"),
                        "isolation", Map.of(
                                "type", "string",
                                "enum", List.of("none", "worktree"),
                                "description", "Isolation mode for the sub-agent")
                ),
                "required", List.of("prompt")
        );
    }

    @Override
    public String getGroup() {
        return "agent";
    }

    @Override
    public PermissionRequirement getPermissionRequirement() {
        return PermissionRequirement.NONE;  // 子代理继承父代理权限
    }

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        String prompt = input.getString("prompt");
        String description = input.getString("description", "sub-agent task");
        String agentType = input.getString("subagent_type", null);
        String model = input.getString("model", null);
        boolean runInBackground = input.getBoolean("run_in_background", false);
        String isolationStr = input.getString("isolation", "none");

        // 解析隔离模式
        SubAgentExecutor.IsolationMode isolation = switch (isolationStr.toLowerCase()) {
            case "worktree" -> SubAgentExecutor.IsolationMode.WORKTREE;
            case "remote" -> SubAgentExecutor.IsolationMode.REMOTE;
            default -> SubAgentExecutor.IsolationMode.NONE;
        };

        // 生成代理 ID
        String agentId = "agent-" + UUID.randomUUID().toString().substring(0, 8);

        log.info("AgentTool creating sub-agent: id={}, type={}, bg={}, isolation={}",
                agentId, agentType, runInBackground, isolation);

        // 构建请求
        SubAgentExecutor.AgentRequest request = new SubAgentExecutor.AgentRequest(
                agentId, prompt, agentType, model, isolation, runInBackground);

        try {
            SubAgentExecutor.AgentResult result;

            if (runInBackground) {
                result = subAgentExecutor.executeAsync(request, context);
                // 异步模式: 返回启动信息
                return ToolResult.success(
                        "Agent launched in background.\n" +
                        "Agent ID: " + agentId + "\n" +
                        "Output file: " + result.outputFile() + "\n" +
                        "Description: " + description + "\n" +
                        "Prompt: " + prompt);
            } else {
                result = subAgentExecutor.executeSync(request, context);
                // 同步模式: 返回执行结果
                return ToolResult.success(result.result() != null
                        ? result.result()
                        : "Sub-agent completed without response.");
            }
        } catch (AgentLimitExceededException e) {
            log.warn("Agent limit exceeded: {}", e.getMessage());
            return ToolResult.error("Agent limit exceeded: " + e.getMessage());
        } catch (Exception e) {
            log.error("AgentTool execution failed: {}", agentId, e);
            return ToolResult.error("Agent execution failed: " + e.getMessage());
        }
    }

    @Override
    public boolean isReadOnly(ToolInput input) {
        // Agent 可能执行写操作
        return false;
    }

    @Override
    public boolean isConcurrencySafe(ToolInput input) {
        // 子代理有独立的并发控制
        return true;
    }
}
