package com.aicodeassistant.coordinator;

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

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Swarm Worker 执行引擎 — 在 Virtual Thread 中运行独立的 Worker Agent。
 * <p>
 * 对标 inProcessRunner.ts (~1400行)。
 * 每个 Worker 复用 {@link QueryEngine#execute} 执行引擎，但使用独立的：
 * <ul>
 *   <li>工具集（经过 allowList/denyList 过滤）</li>
 *   <li>权限模式（降级为 BUBBLE，通过 {@link LeaderPermissionBridge} 冒泡）</li>
 *   <li>上下文（隔离的 {@link QueryLoopState}）</li>
 * </ul>
 * <p>
 * Virtual Thread Executor：每个 Worker 一个虚拟线程，
 * Worker 数量由 {@link SwarmConfig#maxWorkers()} 控制。
 *
 * @see <a href="SPEC §11">Team/Swarm 多Agent协作</a>
 */
@Service
public class SwarmWorkerRunner {

    private static final Logger log = LoggerFactory.getLogger(SwarmWorkerRunner.class);

    /** Worker 最大执行时间（分钟） */
    private static final long WORKER_TIMEOUT_MINUTES = 30;

    /** Worker 消息上限（对标 TEAMMATE_MESSAGES_UI_CAP） */
    private static final int WORKER_MESSAGE_CAP = 50;

    private final QueryEngine queryEngine;
    private final ToolRegistry toolRegistry;
    private final LeaderPermissionBridge permissionBridge;

    /** Virtual Thread Executor — 每个 Worker 一个虚拟线程 */
    private final ExecutorService workerExecutor = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("swarm-worker-", 0).factory());

    public SwarmWorkerRunner(@Lazy QueryEngine queryEngine,
                              @Lazy ToolRegistry toolRegistry,
                              LeaderPermissionBridge permissionBridge) {
        this.queryEngine = queryEngine;
        this.toolRegistry = toolRegistry;
        this.permissionBridge = permissionBridge;
    }

    /**
     * 在 Virtual Thread 中启动一个 Worker Agent。
     *
     * @param workerId      Worker 唯一标识
     * @param taskPrompt    任务提示
     * @param config        Swarm 配置
     * @param parentContext 父查询上下文
     * @param swarmState    Swarm 运行时状态（用于实时更新 Worker 状态）
     * @return CompletableFuture 包含 Worker 执行结果
     */
    public CompletableFuture<WorkerResult> startWorker(
            String workerId,
            String taskPrompt,
            SwarmConfig config,
            ToolUseContext parentContext,
            SwarmState swarmState) {

        return CompletableFuture.supplyAsync(() -> {
            log.info("Worker {} starting: task='{}'", workerId,
                    taskPrompt.length() > 80 ? taskPrompt.substring(0, 80) + "..." : taskPrompt);
            try {
                return executeWorkerLoop(workerId, taskPrompt, config, parentContext, swarmState);
            } catch (Exception e) {
                log.error("Worker {} failed with exception", workerId, e);
                swarmState.markWorkerTerminated(workerId);
                return new WorkerResult(workerId, "Worker failed: " + e.getMessage(), 0, 0L);
            }
        }, workerExecutor)
        .orTimeout(WORKER_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        .exceptionally(ex -> {
            if (ex instanceof TimeoutException || (ex.getCause() instanceof TimeoutException)) {
                log.error("Worker {} timed out after {} minutes, forcing abort", workerId, WORKER_TIMEOUT_MINUTES);
                swarmState.markWorkerTerminated(workerId);
            }
            return new WorkerResult(workerId, "Worker failed: " + ex.getMessage(), 0, 0L);
        });
    }

    /**
     * Worker 执行主循环 — 核心方法。
     * <p>
     * 复用 {@link QueryEngine#execute(QueryConfig, QueryLoopState, QueryMessageHandler)}，
     * 使用隔离的工具集、权限和上下文。
     */
    private WorkerResult executeWorkerLoop(
            String workerId,
            String taskPrompt,
            SwarmConfig config,
            ToolUseContext parentContext,
            SwarmState swarmState) {

        // 1. 构建 worker 工具集（应用 allowList/denyList 过滤）
        List<Tool> allTools = toolRegistry.getEnabledTools();
        List<Tool> filteredTools = allTools.stream()
                .filter(t -> config.workerToolAllowList().isEmpty()
                        || config.workerToolAllowList().contains(t.getName()))
                .filter(t -> !config.workerToolDenyList().contains(t.getName()))
                // Worker 不能使用 Agent 工具（防止嵌套 Swarm）
                .filter(t -> !"Agent".equals(t.getName()))
                .filter(t -> !"TeamCreate".equals(t.getName()))
                .filter(t -> !"TeamDelete".equals(t.getName()))
                .toList();

        List<Map<String, Object>> toolDefs = filteredTools.stream()
                .map(Tool::toToolDefinition)
                .toList();

        // 2. 构建 worker 上下文（独立消息历史 + scratchpad）
        String workerSessionId = "swarm-worker-" + workerId;
        ToolUseContext workerContext = parentContext
                .withNestingDepth(parentContext.nestingDepth() + 1)
                .withCurrentTaskId(workerId)
                .withParentSessionId(parentContext.sessionId())
                .withAgentHierarchy(
                        (parentContext.agentHierarchy() != null ? parentContext.agentHierarchy() : "main")
                                + "/" + workerId)
                .withWorkingDirectory(config.scratchpadDir() != null
                        ? config.scratchpadDir().toString()
                        : parentContext.workingDirectory());

        // 3. 构建 QueryConfig（复用现有引擎签名）
        String model = config.workerModel() != null ? config.workerModel() : "qwen-plus";
        String systemPrompt = buildWorkerSystemPrompt(taskPrompt, config);

        QueryConfig workerConfig = QueryConfig.withDefaults(
                model,
                systemPrompt,
                filteredTools,
                toolDefs,
                QueryConfig.DEFAULT_MAX_TOKENS,
                200_000,
                new ThinkingConfig.Disabled(),
                30,  // maxTurns for worker
                "swarm_worker"
        );

        // 4. 构建隔离的 QueryLoopState（独立消息历史）
        List<Message> initialMessages = new ArrayList<>();
        initialMessages.add(new Message.UserMessage(
                UUID.randomUUID().toString(),
                Instant.now(),
                List.of(new ContentBlock.TextBlock(taskPrompt)),
                null, null));

        QueryLoopState workerState = new QueryLoopState(initialMessages, workerContext);

        // 5. 更新 SwarmState: Worker 正在工作
        swarmState.markWorkerWorking(workerId, taskPrompt);

        // 6. 执行查询循环
        WorkerMessageHandler handler = new WorkerMessageHandler(workerId, swarmState, permissionBridge);
        QueryEngine.QueryResult result = queryEngine.execute(workerConfig, workerState, handler);

        // 7. 标记 Worker 为空闲
        swarmState.markWorkerIdle(workerId);

        // 8. 提取结果文本
        String resultText = extractResultText(result);
        log.info("Worker {} completed: turns={}, tokens={}",
                workerId, result.turnCount(),
                result.totalUsage() != null ? result.totalUsage().totalTokens() : 0);

        return new WorkerResult(
                workerId,
                resultText,
                result.turnCount(),
                result.totalUsage() != null ? result.totalUsage().totalTokens() : 0L
        );
    }

    /**
     * Worker 系统提示构建。
     */
    private String buildWorkerSystemPrompt(String taskPrompt, SwarmConfig config) {
        return """
                You are a worker agent in a Swarm team. Your job is to complete the assigned task efficiently.
                
                ## Constraints
                - You are part of team '%s'
                - Complete your assigned task and return a clear, concise result
                - You do NOT have access to: Agent, TeamCreate, TeamDelete tools
                - If you modify files, list all modified file paths in your final response
                - Focus on your specific task, do not attempt unrelated work
                - If you need information from other workers, check the scratchpad directory
                
                ## Scratchpad
                - Shared directory: %s
                - Use this for inter-worker file exchange if needed
                
                ## Your Task
                %s
                """.formatted(
                config.teamName(),
                config.scratchpadDir() != null ? config.scratchpadDir().toString() : "N/A",
                taskPrompt
        );
    }

    /**
     * 从 QueryResult 提取最终答案文本。
     */
    private String extractResultText(QueryEngine.QueryResult result) {
        if (result == null || result.messages() == null || result.messages().isEmpty()) {
            return "No response from worker.";
        }
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
        return "Worker completed without text response.";
    }

    /**
     * 关闭执行器（Spring 容器销毁时调用）。
     */
    public void shutdown() {
        workerExecutor.shutdown();
        try {
            if (!workerExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                workerExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            workerExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ═══ Worker Result DTO ═══

    /**
     * Worker 执行结果。
     *
     * @param workerId       Worker 标识
     * @param result         执行结果文本
     * @param turnCount      查询循环轮次数
     * @param tokensConsumed Token 消耗量
     */
    public record WorkerResult(
            String workerId,
            String result,
            int turnCount,
            long tokensConsumed
    ) {}

    // ═══ Worker Message Handler ═══

    /**
     * Worker 专用消息处理器 — 实时更新 SwarmState 中的 Worker 状态。
     */
    private static class WorkerMessageHandler implements QueryMessageHandler {

        private final String workerId;
        private final SwarmState swarmState;
        private final LeaderPermissionBridge permissionBridge;

        WorkerMessageHandler(String workerId, SwarmState swarmState,
                              LeaderPermissionBridge permissionBridge) {
            this.workerId = workerId;
            this.swarmState = swarmState;
            this.permissionBridge = permissionBridge;
        }

        @Override
        public void onTextDelta(String text) {
            // Worker 文本增量 — 不推送到前端，仅内部处理
        }

        @Override
        public void onToolUseStart(String toolUseId, String toolName) {
            // 更新 Worker 工具调用计数
            swarmState.updateWorkerToolCall(workerId, toolName);
        }

        @Override
        public void onToolUseComplete(String toolUseId, ContentBlock.ToolUseBlock toolUse) {
            // 工具调用完成 — 状态已在 start 时更新
        }

        @Override
        public void onToolResult(String toolUseId, ContentBlock.ToolResultBlock result) {
            // 工具结果 — 不需要额外处理
        }

        @Override
        public void onAssistantMessage(Message.AssistantMessage message) {
            // 助手消息完成 — 可用于追踪 Worker 进度
        }

        @Override
        public void onUsage(Usage usage) {
            // 更新 Worker Token 消耗
            if (usage != null) {
                swarmState.updateWorkerTokens(workerId, usage.totalTokens());
            }
        }
    }
}
