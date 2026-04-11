package com.aicodeassistant.coordinator;

import com.aicodeassistant.mcp.McpClientManager;
import com.aicodeassistant.mcp.McpServerConnection;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Coordinator 系统提示构建器。
 * <p>
 * 对标原版 Coordinator 系统提示（约 1400 行），基础版提取核心工作协议。
 *
 * @see <a href="SPEC §4.16.3">Coordinator 系统提示</a>
 */
@Component
public class CoordinatorPromptBuilder {

    private final CoordinatorService coordinatorService;
    private final McpClientManager mcpClientManager;

    public CoordinatorPromptBuilder(CoordinatorService coordinatorService,
                                     McpClientManager mcpClientManager) {
        this.coordinatorService = coordinatorService;
        this.mcpClientManager = mcpClientManager;
    }

    /**
     * 构建 Coordinator 系统提示（基础版）。
     */
    public String buildCoordinatorPrompt(String sessionId) {
        return buildCoordinatorPrompt(sessionId, null);
    }

    /**
     * 构建 Coordinator 系统提示（增强版）。
     * 包含 MCP 客户端列表和 scratchpad 目录信息。
     *
     * @param sessionId      会话 ID
     * @param scratchpadDir  scratchpad 目录（可为 null，自动读取）
     */
    public String buildCoordinatorPrompt(String sessionId, Path scratchpadDir) {
        Map<String, String> workerContext =
                coordinatorService.getWorkerToolsContext(sessionId);
        String workerTools = workerContext.getOrDefault(
                "workerToolsContext", "standard tools");

        // Scratchpad 目录
        Path scratchpad = scratchpadDir != null
                ? scratchpadDir
                : coordinatorService.getScratchpadDir(sessionId);

        // MCP 客户端列表
        String mcpClients = buildMcpClientsSection();

        return COORDINATOR_SYSTEM_PROMPT_TEMPLATE.formatted(
                workerTools, scratchpad.toString(), mcpClients);
    }

    /**
     * 构建 MCP 客户端列表段落。
     */
    private String buildMcpClientsSection() {
        List<McpServerConnection> connected = mcpClientManager.getConnectedServers();
        if (connected.isEmpty()) {
            return "No MCP servers connected.";
        }
        StringBuilder sb = new StringBuilder();
        for (McpServerConnection conn : connected) {
            sb.append("- **").append(conn.getName()).append("**: ")
                    .append(conn.getTools().size()).append(" tools");
            if (!conn.getTools().isEmpty()) {
                sb.append(" (");
                sb.append(conn.getTools().stream()
                        .limit(5)
                        .map(McpServerConnection.McpToolDefinition::name)
                        .reduce((a, b) -> a + ", " + b)
                        .orElse(""));
                if (conn.getTools().size() > 5) sb.append(", ...");
                sb.append(")");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static final String COORDINATOR_SYSTEM_PROMPT_TEMPLATE = """
            # Coordinator Mode

            You are a coordinator. Your role is to break down complex tasks and delegate work
            to specialized worker agents. You do NOT execute tools directly (no file edits,
            no bash commands). You orchestrate.

            ## Available Actions
            - **Agent**: Spawn a new worker agent with a specific task
            - **SendMessage**: Continue an existing worker's task with additional instructions
            - **TaskStop**: Stop a running worker agent

            ## Worker Capabilities
            %s

            ## Scratchpad Directory
            Workers share a scratchpad at: `%s`
            Use this directory for intermediate files, partial results, and cross-worker data exchange.

            ## MCP Servers
            %s

            ## Workflow Protocol
            1. **Analyze** the user's request — identify independent sub-tasks
            2. **Research Phase** — spawn explore agents to gather information (parallel OK)
            3. **Synthesize** — combine research results into an implementation plan
            4. **Implement Phase** — spawn implementation agents (read parallel, write serial)
            5. **Verify Phase** — spawn verification agents to check the work

            ## Critical Rules
            - **Parallelism is your superpower** — spawn multiple agents for independent tasks
            - **Workers cannot see your conversation** — give them ALL needed context in prompts
            - **Never write "based on your findings"** — workers don't know what you know
            - **Don't use workers to check other workers' output** — spawn fresh verification agents
            - **Simple questions don't need workers** — answer directly if no tools needed
            - **Continue vs New**: Use SendMessage to continue if worker has relevant context;
              use new Agent if starting fresh or previous context would pollute

            ## Task Notification Format
            Worker results are returned as <task-notification> XML:
            ```xml
            <task-notification>
            <task-id>{agentId}</task-id>
            <status>completed|failed|killed</status>
            <summary>{human-readable status}</summary>
            <result>{agent's final text response}</result>
            </task-notification>
            ```

            ## Decision Matrix: Continue vs New Agent
            | Scenario | Choice | Reason |
            |----------|--------|--------|
            | Research found files to edit | SendMessage | Worker has file context |
            | Broad research, narrow impl | New Agent | Avoid exploration noise |
            | Fix failure or extend work | SendMessage | Worker has error context |
            | Verify someone's code | New Agent | Fresh perspective needed |
            | First attempt totally wrong | New Agent | Bad context pollutes retry |
            """;
}
