package com.aicodeassistant.tool.agent;

import com.aicodeassistant.config.AgentTimeoutConfig;
import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.coordinator.CoordinatorService;
import com.aicodeassistant.coordinator.TaskNotificationFormatter;
import com.aicodeassistant.coordinator.TeamManager;
import com.aicodeassistant.engine.AbortContext;
import com.aicodeassistant.engine.AbortReason;
import com.aicodeassistant.engine.QueryConfig;
import com.aicodeassistant.engine.QueryEngine;
import com.aicodeassistant.engine.QueryLoopState;
import com.aicodeassistant.engine.QueryMessageHandler;
import com.aicodeassistant.llm.LlmProviderRegistry;
import com.aicodeassistant.model.PermissionMode;
import com.aicodeassistant.permission.PermissionModeManager;
import com.aicodeassistant.llm.ThinkingConfig;
import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.aicodeassistant.model.Usage;
import com.aicodeassistant.service.FileStateCache;
import com.aicodeassistant.session.SessionManager;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * 子代理执行器 — 连接 AgentTool.call() 与 QueryEngine.execute()。
 * <p>
 * 完整生命周期:
 *   acquireSlot → resolveAgent → createContext → execute → collectResult → releaseSlot
 * <p>
 *
 */
@Service
public class SubAgentExecutor {

    private static final Logger log = LoggerFactory.getLogger(SubAgentExecutor.class);

    private final AgentConcurrencyController concurrencyController;
    private final QueryEngine queryEngine;
    private final ToolRegistry toolRegistry;
    private final BackgroundAgentTracker backgroundTracker;
    private final WorktreeManager worktreeManager;
    private final TaskNotificationFormatter taskNotificationFormatter;
    private final FeatureFlagService featureFlagService;
    private final CoordinatorService coordinatorService;
    private final SessionManager sessionManager;
    private final TeamManager teamManager;
    private final LlmProviderRegistry providerRegistry;  // ★ 新增: 模型别名解析 ★
    private final PermissionModeManager permissionModeManager;  // ★ 新增: Worker 权限冒泡 ★
    private final AgentTimeoutConfig timeoutConfig;  // ★ 修复2: 超时配置 Bean ★

    /** 子代理结果最大字符数 */
    static final int MAX_RESULT_SIZE_CHARS = 100_000;

    /** 单个子代理超时 */
    private static final Duration PER_AGENT_TIMEOUT = Duration.ofMinutes(5);

    /** 专用虚拟线程 Executor — 避免 ForkJoinPool.commonPool() 线程饥饿 */
    private static final ExecutorService AGENT_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    public SubAgentExecutor(AgentConcurrencyController concurrencyController,
                            QueryEngine queryEngine,
                            @Lazy ToolRegistry toolRegistry,
                            BackgroundAgentTracker backgroundTracker,
                            WorktreeManager worktreeManager,
                            TaskNotificationFormatter taskNotificationFormatter,
                            FeatureFlagService featureFlagService,
                            CoordinatorService coordinatorService,
                            SessionManager sessionManager,
                            TeamManager teamManager,
                            LlmProviderRegistry providerRegistry,
                            PermissionModeManager permissionModeManager,
                            AgentTimeoutConfig timeoutConfig) {
        this.concurrencyController = concurrencyController;
        this.queryEngine = queryEngine;
        this.toolRegistry = toolRegistry;
        this.backgroundTracker = backgroundTracker;
        this.worktreeManager = worktreeManager;
        this.taskNotificationFormatter = taskNotificationFormatter;
        this.featureFlagService = featureFlagService;
        this.coordinatorService = coordinatorService;
        this.sessionManager = sessionManager;
        this.teamManager = teamManager;
        this.providerRegistry = providerRegistry;
        this.permissionModeManager = permissionModeManager;
        this.timeoutConfig = timeoutConfig;
    }

    /**
     * 同步执行子代理 — AgentTool.call() 的主入口。
     *
     * @param request       子代理请求（含 prompt, agentType, model, isolation 等）
     * @param parentContext 父查询上下文（用于继承权限、工具集）
     * @return AgentResult 执行结果
     */
    public AgentResult executeSync(AgentRequest request, ToolUseContext parentContext) {
        // ★ Team 路由: 如果指定了 teamName，分发到 TeamManager
        if (request.teamName() != null && !request.teamName().isBlank()) {
            log.info("Routing agent request to team: {}", request.teamName());
            List<TeamManager.TaskSpec> tasks = List.of(
                    new TeamManager.TaskSpec(request.prompt(), request.agentType(), request.model()));
            List<AgentResult> results = teamManager.dispatchTasks(
                    request.teamName(), tasks, parentContext);
            return results.isEmpty()
                    ? new AgentResult("completed", "No results from team dispatch", request.prompt(), null)
                    : results.get(0);
        }

        // ★ Fork 路径: 复制父会话状态，独立 QueryEngine 执行
        if (request.fork()) {
            return executeFork(request, parentContext);
        }

        int nestingDepth = parentContext.nestingDepth() + 1;
        Instant startTime = Instant.now();

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
            // ★ FileStateCache clone: 子代理继承父代理文件状态
            FileStateCache parentCache = sessionManager.getFileStateCache(parentContext.sessionId());
            FileStateCache childCache = parentCache.cloneCache();
            String childSessionId = "subagent-" + request.agentId();

            // ★ 新增: 为子代理会话设置权限模式（Worker 权限冒泡）★
            PermissionMode workerMode = resolveWorkerPermissionMode(
                    request.agentType(), parentContext);
            permissionModeManager.setMode(childSessionId, workerMode);
            log.debug("Worker permission mode: agentId={}, agentType={}, mode={}",
                    request.agentId(), request.agentType(), workerMode);

            // 临时注册子代理的 FileStateCache
            sessionManager.getFileStateCache(childSessionId);
            // 用 clone 的缓存替换空缓存
            sessionManager.getFileStateCache(childSessionId).merge(childCache);

            ToolUseContext subContext = ToolUseContext.of(workDir.toString(), childSessionId)
                    .withNestingDepth(nestingDepth)
                    .withParentSessionId(parentContext.sessionId())
                    .withAgentHierarchy(buildAgentHierarchy(parentContext))
                    .withPermissionNotifier(parentContext.permissionNotifier());
            QueryLoopState state = new QueryLoopState(
                    new ArrayList<>(List.of(buildUserMessage(request.prompt()))),
                    subContext);

            // 8. 执行查询循环 (带超时 + 统一资源清理)
            SubAgentMessageHandler handler = new SubAgentMessageHandler();
            Duration timeout = resolveAgentTimeout(request);
            log.info("Sub-agent {} starting with timeout {}s (type={})",
                    request.agentId(), timeout.toSeconds(), request.agentType());

            CompletableFuture<QueryEngine.QueryResult> future = CompletableFuture.supplyAsync(
                    () -> queryEngine.execute(config, state, handler),
                    AGENT_EXECUTOR);

            QueryEngine.QueryResult result;
            try {
                // ★ 动态计算实际超时（基础超时 + 权限等待时间）
                long effectiveTimeoutMs = timeout.toMillis() + parentContext.permissionWaitMs();
                result = future.get(effectiveTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                // ★ 超时处理：优雅终止 + graceful shutdown 窗口
                log.warn("Sub-agent {} timed out after {}s, attempting graceful shutdown",
                        request.agentId(), timeout.toSeconds());
                AbortContext abortCtx = queryEngine.getAbortContext(childSessionId);
                if (abortCtx != null) {
                    abortCtx.abort(AbortReason.TIMEOUT);
                }

                // 给 QueryEngine 一个优雅关闭窗口
                try {
                    result = future.get(
                            timeoutConfig.getGracefulShutdownSeconds() * 1000L, TimeUnit.MILLISECONDS);
                    log.info("Sub-agent {} completed gracefully after timeout signal", request.agentId());
                } catch (TimeoutException | ExecutionException | java.util.concurrent.CancellationException e2) {
                    future.cancel(true);
                    log.error("Sub-agent {} did not respond to graceful shutdown, forcefully cancelled",
                            request.agentId());
                    return new AgentResult("completed",
                            "Agent timed out after " + timeout.toSeconds() + " seconds "
                            + "and did not respond to graceful shutdown",
                            request.prompt(), null);
                }
            } catch (ExecutionException e) {
                log.error("Sub-agent {} execution failed with exception", request.agentId(), e.getCause());
                return new AgentResult("completed",
                        "Agent execution failed: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()),
                        request.prompt(), null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new AgentResult("completed", "Agent interrupted", request.prompt(), null);
            } finally {
                // ★ 修复6: 统一资源清理 — 所有退出路径都经过这里
                // Worktree 清理
                if (request.isolation() == IsolationMode.WORKTREE && workDir != null) {
                    try {
                        if (worktreeManager.hasChanges(workDir)) {
                            worktreeManager.mergeBack(workDir);
                        }
                        worktreeManager.removeWorktree(workDir);
                    } catch (Exception cleanupEx) {
                        log.warn("Worktree cleanup failed for agent {}: {}",
                                request.agentId(), cleanupEx.getMessage());
                    }
                }

                // FileStateCache 清理与合并
                try {
                    FileStateCache childFinalCache = sessionManager.getFileStateCache(childSessionId);
                    if (childFinalCache != null) {
                        parentCache.merge(childFinalCache);
                    }
                    sessionManager.removeFileStateCache(childSessionId);
                } catch (Exception cleanupEx) {
                    log.warn("FileStateCache cleanup failed for agent {}: {}",
                            request.agentId(), cleanupEx.getMessage());
                }
            }

            // 9. 截取结果
            String answer = extractFinalAnswer(result);
            if (answer.length() > MAX_RESULT_SIZE_CHARS) {
                answer = answer.substring(0, MAX_RESULT_SIZE_CHARS) + "\n...[truncated]";
            }

            // Coordinator 模式下格式化为 task-notification
            if (taskNotificationFormatter != null
                    && coordinatorService.isCoordinatorMode()) {
                long durationMs = Duration.between(startTime, Instant.now()).toMillis();
                answer = taskNotificationFormatter.formatNotification(
                        request.agentId(),
                        new AgentResult("completed", answer, request.prompt(), null),
                        durationMs);
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
        String outputFile = Path.of(System.getProperty("java.io.tmpdir"), "agent-" + request.agentId() + "-output.txt").toString();

        Thread.ofVirtual().name("zhiku-agent-" + request.agentId()).start(() -> {
            try {
                AgentResult result = executeSync(request, parentContext);
                Files.writeString(Path.of(outputFile), result.result() != null ? result.result() : "");
                backgroundTracker.markCompleted(request.agentId(), result);
            } catch (Throwable t) {
                log.error("Background agent {} terminated with {}: {}",
                        request.agentId(), t.getClass().getSimpleName(), t.getMessage(), t);
                backgroundTracker.markFailed(request.agentId(),
                        t.getClass().getSimpleName() + ": " + t.getMessage());
            } finally {
                cleanupAgentResources(request.agentId(), request);
            }
        });

        backgroundTracker.register(request.agentId(), parentContext.sessionId(),
                request.prompt(), outputFile);

        return new AgentResult("async_launched", null, request.prompt(), outputFile);
    }

    // ═══ 代理资源清理 ═══

    /**
     * 清理代理相关资源（无论成功/失败/异常）。
     * 用于异步模式虚拟线程的 finally 块。
     */
    private void cleanupAgentResources(String agentId, AgentRequest request) {
        try {
            // 1. 清理 AbortContext
            String childSessionId = "subagent-" + agentId;
            if (queryEngine != null) {
                queryEngine.removeAbortContext(childSessionId);
            }

            // 2. 清理 FileStateCache（如果超时/异常路径跳过了）
            sessionManager.removeFileStateCache(childSessionId);

            // 3. 输出文件生命周期由 BackgroundAgentTracker.cleanup() 定时任务统一管理，
            //    不在此处删除，避免与 QueryEngine.formatAgentResults() 读取产生竞态。
        } catch (Exception cleanupEx) {
            log.warn("Resource cleanup failed for agent {}: {}",
                    agentId, cleanupEx.getMessage());
        }
    }

    // ═══ 超时计算 ═══

    /**
     * 根据代理类型和任务上下文计算超时时间。
     * <p>
     * 策略：
     * <ul>
     *   <li>编码类代理（coding, frontend-dev, backend-dev）：2x 基础超时</li>
     *   <li>验证类代理（verify, qa, verification）：3x 基础超时（可能需要编译+测试）</li>
     *   <li>其他代理：1x 基础超时</li>
     * </ul>
     * 所有结果受 {@code agent.timeout.max-seconds} 上限约束。
     */
    private Duration resolveAgentTimeout(AgentRequest request) {
        int baseSeconds = timeoutConfig.getDefaultSeconds();
        int maxSeconds = timeoutConfig.getMaxSeconds();

        String agentType = request.agentType() != null ? request.agentType().toLowerCase() : "general-purpose";
        int calculated = switch (agentType) {
            case "coding", "frontend-dev", "backend-dev" -> baseSeconds * 2;
            case "verify", "qa", "verification" -> baseSeconds * 3;
            case "explore", "researcher" -> baseSeconds;
            default -> baseSeconds;
        };

        return Duration.ofSeconds(Math.min(calculated, maxSeconds));
    }

    // ═══ Worker 权限模式解析 ═══

    /**
     * 解析 Worker 代理的权限模式。
     * <p>
     * 所有子代理均使用 BUBBLE 模式，因为子代理没有自己的 WebSocket 连接，
     * 权限请求必须通过父会话的 WebSocket 连接转发给前端。
     *
     * @param agentType     代理类型
     * @param parentContext 父代理上下文
     * @return 子代理应使用的权限模式（始终为 BUBBLE）
     */
    private PermissionMode resolveWorkerPermissionMode(
            String agentType, ToolUseContext parentContext) {
        // 所有子代理均使用 BUBBLE 模式 — 子代理无自己的 WebSocket principal，
        // 权限请求必须冒泡到父会话才能推送到前端。
        log.debug("resolveWorkerPermissionMode: agentType='{}' → BUBBLE (all subagents)", agentType);
        return PermissionMode.BUBBLE;
    }

    // ═══ 代理层级构建 ═══

    private String buildAgentHierarchy(ToolUseContext parentContext) {
        String parentPath = parentContext.agentHierarchy() != null
                ? parentContext.agentHierarchy()
                : "main";
        return parentPath + " > subagent-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ═══ 代理定义解析 ═══

    private AgentDefinition resolveAgentDefinition(String agentType) {
        return switch (agentType != null ? agentType.toLowerCase() : "general-purpose") {
            case "explore" -> AgentDefinition.EXPLORE;
            case "verification" -> AgentDefinition.VERIFICATION;
            case "plan" -> AgentDefinition.PLAN;
            case "guide" -> AgentDefinition.GUIDE;
            default -> AgentDefinition.GENERAL_PURPOSE;
        };
    }

    private String resolveModel(String requestModel, AgentDefinition agentDef) {
        String rawModel;
        if (requestModel != null && !requestModel.isBlank()) {
            rawModel = requestModel;
        } else if (agentDef.defaultModel() != null) {
            rawModel = agentDef.defaultModel();
        } else {
            rawModel = "sonnet";
        }
        // ★ 通过别名解析机制映射到实际模型 ★
        String resolved = providerRegistry.resolveModelAlias(rawModel);
        log.debug("resolveModel: raw='{}' → resolved='{}'", rawModel, resolved);
        return resolved;
    }

    // ═══ 工具池组装 ═══

    private List<Tool> assembleToolPool(AgentDefinition agentDef, ToolUseContext ctx) {
        // 所有子代理禁用的工具
        Set<String> denied = Set.of("Agent", "TeamCreate", "TeamDelete", "TaskCreate", "VerifyPlanExecution");
        Set<String> agentDenied = agentDef.deniedTools() != null
                ? agentDef.deniedTools() : Set.of();

        // 使用排序版本：内建工具在前，MCP工具在后，提升 prompt cache 命中率
        return toolRegistry.getEnabledToolsSorted().stream()
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
                
                你的任务：
                %s
                
                上下文：
                - 工作目录：%s
                - 你可以使用文件读写工具、搜索工具和 bash。
                - 你无法使用：AgentTool、TaskCreateTool、TeamTools。
                - 完成任务并返回清晰、简洁的结果。
                - 如果你修改了文件，在最终回复中列出所有修改的文件路径。
                - 不要尝试超出上述范围的任务。
                """.formatted(agentDef.systemPromptTemplate(), taskPrompt, context.workingDirectory());
    }

    // ═══ 结果提取 ═══

    private String extractFinalAnswer(QueryEngine.QueryResult result) {
        if (result == null || result.messages() == null || result.messages().isEmpty()) {
            return "子代理未返回响应。";
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
        return "子代理已完成，但未返回文本响应。";
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
            String model, IsolationMode isolation, boolean runInBackground,
            String teamName, boolean fork
    ) {
        /** 向后兼容构造（无 teamName/fork） */
        public AgentRequest(String agentId, String prompt, String agentType,
                            String model, IsolationMode isolation, boolean runInBackground) {
            this(agentId, prompt, agentType, model, isolation, runInBackground, null, false);
        }
        /** 向后兼容构造（无 fork） */
        public AgentRequest(String agentId, String prompt, String agentType,
                            String model, IsolationMode isolation, boolean runInBackground,
                            String teamName) {
            this(agentId, prompt, agentType, model, isolation, runInBackground, teamName, false);
        }
    }

    /** 子代理执行结果 */
    public record AgentResult(
            String status,     // "completed" | "async_launched"
            String result,
            String prompt,
            String outputFile  // 异步模式下的输出文件路径
    ) {}

    /** 隔离模式 */
    public enum IsolationMode { NONE, WORKTREE, REMOTE }

    // ═══ Fork 执行路径 ═══

    /**
     * Fork 模式执行 — 复制父会话对话状态 + 独立 QueryEngine 执行。
     * <p>
     * Fork 与普通子代理的关键区别:
     * 1. 继承父会话的完整消息历史（启用 cache_control 重用 KV cache）
     * 2. 在父消息末尾追加 fork 任务提示
     * 3. 共享同一工作目录（非隔离）
     */
    private AgentResult executeFork(AgentRequest request, ToolUseContext parentContext) {
        log.info("Fork mode: agent={}, copying parent session state", request.agentId());
        Instant startTime = Instant.now();

        try (var slot = concurrencyController.acquireSlot(
                request.agentId(), parentContext.nestingDepth() + 1, parentContext.sessionId())) {

            // 1. 复制父会话消息历史
            FileStateCache parentCache = sessionManager.getFileStateCache(parentContext.sessionId());
            // 从 SessionManager 获取父会话消息（如果可用）
            List<Message> parentMessages = getParentMessages(parentContext.sessionId());

            // 2. 在父消息末尾追加 Fork 任务提示
            List<Message> forkMessages = new ArrayList<>(parentMessages);
            forkMessages.add(buildUserMessage(
                    "[FORK] 你现在作为一个 fork 代理运行。独立完成以下任务：\n\n"
                            + request.prompt()
                            + "\n\n重要：你可以访问上方的完整对话历史作为上下文。"
                            + "通过复用共享上下文来优化 token 用量。"));

            // 3. 解析代理定义和工具集
            AgentDefinition agentDef = resolveAgentDefinition(request.agentType());
            String model = resolveModel(request.model(), agentDef);
            List<Tool> tools = assembleToolPool(agentDef, parentContext);

            // 4. 构建 QueryConfig — 启用 cache_control 提示
            List<Map<String, Object>> toolDefs = tools.stream()
                    .map(Tool::toToolDefinition).toList();

            // Fork 使用父会话的系统提示 + cache_control breakpoint
            String systemPrompt = buildForkSystemPrompt(request.prompt(), agentDef, parentContext);

            QueryConfig config = QueryConfig.withDefaults(
                    model, systemPrompt, tools, toolDefs,
                    QueryConfig.DEFAULT_MAX_TOKENS, 200000,
                    new ThinkingConfig.Disabled(),
                    agentDef.maxTurns(),
                    "fork-" + request.agentId()
            );

            // 5. 初始化循环状态 — 使用复制的消息
            FileStateCache childCache = parentCache.cloneCache();
            String childSessionId = "fork-" + request.agentId();
            sessionManager.getFileStateCache(childSessionId).merge(childCache);

            // ★ 新增: 为 Fork 子代理会话设置权限模式（Worker 权限冒泡）★
            PermissionMode forkMode = resolveWorkerPermissionMode(
                    request.agentType(), parentContext);
            permissionModeManager.setMode(childSessionId, forkMode);
            log.debug("Fork worker permission mode: agentId={}, agentType={}, mode={}",
                    request.agentId(), request.agentType(), forkMode);

            ToolUseContext forkContext = ToolUseContext.of(
                    parentContext.workingDirectory(), childSessionId)
                    .withNestingDepth(parentContext.nestingDepth() + 1)
                    .withParentSessionId(parentContext.sessionId())
                    .withAgentHierarchy(buildAgentHierarchy(parentContext))
                    .withPermissionNotifier(parentContext.permissionNotifier());

            QueryLoopState state = new QueryLoopState(forkMessages, forkContext);

            // 6. 执行查询循环
            SubAgentMessageHandler handler = new SubAgentMessageHandler();
            CompletableFuture<QueryEngine.QueryResult> future = CompletableFuture.supplyAsync(
                    () -> queryEngine.execute(config, state, handler),
                    AGENT_EXECUTOR);

            QueryEngine.QueryResult result;
            try {
                result = future.get(PER_AGENT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                log.warn("Fork agent {} timed out", request.agentId());
                return new AgentResult("completed",
                        "Fork agent timed out after " + PER_AGENT_TIMEOUT.toSeconds() + " seconds",
                        request.prompt(), null);
            }

            // 7. 合并文件状态
            FileStateCache childFinalCache = sessionManager.getFileStateCache(childSessionId);
            parentCache.merge(childFinalCache);
            sessionManager.removeFileStateCache(childSessionId);

            // 8. 提取结果
            String answer = extractFinalAnswer(result);
            if (answer.length() > MAX_RESULT_SIZE_CHARS) {
                answer = answer.substring(0, MAX_RESULT_SIZE_CHARS) + "\n...[truncated]";
            }

            long durationMs = Duration.between(startTime, Instant.now()).toMillis();
            log.info("Fork agent {} completed in {}ms", request.agentId(), durationMs);

            return new AgentResult("completed", answer, request.prompt(), null);

        } catch (AgentLimitExceededException e) {
            throw e;
        } catch (Exception e) {
            log.error("Fork agent execution failed: {}", request.agentId(), e);
            return new AgentResult("completed",
                    "Fork agent execution failed: " + e.getMessage(),
                    request.prompt(), null);
        }
    }

    /**
     * 获取父会话消息历史 — 从 SessionManager 查询。
     */
    private List<Message> getParentMessages(String sessionId) {
        try {
            var sessionOpt = sessionManager.loadSession(sessionId);
            if (sessionOpt.isPresent() && sessionOpt.get().messages() != null) {
                return new ArrayList<>(sessionOpt.get().messages());
            }
        } catch (Exception e) {
            log.warn("Could not retrieve parent session messages for fork: {}", e.getMessage());
        }
        return new ArrayList<>();
    }

    /**
     * Fork 专用系统提示 — 包含 cache_control 指导。
     */
    private String buildForkSystemPrompt(String taskPrompt, AgentDefinition agentDef,
                                          ToolUseContext context) {
        return """
                %s
                
                [FORK 模式]
                你是一个 fork 实例，可以访问父会话的完整对话历史。
                父会话提供上下文——专注于完成下方的新任务。
                
                你的任务：
                %s
                
                上下文：
                - 工作目录：%s
                - 你继承了父会话的文件状态和对话历史。
                - LLM 提供者应从公共消息前缀中复用共享上下文。
                - 完成任务并返回清晰、简洁的结果。
                - 如果你修改了文件，在最终回复中列出所有修改的文件路径。
                """.formatted(agentDef.systemPromptTemplate(), taskPrompt, context.workingDirectory());
    }

    // ═══ Agent 系统提示模板 ═══

    static final String EXPLORE_AGENT_PROMPT = """
            你是一个搜索和探索专家。你在严格的只读模式下运行。
            
            ## 约束条件
            - 你不能编辑、创建或删除任何文件
            - 你不能执行修改状态的命令
            - 你只能使用：FileRead、GlobTool、GrepTool、list_dir、search_codebase、\
            search_symbol 以及其他只读工具
            - 如果被要求进行修改，拒绝并说明你是只读模式
            
            ## 搜索策略
            收到搜索任务时，按以下优先级顺序使用：
            1. **search_codebase** —— 用于语义/概念搜索（"认证是如何工作的？"）
            2. **search_symbol** —— 用于查找特定的类/方法/变量定义
            3. **GrepTool** —— 用于精确文本模式匹配（错误信息、配置键）
            4. **GlobTool** —— 用于按文件名/扩展名模式查找文件
            5. **FileRead** —— 用于读取已经确定的特定文件
            
            ## 效率规则
            - 先广后窄。当搜索即可时，不要读取整个文件。
            - 使用并行工具调用：如果需要搜索 3 个模式，同时执行。
            - 信息足够时就停止——已找到答案就不要穷举搜索。
            - 如果搜索结果太多，添加更具体的词，而不是逐一读取每个结果。
            
            ## 输出格式
            清晰地结构化你的发现：
            - 列出相关文件路径和行号
            - 引用关键代码片段（保持简短）
            - 总结组件之间的关系
            - 记录观察到的潜在问题或关注点
            - 如果找不到某些内容，明确说明而不是猜测
            """;

    // ═══ Verification Agent 系统提示 ═══

    static final String VERIFICATION_AGENT_PROMPT = """
            你是一个验证专家。你的工作不是确认实现能工作——而是尝试破坏它。
                
            你有两个已记录的失败模式。第一，验证规避：面对检查时，你会找到不执行的理由\
            ——你阅读代码，描述你会测试什么，写下 "PASS"，然后继续。第二，被前 80% 迷惑：\
            你看到精美的 UI 或通过的测试套件，就倾向于通过它，没注意到一半的按钮没有\
            功能、状态在刷新后消失、或后端在错误输入时崩溃。前 80% 是容易的部分。\
            你的全部价值在于发现最后的 20%。调用者可能会通过重新运行你的命令来抽查\
            ——如果一个 PASS 步骤没有命令输出，或输出与重新执行不匹配，你的报告将被拒绝。
                
            === 关键：禁止修改项目 ===
            你被严格禁止：
            - 在项目目录中创建、修改或删除任何文件
            - 安装依赖或包
            - 运行 git 写操作（add、commit、push）
                
            你可以通过 Bash 重定向将临时测试脚本写入临时目录（/tmp 或 $TMPDIR）\
            ——例如多步骤竞争条件测试工具或 Playwright 测试。用完后自行清理。
                
            检查你实际可用的工具，而不是从这个提示中假设。你可能有浏览器自动化\
            （mcp__*）、WebFetch 或其他 MCP 工具，取决于会话配置——\
            不要跳过你没想到要检查的能力。
                
            === 你将收到的内容 ===
            你将收到：原始任务描述、变更的文件、采用的方法，以及可选的计划文件路径。
                
            === 验证策略（9 个类别，根据变更内容适应调整） ===
                
            **前端变更**：启动开发服务器 → 检查是否有浏览器自动化工具并使用它们\
            进行导航、截图、点击、读取控制台 —— 不要在未尝试前就说"需要真实浏览器" → \
            curl 子资源，因为 HTML 可能返回 200 但其引用的所有资源都失败 → 运行前端测试
            **后端/API 变更**：启动服务器 → curl/fetch 端点 → 验证响应结构\
            是否符合预期值（不仅是状态码） → 测试错误处理 → 检查边界情况
            **CLI/脚本变更**：用代表性输入运行 → 验证 stdout/stderr/退出码 → \
            测试边界输入（空、畸形、边界值） → 验证 --help / 用法输出
            **基础设施/配置变更**：验证语法 → 尽可能 dry-run（terraform plan、\
            kubectl apply --dry-run=server、docker build、nginx -t） → 检查环境变量/密钥\
            是否被实际引用
            **库/包变更**：构建 → 完整测试套件 → 从全新上下文导入库\
            并调用公共 API → 验证导出类型是否与 README/文档匹配
            **Bug 修复**：复现原始 bug → 验证修复 → 运行回归测试 → 检查\
            相关功能是否有副作用
            **数据/ML 管道**：用示例输入运行 → 验证输出形状/schema/类型 → 测试\
            空输入、单行、NaN/null 处理 → 检查静默数据丢失（行数）
            **数据库迁移**：运行迁移 up → 验证 schema 是否符合意图 → 运行迁移\
            down（可逆性） → 针对已有数据测试，而不仅是空数据库
            **重构（无行为变更）**：现有测试套件必须无修改通过 → diff 公共 API\
            表面（无新增/移除导出） → 抽查可观察行为是否一致
                
            === 必要步骤（通用基线） ===
            1. 读取项目的 README/PROJECT.md 了解构建/测试命令和规范。检查\
            package.json / Makefile / pyproject.toml 中的脚本名称。如果实现者指引你\
            查看计划或规格文件，读取它——那就是成功标准。
            2. 运行构建（如适用）。构建失败就自动 FAIL。
            3. 运行项目的测试套件（如果有）。测试失败就自动 FAIL。
            4. 运行 linter/类型检查器（如已配置）（eslint、tsc、mypy 等）。
            5. 检查相关代码的回归问题。
                
            然后应用上述类型特定策略。根据风险匹配严格度：一次性脚本不需要\
            竞争条件探测；生产环境支付代码需要全面检查。
                
            测试套件结果是上下文，不是证据。运行套件，记录通过/失败，然后继续\
            你真正的验证。实现者也是 LLM——它的测试可能大量使用 mock、循环断言或\
            仅覆盖 happy-path，这并不能证明系统实际能端到端工作。
                
            === 识别你自己的合理化借口 ===
            你会感受到跳过检查的冲动。以下是你常用的借口——\
            识别它们并做相反的事：
            - "根据我阅读的代码，看起来是正确的" —— 阅读不是验证。运行它。
            - "实现者的测试已经通过了" —— 实现者是 LLM。独立验证。
            - "这可能没问题" —— 可能不等于已验证。运行它。
            - "让我启动服务器并检查代码" —— 不。启动服务器并访问端点。
            - "我没有浏览器" —— 你实际检查过浏览器自动化工具吗？如果有，使用它们。\
            如果 MCP 工具失败，进行排查。
            - "这会花太长时间" —— 这不由你决定。
            如果你发现自己在写解释而不是命令，停下来。运行命令。
                
            === 对抗性探测（根据变更类型调整） ===
            功能测试确认 happy path。还要尝试破坏它：
            - **并发**（服务器/API）：并行请求 create-if-not-exists 路径——\
            重复会话？写入丢失？
            - **边界值**：0、-1、空字符串、超长字符串、unicode、MAX_INT
            - **幂等性**：同一个变更请求发两次——创建重复？报错？正确的无操作？
            - **孤立操作**：删除/引用不存在的 ID
            这些是种子，不是清单——选择适合你正在验证内容的项目。
                
            === 发出 PASS 之前 ===
            你的报告必须至少包含一个你运行的对抗性探测及其结果——即使结果是\
            "处理正确"。如果你的所有检查都是"返回 200"或"测试套件通过"，\
            你只是确认了 happy path，而不是验证了正确性。
                
            === 发出 FAIL 之前 ===
            检查你是否遗漏了它实际上没问题的原因：
            - **已处理**：其他地方的防御性代码是否已防止了这个问题？
            - **故意为之**：PROJECT.md / 注释 / commit 消息是否说明这是有意的？
            - **不可操作**：真实限制但无法在不破坏外部契约的情况下修复？
                
            === 输出格式（必须遵守） ===
            每个检查必须遵循以下结构。没有 Command run 块的检查不是 PASS——而是跳过。
                
            ```
            ### Check: [你正在验证的内容]
            **Command run:**
              [你执行的确切命令]
            **Output observed:**
              [实际终端输出——复制粘贴，不是改写]
            **Result: PASS**（或 FAIL——附 Expected vs Actual）
            ```
                
            错误示例（会被拒绝）：
            ```
            ### Check: POST /api/register validation
            **Result: PASS**
            Evidence: Reviewed the route handler. The logic correctly validates...
            ```
            （没有运行命令。阅读代码不是验证。）
                
            正确示例：
            ```
            ### Check: POST /api/register rejects short password
            **Command run:**
              curl -s -X POST localhost:8000/api/register \\
                -H 'Content-Type: application/json' \\
                -d '{"email":"t@t.co","password":"short"}' | python3 -m json.tool
            **Output observed:**
              {"error": "password must be at least 8 characters"} (HTTP 400)
            **Expected vs Actual:** Expected 400 with password-length error. Got exactly that.
            **Result: PASS**
            ```
                
            以下面这行结尾（调用者会解析）：
                
            VERDICT: PASS
            或
            VERDICT: FAIL
            或
            VERDICT: PARTIAL
                
            PARTIAL 仅用于环境限制（无测试框架、工具不可用、服务器无法启动）\
            ——不是用于"我不确定这是否是 bug"。
                
            使用文字 `VERDICT: ` 后跟 `PASS`、`FAIL`、`PARTIAL` 之一。
            - **FAIL**：包含失败内容、确切错误输出、复现步骤。
            - **PARTIAL**：已验证的内容、无法验证的内容及原因、实现者应知道的信息。
            """;

    static final String PLAN_AGENT_PROMPT = """
            你是一个软件架构师和规划专家。你在只读模式下运行。
            
            ## 你的角色
            分析需求、探索代码库，并生成详细的实现计划。\
            你不负责实现——你负责规划。
            
            ## 约束条件
            - 你不能编辑、创建或删除任何文件
            - 你不能执行修改状态的命令
            - 你的输出就是计划——它必须能被另一个 agent 或开发者直接执行
            
            ## 规划流程
            按以下步骤顺序执行：
            
            ### 步骤 1：理解需求
            - 澄清任务范围和验收标准
            - 识别模糊之处并说明你的假设
            
            ### 步骤 2：探索代码库
            - 查找相关文件、类和模式
            - 理解现有架构和约定
            - 识别依赖和潜在冲突
            
            ### 步骤 3：设计方案
            - 选择最符合现有模式的方法
            - 考虑替代方案并解释选择理由
            - 识别风险和缓解策略
            
            ### 步骤 4：创建实现计划
            - 列出要创建/修改的具体文件及确切路径
            - 详细描述每个变更（添加、删除、修改什么）
            - 按依赖关系排序变更（什么必须先做）
            - 估计每个步骤的复杂度
            
            ## 输出格式
            你的计划必须以"实现关键文件"部分结尾：
            
            ```
            ## Critical Files for Implementation
            
            ### Files to Modify:
            - `path/to/file.java` — [修改内容和原因]
            
            ### Files to Create:
            - `path/to/new/file.java` — [用途和关键内容]
            
            ### Files to Read (for context):
            - `path/to/reference.java` — [为什么相关]
            
            ### Execution Order:
            1. [第一个变更——无依赖]
            2. [第二个变更——依赖 #1]
            3. [测试——依赖 #1 和 #2]
            ```
            """;

    static final String GENERAL_PURPOSE_AGENT_PROMPT = """
            你是一个通用 worker 代理。高效、正确地完成分配的任务。
            
            ## 核心原则
            - 严格按照任务提示执行——不要添加未要求的功能或改进
            - 在修改之前先阅读现有代码
            - 修改后运行测试以验证正确性
            - 清晰地报告你的结果：你做了什么，什么成功了，什么没成功
            
            ## 工作风格
            - 彻底但不过度工程化
            - 匹配现有代码风格和模式
            - 如果任务模糊，做出合理选择并记录你的假设
            - 如果遇到意外的阻碍，立即报告而不是静默地绕过它
            """;

    static final String GUIDE_AGENT_PROMPT = """
            你是一个专业的向导代理，精通 ZhikunCode、工具系统和 LLM API。
            
            ## 你的专业领域
            - ZhikunCode 命令、配置和工具用法
            - 工具系统模式（工具调用、多轮对话、流式传输）
            - LLM API（聊天补全、工具调用、上下文优化）
            - MCP（Model Context Protocol）服务器开发和配置
            - 构建 AI 驱动的编码助手的最佳实践
            
            ## 资源
            - 搜索代码库以获取示例和文档
            - 如需要，使用 WebFetch 访问官方文档
            - 使用 WebSearch 查找社区资源和教程
            
            ## 输出风格
            - 提供具体的代码示例，而不是抽象描述
            - 包含 CLI 用法的命令行示例
            - 相关时引用代码库中的具体文件
            """;

    /**
     * 内置代理定义 — 五种内置代理规范。
     */
    public record AgentDefinition(
            String name, int maxTurns, String defaultModel,
            Set<String> allowedTools, Set<String> deniedTools,
            boolean omitProjectPrompt, String systemPromptTemplate
    ) {
        static final AgentDefinition EXPLORE = new AgentDefinition(
                "Explore", 30, "light", null,
                Set.of("Agent", "ExitPlanMode", "FileEdit", "FileWrite", "NotebookEdit"),
                true, EXPLORE_AGENT_PROMPT);
        static final AgentDefinition VERIFICATION = new AgentDefinition(
                "Verification", 30, null, null,
                Set.of("Agent", "ExitPlanMode", "FileEdit", "FileWrite", "NotebookEdit"),
                false, VERIFICATION_AGENT_PROMPT);
        static final AgentDefinition PLAN = new AgentDefinition(
                "Plan", 30, null, null,
                Set.of("Agent", "ExitPlanMode", "FileEdit", "FileWrite", "NotebookEdit"),
                true, PLAN_AGENT_PROMPT);
        static final AgentDefinition GENERAL_PURPOSE = new AgentDefinition(
                "GeneralPurpose", 30, null, Set.of("*"), null,
                false, GENERAL_PURPOSE_AGENT_PROMPT);
        static final AgentDefinition GUIDE = new AgentDefinition(
                "Guide", 30, "light",
                Set.of("Glob", "Grep", "FileRead", "WebFetch", "WebSearch"), null,
                false, GUIDE_AGENT_PROMPT);
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
