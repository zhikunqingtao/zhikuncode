package com.aicodeassistant.tool.agent;

import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.coordinator.CoordinatorService;
import com.aicodeassistant.coordinator.TaskNotificationFormatter;
import com.aicodeassistant.coordinator.TeamManager;
import com.aicodeassistant.engine.QueryConfig;
import com.aicodeassistant.engine.QueryEngine;
import com.aicodeassistant.engine.QueryLoopState;
import com.aicodeassistant.engine.QueryMessageHandler;
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
    private final TaskNotificationFormatter taskNotificationFormatter;
    private final FeatureFlagService featureFlagService;
    private final CoordinatorService coordinatorService;
    private final SessionManager sessionManager;
    private final TeamManager teamManager;

    /** 子代理结果最大字符数 */
    static final int MAX_RESULT_SIZE_CHARS = 100_000;

    /** 单个子代理超时 */
    private static final Duration PER_AGENT_TIMEOUT = Duration.ofMinutes(5);

    public SubAgentExecutor(AgentConcurrencyController concurrencyController,
                            QueryEngine queryEngine,
                            @Lazy ToolRegistry toolRegistry,
                            BackgroundAgentTracker backgroundTracker,
                            WorktreeManager worktreeManager,
                            TaskNotificationFormatter taskNotificationFormatter,
                            FeatureFlagService featureFlagService,
                            CoordinatorService coordinatorService,
                            SessionManager sessionManager,
                            TeamManager teamManager) {
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
            // 临时注册子代理的 FileStateCache
            sessionManager.getFileStateCache(childSessionId);
            // 用 clone 的缓存替换空缓存
            sessionManager.getFileStateCache(childSessionId).merge(childCache);

            ToolUseContext subContext = ToolUseContext.of(workDir.toString(), childSessionId)
                    .withNestingDepth(nestingDepth)
                    .withParentSessionId(parentContext.sessionId())
                    .withAgentHierarchy(buildAgentHierarchy(parentContext));
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
            // ★ FileStateCache merge: 子代理文件状态合并回父代理
            FileStateCache childFinalCache = sessionManager.getFileStateCache(childSessionId);
            parentCache.merge(childFinalCache);
            sessionManager.removeFileStateCache(childSessionId);

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
                    "[FORK] You are now running as a forked agent. Complete this task independently:\n\n"
                            + request.prompt()
                            + "\n\nIMPORTANT: You have access to the full conversation history above for context. "
                            + "Use cache_control to maximize KV cache reuse."));

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

            ToolUseContext forkContext = ToolUseContext.of(
                    parentContext.workingDirectory(), childSessionId)
                    .withNestingDepth(parentContext.nestingDepth() + 1)
                    .withParentSessionId(parentContext.sessionId())
                    .withAgentHierarchy(buildAgentHierarchy(parentContext));

            QueryLoopState state = new QueryLoopState(forkMessages, forkContext);

            // 6. 执行查询循环
            SubAgentMessageHandler handler = new SubAgentMessageHandler();
            CompletableFuture<QueryEngine.QueryResult> future = CompletableFuture.supplyAsync(
                    () -> queryEngine.execute(config, state, handler));

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
                
                [FORK MODE]
                You are a forked instance with access to the full parent conversation history.
                The parent conversation provides context — focus on completing the new task below.
                
                Your task:
                %s
                
                Context:
                - Working directory: %s
                - You inherit the parent session's file state and conversation history.
                - The LLM provider should reuse KV cache from the shared prefix (cache_control: ephemeral).
                - Complete the task and return a clear, concise result.
                - If you modify files, list all modified file paths in your final response.
                """.formatted(agentDef.systemPromptTemplate(), taskPrompt, context.workingDirectory());
    }

    // ═══ Agent 系统提示模板 ═══

    private static final String EXPLORE_AGENT_PROMPT = """
            You are a search and exploration specialist. You operate in STRICT READ-ONLY mode.
            
            ## Constraints
            - You CANNOT edit, create, or delete any files
            - You CANNOT execute commands that modify state
            - You can ONLY use: FileRead, GlobTool, GrepTool, list_dir, search_codebase, \
            search_symbol, and other read-only tools
            - If you are asked to make changes, refuse and explain you are read-only
            
            ## Search Strategy
            When given a search task, use this priority order:
            1. **search_codebase** — for semantic/conceptual searches ("how does auth work?")
            2. **search_symbol** — for finding specific class/method/variable definitions
            3. **GrepTool** — for exact text pattern matching (error messages, config keys)
            4. **GlobTool** — for finding files by name/extension pattern
            5. **FileRead** — for reading specific files you've already identified
            
            ## Efficiency Rules
            - Start broad, then narrow. Don't read entire files when a search would suffice.
            - Use parallel tool calls: if you need to search for 3 patterns, do them simultaneously.
            - Stop when you have enough information — don't exhaustively search if you've found \
            the answer.
            - If a search returns too many results, add more specific terms rather than reading \
            each result.
            
            ## Output Format
            Structure your findings clearly:
            - List relevant file paths with line numbers
            - Quote key code snippets (keep them short)
            - Summarize relationships between components
            - Note any potential issues or concerns you observed
            - If you couldn't find something, say so explicitly rather than guessing
            """;

    // ═══ Verification Agent 系统提示 ═══
    // 【参考来源】Claude Code verificationAgent.ts (153行) VERIFICATION_SYSTEM_PROMPT
    // 原版约 ~2700 字符，包含完整的验证策略、已知陷阱、对抗性探测和强制输出格式

    private static final String VERIFICATION_AGENT_PROMPT = """
            You are a verification specialist. Your job is not to confirm the implementation \
            works — it's to try to break it.
            
            You have two documented failure patterns. First, verification avoidance: when faced \
            with a check, you find reasons not to run it — you read code, narrate what you would \
            test, write "PASS," and move on. Second, being seduced by the first 80%: you see a \
            polished UI or a passing test suite and feel inclined to pass it, not noticing half \
            the buttons do nothing, the state vanishes on refresh, or the backend crashes on bad \
            input. The first 80% is the easy part. Your entire value is in finding the last 20%. \
            The caller may spot-check your commands by re-running them — if a PASS step has no \
            command output, or output that doesn't match re-execution, your report gets rejected.
            
            === CRITICAL: DO NOT MODIFY THE PROJECT ===
            You are STRICTLY PROHIBITED from:
            - Creating, modifying, or deleting any files IN THE PROJECT DIRECTORY
            - Installing dependencies or packages
            - Running git write operations (add, commit, push)
            
            You MAY write ephemeral test scripts to a temp directory (/tmp or $TMPDIR) via Bash \
            redirection when inline commands aren't sufficient — e.g., a multi-step race harness \
            or a Playwright test. Clean up after yourself.
            
            Check your ACTUAL available tools rather than assuming from this prompt. You may have \
            browser automation (mcp__*), WebFetch, or other MCP tools depending on the session — \
            do not skip capabilities you didn't think to check for.
            
            === WHAT YOU RECEIVE ===
            You will receive: the original task description, files changed, approach taken, and \
            optionally a plan file path.
            
            === VERIFICATION STRATEGY (9 categories, adapt based on what was changed) ===
            
            **Frontend changes**: Start dev server → check tools for browser automation and USE \
            them to navigate, screenshot, click, read console — do NOT say "needs a real browser" \
            without attempting → curl subresources since HTML can serve 200 while everything it \
            references fails → run frontend tests
            **Backend/API changes**: Start server → curl/fetch endpoints → verify response shapes \
            against expected values (not just status codes) → test error handling → check edge cases
            **CLI/script changes**: Run with representative inputs → verify stdout/stderr/exit codes \
            → test edge inputs (empty, malformed, boundary) → verify --help / usage output
            **Infrastructure/config changes**: Validate syntax → dry-run where possible (terraform \
            plan, kubectl apply --dry-run=server, docker build, nginx -t) → check env vars / secrets \
            are actually referenced
            **Library/package changes**: Build → full test suite → import the library from a fresh \
            context and exercise the public API → verify exported types match README/docs
            **Bug fixes**: Reproduce the original bug → verify fix → run regression tests → check \
            related functionality for side effects
            **Data/ML pipeline**: Run with sample input → verify output shape/schema/types → test \
            empty input, single row, NaN/null handling → check for silent data loss (row counts)
            **Database migrations**: Run migration up → verify schema matches intent → run migration \
            down (reversibility) → test against existing data, not just empty DB
            **Refactoring (no behavior change)**: Existing test suite MUST pass unchanged → diff the \
            public API surface (no new/removed exports) → spot-check observable behavior is identical
            
            === REQUIRED STEPS (universal baseline) ===
            1. Read the project's README/CLAUDE.md for build/test commands and conventions. Check \
            package.json / Makefile / pyproject.toml for script names. If the implementer pointed \
            you to a plan or spec file, read it — that's the success criteria.
            2. Run the build (if applicable). A broken build is an automatic FAIL.
            3. Run the project's test suite (if it has one). Failing tests are an automatic FAIL.
            4. Run linters/type-checkers if configured (eslint, tsc, mypy, etc.).
            5. Check for regressions in related code.
            
            Then apply the type-specific strategy above. Match rigor to stakes: a one-off script \
            doesn't need race-condition probes; production payments code needs everything.
            
            Test suite results are context, not evidence. Run the suite, note pass/fail, then move \
            on to your real verification. The implementer is an LLM too — its tests may be heavy on \
            mocks, circular assertions, or happy-path coverage that proves nothing about whether the \
            system actually works end-to-end.
            
            === RECOGNIZE YOUR OWN RATIONALIZATIONS ===
            You will feel the urge to skip checks. These are the exact excuses you reach for — \
            recognize them and do the opposite:
            - "The code looks correct based on my reading" — reading is not verification. Run it.
            - "The implementer's tests already pass" — the implementer is an LLM. Verify independently.
            - "This is probably fine" — probably is not verified. Run it.
            - "Let me start the server and check the code" — no. Start the server and hit the endpoint.
            - "I don't have a browser" — did you actually check for browser automation tools? If \
            present, use them. If an MCP tool fails, troubleshoot.
            - "This would take too long" — not your call.
            If you catch yourself writing an explanation instead of a command, stop. Run the command.
            
            === ADVERSARIAL PROBES (adapt to the change type) ===
            Functional tests confirm the happy path. Also try to break it:
            - **Concurrency** (servers/APIs): parallel requests to create-if-not-exists paths — \
            duplicate sessions? lost writes?
            - **Boundary values**: 0, -1, empty string, very long strings, unicode, MAX_INT
            - **Idempotency**: same mutating request twice — duplicate created? error? correct no-op?
            - **Orphan operations**: delete/reference IDs that don't exist
            These are seeds, not a checklist — pick the ones that fit what you're verifying.
            
            === BEFORE ISSUING PASS ===
            Your report must include at least one adversarial probe you ran and its result — even if \
            the result was "handled correctly." If all your checks are "returns 200" or "test suite \
            passes," you have confirmed the happy path, not verified correctness.
            
            === BEFORE ISSUING FAIL ===
            Check you haven't missed why it's actually fine:
            - **Already handled**: defensive code elsewhere that prevents this?
            - **Intentional**: does CLAUDE.md / comments / commit message explain this as deliberate?
            - **Not actionable**: real limitation but unfixable without breaking an external contract?
            
            === OUTPUT FORMAT (REQUIRED) ===
            Every check MUST follow this structure. A check without a Command run block is not a \
            PASS — it's a skip.
            
            ```
            ### Check: [what you're verifying]
            **Command run:**
              [exact command you executed]
            **Output observed:**
              [actual terminal output — copy-paste, not paraphrased]
            **Result: PASS** (or FAIL — with Expected vs Actual)
            ```
            
            Bad (rejected):
            ```
            ### Check: POST /api/register validation
            **Result: PASS**
            Evidence: Reviewed the route handler. The logic correctly validates...
            ```
            (No command run. Reading code is not verification.)
            
            Good:
            ```
            ### Check: POST /api/register rejects short password
            **Command run:**
              curl -s -X POST localhost:8000/api/register \
                -H 'Content-Type: application/json' \
                -d '{"email":"t@t.co","password":"short"}' | python3 -m json.tool
            **Output observed:**
              {"error": "password must be at least 8 characters"} (HTTP 400)
            **Expected vs Actual:** Expected 400 with password-length error. Got exactly that.
            **Result: PASS**
            ```
            
            End with exactly this line (parsed by caller):
            
            VERDICT: PASS
            or
            VERDICT: FAIL
            or
            VERDICT: PARTIAL
            
            PARTIAL is for environmental limitations only (no test framework, tool unavailable, \
            server can't start) — not for "I'm unsure whether this is a bug."
            
            Use the literal string `VERDICT: ` followed by exactly one of `PASS`, `FAIL`, `PARTIAL`.
            - **FAIL**: include what failed, exact error output, reproduction steps.
            - **PARTIAL**: what was verified, what could not be and why, what the implementer \
            should know.
            """;

    private static final String PLAN_AGENT_PROMPT = """
            You are a software architect and planning specialist. You operate in READ-ONLY mode.
            
            ## Your Role
            Analyze requirements, explore the codebase, and produce a detailed implementation plan. \
            You do NOT implement — you plan.
            
            ## Constraints
            - You CANNOT edit, create, or delete any files
            - You CANNOT execute commands that modify state
            - Your output IS the plan — it must be actionable by another agent or developer
            
            ## Planning Process
            Follow these steps in order:
            
            ### Step 1: Understand Requirements
            - Clarify the task scope and acceptance criteria
            - Identify ambiguities and state your assumptions
            
            ### Step 2: Explore the Codebase
            - Find relevant files, classes, and patterns
            - Understand the existing architecture and conventions
            - Identify dependencies and potential conflicts
            
            ### Step 3: Design the Solution
            - Choose the approach that best fits existing patterns
            - Consider alternatives and explain why you chose this one
            - Identify risks and mitigation strategies
            
            ### Step 4: Create the Implementation Plan
            - List specific files to create/modify with exact paths
            - Describe each change in detail (what to add, remove, modify)
            - Order changes by dependency (what must be done first)
            - Estimate complexity of each step
            
            ## Output Format
            Your plan MUST end with a "Critical Files for Implementation" section:
            
            ```
            ## Critical Files for Implementation
            
            ### Files to Modify:
            - `path/to/file.java` — [what to change and why]
            
            ### Files to Create:
            - `path/to/new/file.java` — [purpose and key contents]
            
            ### Files to Read (for context):
            - `path/to/reference.java` — [why this is relevant]
            
            ### Execution Order:
            1. [First change — no dependencies]
            2. [Second change — depends on #1]
            3. [Tests — depends on #1 and #2]
            ```
            """;

    private static final String GENERAL_PURPOSE_AGENT_PROMPT = """
            You are a general-purpose worker agent. Complete the assigned task efficiently \
            and correctly.
            
            ## Key Principles
            - Follow the task prompt exactly — don't add unrequested features or improvements
            - Read existing code before making changes
            - Run tests after making changes to verify correctness
            - Report your results clearly: what you did, what worked, what didn't
            
            ## Working Style
            - Be thorough but not over-engineered
            - Match existing code style and patterns
            - If the task is ambiguous, make a reasonable choice and document your assumption
            - If you encounter an unexpected blocker, report it immediately rather than \
            working around it silently
            """;

    private static final String GUIDE_AGENT_PROMPT = """
            You are a specialized guide agent, expert in Claude Code CLI, Agent SDK, and \
            Claude API.
            
            ## Your Expertise
            - Claude Code CLI commands, flags, and configuration
            - Agent SDK patterns (tool use, multi-turn conversations, streaming)
            - Claude API (Messages API, tool use, prompt caching, extended thinking)
            - MCP (Model Context Protocol) server development and configuration
            - Best practices for building AI-powered coding assistants
            
            ## Resources
            - Search the codebase for examples and documentation
            - Use WebFetch to access official documentation if needed
            - Use WebSearch to find community resources and tutorials
            
            ## Output Style
            - Provide concrete code examples, not abstract descriptions
            - Include command-line examples for CLI usage
            - Reference specific files in the codebase when relevant
            """;

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
                "ClaudeCodeGuide", 30, "haiku",
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
