package com.aicodeassistant.tool.agent;

import com.aicodeassistant.engine.QueryConfig;
import com.aicodeassistant.engine.QueryEngine;
import com.aicodeassistant.engine.QueryLoopState;
import com.aicodeassistant.engine.QueryMessageHandler;
import com.aicodeassistant.llm.ThinkingConfig;
import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.aicodeassistant.model.Usage;
import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.ToolRegistry;
import com.aicodeassistant.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 子代理执行器 — 连接 AgentTool.call() 与 QueryEngine.execute()。
 * <p>
 * 完整生命周期:
 *   acquireSlot → resolveAgent → createContext → execute → collectResult → releaseSlot
 * <p>
 * 对照源码 src/tools/AgentTool/runAgent.ts 的 runAgent() 函数。
 *
 * @see <a href="SPEC §4.1.1d.1">SubAgentExecutor</a>
 */
@Service
public class SubAgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(SubAgentExecutor.class);

    private final AgentConcurrencyController concurrencyController;
    private final QueryEngine queryEngine;
    private final ToolRegistry toolRegistry;
    private final BackgroundAgentTracker backgroundTracker;
    private final WorktreeManager worktreeManager;

    /** 子代理结果最大字符数 */
    static final int MAX_RESULT_SIZE_CHARS = 100_000;

    /** 单个子代理超时 */
    private static final Duration PER_AGENT_TIMEOUT = Duration.ofMinutes(5);

    public SubAgentExecutor(AgentConcurrencyController concurrencyController,
                            QueryEngine queryEngine,
                            @Lazy ToolRegistry toolRegistry,
                            BackgroundAgentTracker backgroundTracker,
                            WorktreeManager worktreeManager) {
        this.concurrencyController = concurrencyController;
        this.queryEngine = queryEngine;
        this.toolRegistry = toolRegistry;
        this.backgroundTracker = backgroundTracker;
        this.worktreeManager = worktreeManager;
    }

    /**
     * 同步执行子代理 — AgentTool.call() 的主入口。
     *
     * @param request       子代理请求（含 prompt, agentType, model, isolation 等）
     * @param parentContext 父查询上下文（用于继承权限、工具集）
     * @return AgentResult 执行结果
     */
    public AgentResult executeSync(AgentRequest request, ToolUseContext parentContext) {
        int nestingDepth = parentContext.nestingDepth() + 1;

        try (var slot = concurrencyController.acquireSlot(
                request.agentId(), nestingDepth, parentContext.sessionId())) {

            // 1. 解析代理定义
            AgentDefinition agentDef = resolveAgentDefinition(request.agentType());

            // 2. 解析模型: 参数 → 代理定义 → 默认
            String model = resolveModel(request.model(), agentDef);

            // 3. 组装工具集（过滤禁用工具）
            List<Tool> tools = assembleToolPool(agentDef, parentContext);

            // 4. 构建系统提示
            String systemPrompt = buildAgentSystemPrompt(request.prompt(), agentDef, parentContext);

            // 5. 创建隔离工作目录
            Path workDir = request.isolation() == IsolationMode.WORKTREE
                    ? worktreeManager.createWorktree(request.agentId())
                    : Path.of(parentContext.workingDirectory());

            // 6. 构建 QueryConfig (适配现有 record 构造)
            List<Map<String, Object>> toolDefs = tools.stream()
                    .map(Tool::toToolDefinition).toList();
            QueryConfig config = QueryConfig.withDefaults(
                    model, systemPrompt, tools, toolDefs,
                    QueryConfig.DEFAULT_MAX_TOKENS, 200000,
                    new ThinkingConfig.Disabled(),
                    agentDef.maxTurns(),  // 默认 30
                    "subagent-" + request.agentId()
            );

            // 7. 初始化循环状态
            ToolUseContext subContext = ToolUseContext.of(workDir.toString(), parentContext.sessionId())
                    .withNestingDepth(nestingDepth);
            QueryLoopState state = new QueryLoopState(
                    new ArrayList<>(List.of(buildUserMessage(request.prompt()))),
                    subContext);

            // 8. 执行查询循环 (带超时)
            SubAgentMessageHandler handler = new SubAgentMessageHandler();

            CompletableFuture<QueryEngine.QueryResult> future = CompletableFuture.supplyAsync(
                    () -> queryEngine.execute(config, state, handler));

            QueryEngine.QueryResult result;
            try {
                result = future.get(PER_AGENT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                log.warn("Sub-agent {} timed out after {}s", request.agentId(), PER_AGENT_TIMEOUT.toSeconds());
                return new AgentResult("completed",
                        "Agent timed out after " + PER_AGENT_TIMEOUT.toSeconds() + " seconds",
                        request.prompt(), null);
            }

            // 9. Worktree 清理
            if (request.isolation() == IsolationMode.WORKTREE) {
                boolean hasChanges = worktreeManager.hasChanges(workDir);
                if (hasChanges) {
                    worktreeManager.mergeBack(workDir);
                }
                worktreeManager.removeWorktree(workDir);
            }

            // 10. 截取结果
            String answer = extractFinalAnswer(result);
            if (answer.length() > MAX_RESULT_SIZE_CHARS) {
                answer = answer.substring(0, MAX_RESULT_SIZE_CHARS) + "\n...[truncated]";
            }

            return new AgentResult("completed", answer, request.prompt(), null);
        } catch (AgentLimitExceededException e) {
            throw e;  // 让调用方处理
        } catch (Exception e) {
            log.error("Sub-agent execution failed: {}", request.agentId(), e);
            return new AgentResult("completed",
                    "Agent execution failed: " + e.getMessage(),
                    request.prompt(), null);
        }
    }

    /**
     * 异步执行子代理 — run_in_background=true 时使用。
     */
    public AgentResult executeAsync(AgentRequest request, ToolUseContext parentContext) {
        String outputFile = "/tmp/agent-" + request.agentId() + "-output.txt";

        Thread.startVirtualThread(() -> {
            try {
                AgentResult result = executeSync(request, parentContext);
                Files.writeString(Path.of(outputFile), result.result() != null ? result.result() : "");
                backgroundTracker.markCompleted(request.agentId(), result);
            } catch (Exception e) {
                backgroundTracker.markFailed(request.agentId(), e.getMessage());
            }
        });

        backgroundTracker.register(request.agentId(), parentContext.sessionId(),
                request.prompt(), outputFile);

        return new AgentResult("async_launched", null, request.prompt(), outputFile);
    }

    // ═══ 代理定义解析 ═══

    private AgentDefinition resolveAgentDefinition(String agentType) {
        return switch (agentType != null ? agentType.toLowerCase() : "general-purpose") {
            case "explore" -> AgentDefinition.EXPLORE;
            case "verification" -> AgentDefinition.VERIFICATION;
            case "plan" -> AgentDefinition.PLAN;
            case "claude-code-guide" -> AgentDefinition.GUIDE;
            default -> AgentDefinition.GENERAL_PURPOSE;
        };
    }

    private String resolveModel(String requestModel, AgentDefinition agentDef) {
        if (requestModel != null && !requestModel.isBlank()) return requestModel;
        if (agentDef.defaultModel() != null) return agentDef.defaultModel();
        return "sonnet";  // 默认子代理模型
    }

    // ═══ 工具池组装 ═══

    private List<Tool> assembleToolPool(AgentDefinition agentDef, ToolUseContext ctx) {
        // 所有子代理禁用的工具
        Set<String> denied = Set.of("Agent", "TeamCreate", "TeamDelete", "TaskCreate");
        Set<String> agentDenied = agentDef.deniedTools() != null
                ? agentDef.deniedTools() : Set.of();

        return toolRegistry.getEnabledTools(ctx.sessionId()).stream()
                .filter(t -> !denied.contains(t.getName()))
                .filter(t -> !agentDenied.contains(t.getName()))
                .filter(t -> agentDef.allowedTools() == null
                        || agentDef.allowedTools().contains("*")
                        || agentDef.allowedTools().contains(t.getName()))
                .toList();
    }

    // ═══ 系统提示构建 ═══

    private String buildAgentSystemPrompt(String taskPrompt, AgentDefinition agentDef,
                                          ToolUseContext context) {
        return """
                %s
                
                Your task:
                %s
                
                Context:
                - Working directory: %s
                - You have access to file read/write tools, search tools, and bash.
                - You do NOT have access to: AgentTool, TaskCreateTool, TeamTools.
                - Complete the task and return a clear, concise result.
                - If you modify files, list all modified file paths in your final response.
                - Do not attempt tasks outside the scope described above.
                """.formatted(agentDef.systemPromptTemplate(), taskPrompt, context.workingDirectory());
    }

    // ═══ 结果提取 ═══

    private String extractFinalAnswer(QueryEngine.QueryResult result) {
        if (result == null || result.messages() == null || result.messages().isEmpty()) {
            return "No response from sub-agent.";
        }
        // 从最后一条 assistant 消息提取文本
        for (int i = result.messages().size() - 1; i >= 0; i--) {
            Message msg = result.messages().get(i);
            if (msg instanceof Message.AssistantMessage assistant && assistant.content() != null) {
                StringBuilder sb = new StringBuilder();
                for (ContentBlock block : assistant.content()) {
                    if (block instanceof ContentBlock.TextBlock text) {
                        sb.append(text.text());
                    }
                }
                if (!sb.isEmpty()) return sb.toString();
            }
        }
        return "Sub-agent completed without text response.";
    }

    // ═══ 消息构建 ═══

    private Message.UserMessage buildUserMessage(String prompt) {
        return new Message.UserMessage(
                UUID.randomUUID().toString(), Instant.now(),
                List.of(new ContentBlock.TextBlock(prompt)),
                null, null);
    }

    // ═══ DTO Records ═══

    /** 子代理请求 */
    public record AgentRequest(
            String agentId, String prompt, String agentType,
            String model, IsolationMode isolation, boolean runInBackground
    ) {}

    /** 子代理执行结果 */
    public record AgentResult(
            String status,     // "completed" | "async_launched"
            String result,
            String prompt,
            String outputFile  // 异步模式下的输出文件路径
    ) {}

    /** 隔离模式 */
    public enum IsolationMode { NONE, WORKTREE, REMOTE }

    /**
     * 内置代理定义 — 对照 §4.1.1a 五种内置代理规范。
     */
    public record AgentDefinition(
            String name, int maxTurns, String defaultModel,
            Set<String> allowedTools, Set<String> deniedTools,
            boolean omitClaudeMd, String systemPromptTemplate
    ) {
        static final AgentDefinition EXPLORE = new AgentDefinition(
                "Explore", 30, "haiku", null,
                Set.of("Agent", "ExitPlanMode", "FileEdit", "FileWrite", "NotebookEdit"),
                true, "You are a file search specialist. You operate in read-only mode.");
        static final AgentDefinition VERIFICATION = new AgentDefinition(
                "Verification", 30, null, null,
                Set.of("Agent", "ExitPlanMode", "FileEdit", "FileWrite", "NotebookEdit"),
                false, "You are a verification specialist. Your goal is to try to break the implementation.");
        static final AgentDefinition PLAN = new AgentDefinition(
                "Plan", 30, null, null,
                Set.of("Agent", "ExitPlanMode", "FileEdit", "FileWrite", "NotebookEdit"),
                true, "You are a software architect and planning specialist.");
        static final AgentDefinition GENERAL_PURPOSE = new AgentDefinition(
                "GeneralPurpose", 30, null, Set.of("*"), null,
                false, "You are an agent for the AI assistant. Complete the task without over-engineering.");
        static final AgentDefinition GUIDE = new AgentDefinition(
                "ClaudeCodeGuide", 30, "haiku",
                Set.of("Glob", "Grep", "FileRead", "WebFetch", "WebSearch"), null,
                false, "You are a Claude guide agent, expert in Claude Code CLI, Agent SDK, and Claude API.");
    }

    // ═══ 子代理消息处理器（静默收集） ═══

    /**
     * 子代理消息处理器 — 静默收集结果，不推送到前端。
     */
    private static class SubAgentMessageHandler implements QueryMessageHandler {
        private final List<String> textChunks = new ArrayList<>();

        @Override
        public void onTextDelta(String text) {
            textChunks.add(text);
        }

        @Override
        public void onToolUseStart(String toolUseId, String toolName) {
            // 静默
        }

        @Override
        public void onToolUseComplete(String toolUseId, ContentBlock.ToolUseBlock toolUse) {
            // 静默
        }

        @Override
        public void onToolResult(String toolUseId, ContentBlock.ToolResultBlock result) {
            // 静默
        }

        @Override
        public void onAssistantMessage(Message.AssistantMessage message) {
            // 静默
        }

        @Override
        public void onError(Throwable error) {
            log.warn("Sub-agent error: {}", error.getMessage());
        }

        String getCollectedText() {
            return String.join("", textChunks);
        }
    }
}
