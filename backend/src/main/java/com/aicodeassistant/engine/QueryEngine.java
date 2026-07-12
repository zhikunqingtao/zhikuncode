package com.aicodeassistant.engine;

import com.aicodeassistant.config.AgentTimeoutConfig;
import com.aicodeassistant.engine.correction.CorrectionInstruction;
import com.aicodeassistant.engine.correction.SelfCorrectionLoop;
import com.aicodeassistant.engine.scheduling.ToolPriorityScheduler;
import com.aicodeassistant.engine.strategy.DefaultTerminationStrategy;
import com.aicodeassistant.engine.strategy.TerminationDecision;
import com.aicodeassistant.engine.strategy.TerminationStrategy;
import com.aicodeassistant.engine.strategy.TerminationStrategy.LoopContext;
import com.aicodeassistant.engine.strategy.TerminationStrategy.ToolCallRecord;
import com.aicodeassistant.engine.tracking.ToolCallTracker;
import com.aicodeassistant.history.FileHistoryService;
import com.aicodeassistant.hook.HookRegistry;
import com.aicodeassistant.hook.HookService;
import com.aicodeassistant.llm.*;
import com.aicodeassistant.model.*;
import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.permission.PermissionPipeline;
import com.aicodeassistant.permission.PermissionRuleRepository;
import com.aicodeassistant.tool.*;
import com.aicodeassistant.tool.agent.BackgroundAgentTracker;
import com.aicodeassistant.run.RunEnvelope;
import com.aicodeassistant.run.RunTracker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * QueryEngine — 查询引擎核心循环。
 * <p>
 * 8 步循环: 压缩检查 → 流式执行器初始化 → API 调用 → 流处理 →
 *          工具执行 → 继续/终止判定 → 工具摘要注入 → 状态更新
 * <p>
 * 在 Virtual Thread 中执行，配合 StreamChatCallback 实现流式输出。
 *
 */
@Service
public class QueryEngine {

    private static final Logger log = LoggerFactory.getLogger(QueryEngine.class);

    private static final String MAX_TOKENS_RECOVERY_MESSAGE =
            "Output token limit hit. Resume directly — no apology, " +
            "no recap of what you were doing. Pick up mid-thought if " +
            "that is where the cut happened. Break remaining work " +
            "into smaller pieces.";

    private final LlmProviderRegistry providerRegistry;
    private final CompactService compactService;
    private final ApiRetryService apiRetryService;
    private final PermissionPipeline permissionPipeline;
    private final PermissionRuleRepository permissionRuleRepository;
    private final TokenCounter tokenCounter;
    private final ObjectMapper objectMapper;
    private final StreamingToolExecutor streamingToolExecutor;
    private final MessageNormalizer messageNormalizer;
    private final HookService hookService;
    private final SnipService snipService;
    private final MicroCompactService microCompactService;
    private final ModelRegistry modelRegistry;  // P0-1 新增
    private final ThinkingBudgetCalculator thinkingBudgetCalculator;
    private final ModelTierService modelTierService;
    private final FileHistoryService fileHistoryService;
    private final ToolResultSummarizer toolResultSummarizer;
    private final ContextCascade contextCascade;
    private final CompactMetrics compactMetrics;
    @org.springframework.lang.Nullable
    private final IncrementalCollapseManager incrementalCollapseManager;
    @org.springframework.lang.Nullable
    private final VisualizationAutoRouter visualizationAutoRouter;
    @org.springframework.lang.Nullable
    private final BackgroundAgentTracker backgroundAgentTracker;
    private final FeatureFlagService featureFlagService;
    private final TerminationStrategy terminationStrategy;
    private final ToolPriorityScheduler toolPriorityScheduler;
    private final SelfCorrectionLoop selfCorrectionLoop;
    private final AgentTimeoutConfig agentTimeoutConfig;
    private final RunTracker runTracker;

    /** 单条工具结果最大占上下文窗口的 30% */
    private static final double TOOL_RESULT_BUDGET_RATIO = 0.3;
    /** MicroCompact 保护尾部消息数 */
    private static final int MICRO_COMPACT_PROTECTED_TAIL = 10;

    /** 记录已通知的 thinking 降级 (once-only) */
    private final Set<String> notifiedThinkingDowngrades = ConcurrentHashMap.newKeySet();

    /** 会话级中断上下文 — sessionId → AbortContext */
    private final ConcurrentHashMap<String, AbortContext> abortContexts = new ConcurrentHashMap<>();

    private final ScheduledExecutorService cleanupScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "abort-context-cleanup");
                t.setDaemon(true);
                return t;
            });
    private ScheduledFuture<?> cleanupTask;

    public QueryEngine(LlmProviderRegistry providerRegistry,
                       CompactService compactService,
                       ApiRetryService apiRetryService,
                       PermissionPipeline permissionPipeline,
                       PermissionRuleRepository permissionRuleRepository,
                       TokenCounter tokenCounter,
                       ObjectMapper objectMapper,
                       StreamingToolExecutor streamingToolExecutor,
                       MessageNormalizer messageNormalizer,
                       HookService hookService,
                       SnipService snipService,
                       MicroCompactService microCompactService,
                       ModelRegistry modelRegistry,
                       ThinkingBudgetCalculator thinkingBudgetCalculator,
                       ModelTierService modelTierService,
                       FileHistoryService fileHistoryService,
                       ToolResultSummarizer toolResultSummarizer,
                       ContextCascade contextCascade,
                       CompactMetrics compactMetrics,
                       @org.springframework.lang.Nullable IncrementalCollapseManager incrementalCollapseManager,
                       @org.springframework.lang.Nullable VisualizationAutoRouter visualizationAutoRouter,
                       @org.springframework.lang.Nullable BackgroundAgentTracker backgroundAgentTracker,
                       FeatureFlagService featureFlagService,
                       TerminationStrategy terminationStrategy,
                       ToolPriorityScheduler toolPriorityScheduler,
                       SelfCorrectionLoop selfCorrectionLoop,
                       AgentTimeoutConfig agentTimeoutConfig,
                       @org.springframework.context.annotation.Lazy RunTracker runTracker) {
        this.providerRegistry = providerRegistry;
        this.compactService = compactService;
        this.apiRetryService = apiRetryService;
        this.permissionPipeline = permissionPipeline;
        this.permissionRuleRepository = permissionRuleRepository;
        this.tokenCounter = tokenCounter;
        this.objectMapper = objectMapper;
        this.streamingToolExecutor = streamingToolExecutor;
        this.messageNormalizer = messageNormalizer;
        this.hookService = hookService;
        this.snipService = snipService;
        this.microCompactService = microCompactService;
        this.modelRegistry = modelRegistry;
        this.thinkingBudgetCalculator = thinkingBudgetCalculator;
        this.modelTierService = modelTierService;
        this.fileHistoryService = fileHistoryService;
        this.toolResultSummarizer = toolResultSummarizer;
        this.contextCascade = contextCascade;
        this.compactMetrics = compactMetrics;
        this.incrementalCollapseManager = incrementalCollapseManager;
        this.visualizationAutoRouter = visualizationAutoRouter;
        this.backgroundAgentTracker = backgroundAgentTracker;
        this.featureFlagService = featureFlagService;
        this.terminationStrategy = terminationStrategy;
        this.toolPriorityScheduler = toolPriorityScheduler;
        this.selfCorrectionLoop = selfCorrectionLoop;
        this.agentTimeoutConfig = agentTimeoutConfig;
        this.runTracker = runTracker;
    }

    @PostConstruct
    void scheduleAbortContextCleanup() {
        cleanupTask = cleanupScheduler.scheduleAtFixedRate(() -> {
            int before = abortContexts.size();
            abortContexts.entrySet().removeIf(e -> e.getValue().isExpired());
            int removed = before - abortContexts.size();
            if (removed > 0) {
                log.info("AbortContext cleanup: removed {} expired entries, remaining {}",
                        removed, abortContexts.size());
            }
        }, 30, 30, TimeUnit.MINUTES);
    }

    @PreDestroy
    void shutdownCleanupScheduler() {
        if (cleanupTask != null) cleanupTask.cancel(false);
        cleanupScheduler.shutdown();
    }

    /**
     * 中断指定会话的查询循环。
     * 由 WebSocketController.handleInterrupt() 调用。
     *
     * @param sessionId 会话 ID
     * @param reason    中断原因
     */
    public void abort(String sessionId, AbortReason reason) {
        AbortContext ctx = abortContexts.get(sessionId);
        if (ctx != null) {
            ctx.abort(reason);
            log.info("QueryEngine abort: sessionId={}, reason={}", sessionId, reason);
        } else {
            // 连接断开时查询未启动/已结束属于正常场景，降为 debug 避免日志噪声
            log.debug("No active AbortContext for sessionId={}", sessionId);
        }
    }

    /**
     * 获取或创建会话的 AbortContext。
     */
    public AbortContext getOrCreateAbortContext(String sessionId) {
        return abortContexts.computeIfAbsent(sessionId, k -> new AbortContext());
    }

    /**
     * 移除会话的 AbortContext。
     */
    public void removeAbortContext(String sessionId) {
        abortContexts.remove(sessionId);
    }

    /**
     * 获取会话的 AbortContext（只读，不创建）。
     */
    public AbortContext getAbortContext(String sessionId) {
        return abortContexts.get(sessionId);
    }

    /**
     * 执行查询 — 查询引擎入口。
     * <p>
     * 在 Virtual Thread 中运行完整的 8 步查询循环。
     *
     * @param config  查询配置
     * @param state   循环状态
     * @param handler 消息处理器 (流式输出)
     * @return 查询结果
     */
    public QueryResult execute(QueryConfig config, QueryLoopState state,
                                QueryMessageHandler handler) {
        log.info("QueryEngine 开始执行: model={}, maxTokens={}, maxTurns={}",
                config.model(), config.maxTokens(), config.maxTurns());

        AtomicBoolean aborted = new AtomicBoolean(false);
        Usage totalUsage = Usage.zero();

        // 将 AbortContext 连接到本地 aborted 标志，使得外部 abort() 调用能实际停止循环
        String sessionId = state.getToolUseContext() != null
                ? state.getToolUseContext().sessionId() : null;
        if (sessionId != null) {
            AbortContext abortCtx = getOrCreateAbortContext(sessionId);
            abortCtx.onAbort().thenAccept(reason -> {
                aborted.set(true);
                state.setAbortReason(reason);
            });
        }

        // ★ RunTracker: 启动运行追踪并将 runId 传播到 ToolUseContext
        String currentRunId = null;
        boolean runFailureRecorded = false;
        if (runTracker != null && sessionId != null) {
            try {
                RunEnvelope run = runTracker.startRun(sessionId, null, "query", config.model());
                currentRunId = run.id();
                if (state.getToolUseContext() != null) {
                    state.setToolUseContext(state.getToolUseContext().withCurrentRunId(currentRunId));
                }
            } catch (Exception e) {
                log.warn("Failed to start RunTracker run: {}", e.getMessage());
            }
        }

        try {
            totalUsage = queryLoop(config, state, handler, aborted);
        } catch (Exception e) {
            log.error("QueryEngine 执行异常", e);
            handler.onError(e);
            // ★ RunTracker: 异常路径 — 标记为 FAILED
            if (currentRunId != null && runTracker != null) {
                try {
                    runTracker.failRun(currentRunId, e.getMessage());
                    runFailureRecorded = true;
                } catch (Exception ex) {
                    log.warn("Failed to record RunTracker failure: {}", ex.getMessage());
                }
            }
            return new QueryResult(state.getMessages(), totalUsage,
                    "error", e.getMessage(), state.getTurnCount());
        } finally {
            // P1-04: 确保清理 AbortContext，防止内存泄漏
            if (sessionId != null) {
                abortContexts.remove(sessionId);
            }
        }

        // ★ RunTracker: 根据实际结束原因选择正确的状态转换
        if (!runFailureRecorded && currentRunId != null && runTracker != null) {
            try {
                if (aborted.get()) {
                    // 用户中断或超时 — 标记为 ABORTED
                    AbortReason abortReason = state.getAbortReason() != null
                            ? state.getAbortReason() : AbortReason.USER_INTERRUPT;
                    String reason = abortReason == AbortReason.TIMEOUT
                            ? "timeout" : abortReason.name().toLowerCase();
                    runTracker.abortRun(currentRunId, reason);
                } else {
                    // 正常完成 — 标记为 COMPLETED
                    runTracker.completeRun(currentRunId, totalUsage.totalTokens(),
                            0.0, 0, state.getTurnCount());
                }
            } catch (Exception e) {
                log.warn("Failed to update RunTracker run status: {}", e.getMessage());
            }
        }

        String stopReason = state.getTurnCount() >= config.maxTurns()
                ? "max_turns" : "end_turn";
        log.info("QueryEngine 完成: turns={}, stopReason={}, totalTokens={}",
                state.getTurnCount(), stopReason, totalUsage.totalTokens());

        return new QueryResult(state.getMessages(), totalUsage,
                stopReason, null, state.getTurnCount());
    }

    /**
     * 核心查询循环 — 8 步迭代。
     */
    private Usage queryLoop(QueryConfig config, QueryLoopState state,
                            QueryMessageHandler handler, AtomicBoolean aborted) {
        Usage totalUsage = Usage.zero();
        String[] currentModel = { config.model() };
        // ★ 注入 parentModel 到 ToolUseContext，供子代理继承父会话模型
        if (state.getToolUseContext() != null) {
            state.setToolUseContext(state.getToolUseContext().withParentModel(currentModel[0]));
        }
        TokenBudgetTracker tokenBudgetTracker = config.tokenBudget() != null
                ? new TokenBudgetTracker() : null;

        // 创建局部工具调用追踪器（每次 queryLoop 独立实例，避免跨会话状态污染）
        ToolCallTracker tracker = new ToolCallTracker();

        log.info("[DIAG] queryLoop 进入: model={}, messageCount={}, maxTurns={}, aborted={}",
                config.model(), state.getMessages().size(), config.maxTurns(), aborted.get());

        while (!aborted.get()) {
            state.incrementTurnCount();
            int turn = state.getTurnCount();
            log.info("[DIAG] Turn {} 开始: messageCount={}, model={}", turn, state.getMessages().size(), currentModel[0]);
            handler.onTurnStart(turn);

            // ===== Step 0.5: Incremental collapse check =====
            String loopSessionId = state.getToolUseContext() != null
                    ? state.getToolUseContext().sessionId() : null;
            // ===== Step 0.6: Visualization Auto-Router (v1.5 升级项 C Beta) =====
            // 默认关闭下适配器内部直接 return，零开销；命中时独立消息推送，不改变循环语义。
            if (visualizationAutoRouter != null) {
                visualizationAutoRouter.maybeRoute(loopSessionId, state);
            }
            if (incrementalCollapseManager != null && loopSessionId != null) {
                log.debug("Incremental collapse check: sessionId={}, cumulativeContribution=1", loopSessionId);
                if (incrementalCollapseManager.shouldCollapse(loopSessionId, 1)) {
                    log.debug("Incremental collapse triggered at cumulative turn (request contribution: 1)");
                    state.setIncrementalCollapseNeeded(true);
                }
            }

            // ===== Step 1: 压缩级联（统一入口）=====
            ContextCascade.AutoCompactTrackingState trackingState = state.isAutoCompactEnabled()
                    ? state.toAutoCompactTrackingState()
                    : new ContextCascade.AutoCompactTrackingState(false, state.getTurnCount(), null, Integer.MAX_VALUE);
            ContextCascade.CascadeResult cascadeResult = contextCascade.executePreApiCascade(
                    state.getMessages(), currentModel[0], trackingState);
            state.setMessages(cascadeResult.messages());

            // ===== Step 1b: AutoCompact 状态回写 =====
            if (cascadeResult.autoCompactExecuted()) {
                state.resetAutoCompactFailures();
            } else if (cascadeResult.autoCompactAttempted()) {
                state.incrementAutoCompactFailures();
            }

            // ===== Step 2: 创建流式执行会话 =====
            StreamingToolExecutor.ExecutionSession session =
                    streamingToolExecutor.newSession(state.getToolUseContext());

            // ===== Step 3: API 调用 =====
            int effectiveMaxTokens = state.getEffectiveMaxTokens(config.maxTokens());
            // ★ 模型降级解析 — 检查当前模型是否在冷却期
            String effectiveModel = modelTierService.resolveModel(
                    currentModel[0],
                    config.modelTierChain()
            );
            if (!effectiveModel.equals(currentModel[0])) {
                log.info("Model tier switch: {} → {}", currentModel[0], effectiveModel);
                // 检查新模型是否支持 thinking，不支持则移除 thinking blocks
                if (!providerRegistry.getProvider(effectiveModel).supportsThinking(effectiveModel)) {
                    stripThinkingBlocks(state);
                }
            }
            LlmProvider provider = providerRegistry.getProvider(effectiveModel);
            log.info("[DIAG] Turn {} Step3: provider={}, effectiveModel={}, effectiveMaxTokens={}",
                    turn, provider.getClass().getSimpleName(), effectiveModel, effectiveMaxTokens);
            List<MessageParam> typedMessages = messageNormalizer.normalizeTyped(state.getMessages());
            List<Map<String, Object>> apiMessages = MessageParamConverter.toMaps(typedMessages);
            log.info("[DIAG] Turn {} Step3: apiMessages.size={}, typedMessages.size={}",
                    turn, apiMessages.size(), typedMessages.size());

            // StreamCollector 持有 session, 支持流式工具启动
            StreamCollector collector = new StreamCollector(
                    handler, session, config.tools(),
                    state.getToolUseContext(), objectMapper);

            // P1-16: ThinkingConfig 降级检查
            ThinkingConfig resolvedThinking = resolveThinking(
                    config.thinkingConfig(), provider, currentModel[0], handler, state);

            log.info("[DIAG] Turn {} Step3: 开始 API 调用 streamChat...", turn);
            try {
                apiRetryService.executeWithRetry(() -> {
                    provider.streamChat(
                            effectiveModel,
                            apiMessages,
                            config.systemPrompt(),
                            config.toolDefinitions(),
                            effectiveMaxTokens,
                            resolvedThinking,
                            collector
                    );
                    return null;
                }, config.querySource(), effectiveModel);
            } catch (LlmApiException e) {
                // 413 prompt_too_long → 消息扣留 + 两阶段恢复
                if (e.getStatusCode() == 413 || (e.getMessage() != null
                        && e.getMessage().contains("prompt_too_long"))) {

                    // 扣留错误，不立即释放给消费者
                    state.addWithheldError(e);
                    state.setPromptTooLongWithheld(true);
                    int recoveryAttempt = state.getWithheldErrors().size();
                    handler.onRecovery(RecoveryEvent.of413(recoveryAttempt, "collapse drain"));

                    // Phase 1: context-collapse drain (防止重复)
                    if (!"collapse_drain_retry".equals(state.getLastTransitionReason())) {
                        int drained = tryContextCollapseDrain(config, state, handler);
                        if (drained > 0) {
                            state.setLastTransitionReason("collapse_drain_retry");
                            state.clearWithheldErrors();
                            state.setPromptTooLongWithheld(false);
                            continue;
                        }
                    }

                    // Phase 2: reactive compact (单次 guard)
                    handler.onRecovery(RecoveryEvent.of413(recoveryAttempt, "reactive compact"));
                    if (tryReactiveCompact(config, state, handler)) {
                        state.setLastTransitionReason("reactive_compact_retry");
                        state.clearWithheldErrors();
                        state.setPromptTooLongWithheld(false);
                        continue;
                    }

                    // Phase 3: 媒体文件恢复
                    if (isMediaRelatedError(e)) {
                        handler.onRecovery(RecoveryEvent.ofMedia(recoveryAttempt, "strip media"));
                        if (tryStripMediaBlocks(state, handler)) {
                            state.setLastTransitionReason("media_strip_retry");
                            state.clearWithheldErrors();
                            state.setPromptTooLongWithheld(false);
                            continue;
                        }
                    }

                    // 恢复耗尽，释放扣留错误给消费者
                    log.error("413 recovery exhausted: collapse drain failed, reactive compact failed");
                    for (LlmApiException withheld : state.getWithheldErrors()) {
                        handler.onError(withheld);
                    }
                    state.clearWithheldErrors();
                    state.setPromptTooLongWithheld(false);
                    break;  // 413恢复耗尽，终止循环防止异常继续流向Fallback处理
                }
                // FIX-03: Fallback 模型降级
                if (e instanceof LlmApiException llmEx && llmEx.isFallbackTrigger()
                        && config.fallbackModel() != null
                        && !config.fallbackModel().equals(currentModel[0])) {
                    log.warn("Fallback triggered: {} → {}, reason: {}",
                            currentModel[0], config.fallbackModel(), e.getMessage());
                    hookService.executeNotification("warn",
                            "Model fallback: " + currentModel[0] + " → " + config.fallbackModel());
                    session.discard();
                    // 为 orphan tool_use 生成 synthetic results
                    List<ContentBlock.ToolUseBlock> orphanBlocks =
                            extractToolUseBlocks(collector.buildAssistantMessage());
                    if (!orphanBlocks.isEmpty()) {
                        List<Message> syntheticResults = generateSyntheticResults(
                                orphanBlocks, session, "Model fallback triggered");
                        state.addMessages(syntheticResults);
                    }
                    currentModel[0] = config.fallbackModel();
                    // 移除 thinking blocks 防止跨模型 API 400
                    stripThinkingBlocks(state);
                    handler.onTurnEnd(turn, "fallback");
                    continue;
                }
                throw e;
            }

            // ===== Step 4: 收集 API 响应 =====
            log.info("[DIAG-TOOL] Turn {} Step4: streamChat returned, building AssistantMessage...", turn);
            Message.AssistantMessage assistantMessage = collector.buildAssistantMessage();
            state.addMessage(assistantMessage);
            handler.onAssistantMessage(assistantMessage);

            // ===== 事务边界: 开始 =====
            String txSessionId = state.getToolUseContext() != null
                    ? state.getToolUseContext().sessionId() : null;
            if (txSessionId != null) {
                fileHistoryService.beginTransaction(
                        txSessionId, assistantMessage.uuid(), state.getMessages().size());
            }

            // ★ 每轮关键日志: 模型、stopReason、工具调用、token 用量
            log.info("Turn {} 完成: model={}, stopReason={}, contentBlocks={}, usage={}",
                    turn, effectiveModel,
                    assistantMessage.stopReason(),
                    assistantMessage.content() != null ? assistantMessage.content().size() : 0,
                    assistantMessage.usage() != null ? assistantMessage.usage().totalTokens() : 0);

            if (assistantMessage.usage() != null) {
                totalUsage = totalUsage.add(assistantMessage.usage());
                handler.onUsage(assistantMessage.usage());
            }

            // ===== Abort 检查（必须在 Step 5 之前）=====
            List<ContentBlock.ToolUseBlock> toolUseBlocks = extractToolUseBlocks(assistantMessage);

            if (aborted.get()) {
                // FIX-02: 完整 abort 处理
                session.discard();

                // 收集已完成的工具结果
                List<StreamingToolExecutor.TrackedTool> completed = session.yieldCompleted();
                for (StreamingToolExecutor.TrackedTool tt : completed) {
                    ContentBlock.ToolResultBlock resultBlock = new ContentBlock.ToolResultBlock(
                            tt.getToolUseId(), tt.getResult().content(), tt.getResult().isError());
                    state.addMessage(buildToolResultMessage(resultBlock));
                }

                // 为所有未完成的 tool_use 生成 synthetic error results
                Set<String> completedIds = completed.stream()
                        .map(StreamingToolExecutor.TrackedTool::getToolUseId)
                        .collect(Collectors.toSet());
                for (ContentBlock.ToolUseBlock block : toolUseBlocks) {
                    if (!completedIds.contains(block.id())) {
                        ContentBlock.ToolResultBlock synthetic = new ContentBlock.ToolResultBlock(
                                block.id(),
                                "<tool_use_error>Interrupted by user</tool_use_error>",
                                true);
                        state.addMessage(buildToolResultMessage(synthetic));
                    }
                }

                // 注入用户中断消息（非 submit-interrupt 且非 session 断连时）
                AbortReason abortReason = state.getAbortReason() != null
                        ? state.getAbortReason() : AbortReason.USER_INTERRUPT;
                if (abortReason != AbortReason.SUBMIT_INTERRUPT
                        && abortReason != AbortReason.SESSION_DISCONNECTED) {
                    Message.UserMessage interruptMsg = new Message.UserMessage(
                            UUID.randomUUID().toString(), Instant.now(),
                            List.of(new ContentBlock.TextBlock(
                                    "[User interrupted the assistant's response]")),
                            null, null);
                    state.addMessage(interruptMsg);
                }

                handler.onTurnEnd(turn, "aborted");
                break;
            }

            // ===== Step 5: 消费工具结果（流式并行执行已在 StreamCollector 中启动）=====
            if (!toolUseBlocks.isEmpty()) {
                // 为每个 ToolUseBlock 发送完整 input 到前端（触发 tool_use_input 消息）
                for (ContentBlock.ToolUseBlock block : toolUseBlocks) {
                    handler.onToolUseComplete(block.id(), block);
                }

                // ★ 工具优先级调度：按优先级排序工具调用（用于日志/监控，实际执行已在流式中启动）
                List<ContentBlock.ToolUseBlock> sortedBlocks = toolPriorityScheduler.sortByPriority(
                        toolUseBlocks, ContentBlock.ToolUseBlock::name);
                if (!sortedBlocks.equals(toolUseBlocks)) {
                    log.debug("Tool priority reorder detected: original={}, sorted={}",
                            toolUseBlocks.stream().map(ContentBlock.ToolUseBlock::name).toList(),
                            sortedBlocks.stream().map(ContentBlock.ToolUseBlock::name).toList());
                }

                List<Message> toolResults = consumeToolResults(session, handler, aborted, toolUseBlocks);
                state.addMessages(toolResults);

                // ★ 工具调用追踪：记录每次工具执行结果
                for (Message toolResultMsg : toolResults) {
                    if (toolResultMsg instanceof Message.UserMessage um && um.content() != null) {
                        for (ContentBlock block : um.content()) {
                            if (block instanceof ContentBlock.ToolResultBlock trb) {
                                // 找到对应的 toolUseBlock 以获取工具名
                                String toolName = toolUseBlocks.stream()
                                        .filter(tb -> tb.id().equals(trb.toolUseId()))
                                        .map(ContentBlock.ToolUseBlock::name)
                                        .findFirst().orElse("unknown");
                                tracker.record(toolName, !trb.isError(),
                                        trb.isError() ? trb.content() : null);
                            }
                        }
                    }
                }

                // ★ 新增：获取工具执行后更新的 context（contextModifier 传播）
                ToolUseContext updatedContext = session.getCurrentContext();
                if (updatedContext != null) {
                    state.setToolUseContext(updatedContext);
                }

                // ★ P2: 工具参数错误纠错 — 当工具执行结果是必填字段缺失或参数损坏时，
                // 注入纠错指令引导 LLM 重新生成完整的工具调用。
                for (Message toolResultMsg : toolResults) {
                    if (toolResultMsg instanceof Message.UserMessage um && um.content() != null) {
                        for (ContentBlock block : um.content()) {
                            if (block instanceof ContentBlock.ToolResultBlock trb
                                    && trb.isError() && trb.content() != null
                                    && (trb.content().contains("Required field") || trb.content().contains("required parameter"))) {
                                String failedToolName = toolUseBlocks.stream()
                                        .filter(tb -> tb.id().equals(trb.toolUseId()))
                                        .map(ContentBlock.ToolUseBlock::name)
                                        .findFirst().orElse("unknown");
                                String correctionPrompt = "Your " + failedToolName + " tool call failed because parameters were incomplete or corrupted. "
                                        + "Error: " + trb.content() + ". "
                                        + "Please carefully regenerate the complete tool call with ALL required fields properly filled.";
                                state.addMessage(new Message.UserMessage(
                                        UUID.randomUUID().toString(),
                                        Instant.now(),
                                        List.of(new ContentBlock.TextBlock(correctionPrompt)),
                                        null, null));
                                log.info("[DIAG-TOOL] Injected parameter-correction prompt for tool '{}', toolId={}",
                                        failedToolName, trb.toolUseId());
                                break; // 只处理第一个参数错误
                            }
                        }
                    }
                }
            }

            // ═══════════════════════════════════════════════════════════════
            // Step 5.5: Self-Correction Detection (自纠错检测)
            // ═══════════════════════════════════════════════════════════════
            if (featureFlagService.isEnabled("SELF_CORRECTION_LOOP")) {
                List<Message> currentMessages = state.getMessages();
                // 从列表末尾向前搜索最近的AssistantMessage
                Message.AssistantMessage assistantMsg = null;
                for (int i = currentMessages.size() - 1; i >= 0; i--) {
                    if (currentMessages.get(i) instanceof Message.AssistantMessage am) {
                        assistantMsg = am;
                        break;
                    }
                }
                if (assistantMsg != null) {
                    for (ContentBlock block : assistantMsg.content()) {
                        if (block instanceof ContentBlock.ToolUseBlock toolUse
                                && "Bash".equals(toolUse.name())) {
                            // 在toolResultMessage中查找对应的ToolResultBlock
                            String toolOutput = findToolResultContent(toolUse.id(), currentMessages);
                            if (toolOutput != null && !toolOutput.isEmpty()) {
                                // shouldAbort检查：修复是否引入新错误
                                if (state.getPreviousToolOutput() != null
                                        && selfCorrectionLoop.shouldAbort(toolOutput, state.getPreviousToolOutput())) {
                                    log.info("[SELF-CORRECTION] Aborting: fix introduced new errors");
                                    state.resetCorrectionAttempts();
                                    state.setPreviousToolOutput(null);
                                    break;
                                }
                                // 检测错误并生成修复指令
                                String repoName = extractRepoNameFromContext(state);
                                Optional<CorrectionInstruction> correction =
                                    selfCorrectionLoop.detectAndPrepareCorrection(
                                        toolOutput, state.getCorrectionAttempts(), repoName);
                                if (correction.isPresent()) {
                                    log.info("[SELF-CORRECTION] Injecting correction attempt #{}",
                                        state.getCorrectionAttempts() + 1);
                                    state.addMessage(new Message.UserMessage(
                                        UUID.randomUUID().toString(),
                                        Instant.now(),
                                        List.of(new ContentBlock.TextBlock(correction.get().instruction())),
                                        null, null));
                                    state.incrementCorrectionAttempts();
                                    state.setPreviousToolOutput(toolOutput);
                                    break; // 只处理第一个Bash错误
                                }
                            }
                        }
                    }
                }
            }

            // ===== 事务边界: 提交 =====
            if (txSessionId != null) {
                fileHistoryService.commitTransaction(txSessionId);
            }

            // ===== Step 6: 继续/终止判定（策略模式）=====
            String stopReason = assistantMessage.stopReason();

            // 构建 LoopContext 供终止策略评估
            long tokenBudgetValue = config.tokenBudget() != null ? config.tokenBudget() : 0L;
            LoopContext loopContext = new LoopContext(
                    turn,
                    config.maxTurns(),
                    tracker.getConsecutiveErrors(),
                    toolUseBlocks.size(),
                    !toolUseBlocks.isEmpty(),
                    stopReason,
                    totalUsage.totalTokens(),
                    tokenBudgetValue,
                    tracker.getRecentRecords(5)
            );

            TerminationDecision decision = terminationStrategy.evaluate(loopContext);
            log.debug("TerminationStrategy decision: {} (turn={}, errors={}, stopReason={})",
                    decision, turn, tracker.getConsecutiveErrors(), stopReason);

            // 6a: 策略决定终止（成功/预算/错误）
            if (decision == TerminationDecision.TERMINATE_BUDGET) {
                log.warn("终止: Token 预算耗尽 (used={}, budget={})",
                        totalUsage.totalTokens(), tokenBudgetValue);
                handler.onTurnEnd(turn, "token_budget_exhausted");
                break;
            }

            if (decision == TerminationDecision.TERMINATE_ERROR) {
                log.warn("终止: 达到有效最大轮次 (turn={}, maxTurns={}, errors={})",
                        turn, config.maxTurns(), tracker.getConsecutiveErrors());
                handler.onTurnEnd(turn, "max_turns");
                break;
            }

            if (decision == TerminationDecision.REQUEST_USER_INPUT) {
                log.warn("请求用户输入: 最近 5 次工具调用全部失败");
                handler.onSystemMessage(new Message.SystemMessage(
                        UUID.randomUUID().toString(), Instant.now(),
                        "Multiple consecutive tool failures detected. Waiting for user guidance.",
                        SystemMessageType.WARNING));
                handler.onTurnEnd(turn, "request_user_input");
                break;
            }

            if (decision == TerminationDecision.SWITCH_STRATEGY) {
                log.warn("切换恢复策略: 连续错误达到阈值 (errors={})",
                        tracker.getConsecutiveErrors());
                // 注入恢复提示给 LLM
                Message.UserMessage recoveryHint = new Message.UserMessage(
                        UUID.randomUUID().toString(), Instant.now(),
                        List.of(new ContentBlock.TextBlock(
                                "[System] Multiple consecutive errors detected. " +
                                "Please try a different approach or simplify your current task.")),
                        null, null);
                state.addMessage(recoveryHint);
                handler.onTurnEnd(turn, "switch_strategy");
                continue;
            }

            // 6b: TERMINATE_SUCCESS — 执行 stopHooks 后终止
            if (decision == TerminationDecision.TERMINATE_SUCCESS) {
                // 
                boolean isApiError = "api_error".equals(assistantMessage.stopReason())
                        || (assistantMessage.content() != null
                            && assistantMessage.content().size() == 1
                            && assistantMessage.content().getFirst() instanceof ContentBlock.TextBlock tb
                            && tb.text() != null
                            && tb.text().startsWith("<api_error>"));

                if (!isApiError && !state.isStopHookActive()
                        && state.getToolUseContext() != null) {
                    try {
                        HookRegistry.StopHookResult stopResult = hookService.executeStopHooks(
                                state.getMessages(),
                                state.getToolUseContext().sessionId());

                        // preventContinuation → 直接终止
                        if (stopResult.preventContinuation()) {
                            handler.onTurnEnd(turn, "stop_hook_prevented");
                            break;
                        }

                        // blockingErrors → 注入错误消息继续循环
                        if (stopResult.hasBlockingErrors()) {
                            for (String errorMsg : stopResult.blockingErrors()) {
                                Message.UserMessage errorMessage = new Message.UserMessage(
                                        UUID.randomUUID().toString(), Instant.now(),
                                        List.of(new ContentBlock.TextBlock(errorMsg)),
                                        null, null);
                                state.addMessage(errorMessage);
                            }
                            state.resetRecoveryCount();
                            state.setStopHookActive(true);
                            handler.onTurnEnd(turn, "stop_hook_blocking");
                            continue; // 继续循环
                        }
                    } catch (Exception e) {
                        log.warn("Stop hook execution failed: {}", e.getMessage());
                        handler.onSystemMessage(new Message.SystemMessage(
                                UUID.randomUUID().toString(), Instant.now(),
                                "Stop hook failed: " + e.getMessage(),
                                SystemMessageType.WARNING));
                    }
                }

                // Token Budget 续写检查 
                if (tokenBudgetTracker != null) {
                    int globalTurnTokens = totalUsage.outputTokens();
                    String agentId = state.getToolUseContext() != null
                            && state.getToolUseContext().nestingDepth() > 0
                            ? "subagent-" + state.getToolUseContext().nestingDepth() : null;
                    TokenBudgetTracker.Decision budgetDecision = tokenBudgetTracker.check(
                            agentId, config.tokenBudget(), globalTurnTokens);

                    if (budgetDecision instanceof TokenBudgetTracker.ContinueDecision cont) {
                        log.info("Token budget continuation #{}: {}%",
                                cont.continuationCount(), cont.pct());
                        Message.UserMessage nudgeMsg = new Message.UserMessage(
                                UUID.randomUUID().toString(), Instant.now(),
                                List.of(new ContentBlock.TextBlock(cont.nudgeMessage())),
                                null, null);
                        state.addMessage(nudgeMsg);

                        // ★ WebSocket 推送 token_budget_nudge 到前端
                        handler.onTokenBudgetNudge(cont.pct(), cont.turnTokens(), cont.budget());

                        state.setHasAttemptedReactiveCompact(false);
                        handler.onTurnEnd(turn, "token_budget_continuation");
                        continue;
                    }
                }

                // ★ 新增：检查后台代理是否仍在运行
                if (backgroundAgentTracker != null
                        && featureFlagService.isEnabled("BACKGROUND_AGENT_WAIT")) {
                    String bgSessionId = state.getToolUseContext().sessionId();
                    List<String> activeAgentIds = backgroundAgentTracker.getActiveAgentIds(bgSessionId);

                    if (!activeAgentIds.isEmpty()) {
                        handler.onTurnEnd(turn, "waiting_for_background_agents");

                        // 等待所有后台代理完成（带超时和 abort 信号）
                        Duration waitTimeout = Duration.ofMinutes(
                                agentTimeoutConfig.getMaxWaitMinutes());
                        AbortContext abortCtx = abortContexts.get(bgSessionId);

                        boolean allDone = backgroundAgentTracker.awaitAllAgents(
                                bgSessionId, waitTimeout, abortCtx);

                        if (allDone) {
                            // 收集结果并注入上下文
                            List<BackgroundAgentTracker.AgentStatus> completed =
                                    backgroundAgentTracker.listActive(bgSessionId);
                            // listActive 返回 running 的，此时应已全部完成，获取全部记录
                            List<BackgroundAgentTracker.AgentStatus> allAgents =
                                    activeAgentIds.stream()
                                            .map(backgroundAgentTracker::getStatus)
                                            .filter(java.util.Objects::nonNull)
                                            .toList();
                            String resultSummary = formatAgentResults(allAgents);

                            // 注入为 user message 让 LLM 整合结果
                            Message.UserMessage agentResultMsg = new Message.UserMessage(
                                    UUID.randomUUID().toString(), Instant.now(),
                                    List.of(new ContentBlock.TextBlock(
                                            "[System] Background agents completed:\n" + resultSummary)),
                                    null, null);
                            state.addMessage(agentResultMsg);
                            continue;  // 继续循环让 LLM 整合结果
                        }
                        // 超时或被 abort，正常退出
                    }
                }

                handler.onTurnEnd(turn, stopReason);
                break;
            }

            // 6b: max_tokens 恢复
            if ("max_tokens".equals(stopReason) || "length".equals(stopReason)) {
                if (state.getMaxOutputTokensRecoveryCount()
                        >= QueryConfig.MAX_OUTPUT_TOKENS_RECOVERY_LIMIT) {
                    log.warn("max_tokens 恢复次数已达上限，终止循环");
                    handler.onTurnEnd(turn, stopReason);
                    break;
                }
                // 尝试 escalate
                if (state.getMaxTokensOverride() == null) {
                    state.setMaxTokensOverride(QueryConfig.ESCALATED_MAX_TOKENS);
                    log.info("升级 maxTokens: {} → {}",
                            config.maxTokens(), QueryConfig.ESCALATED_MAX_TOKENS);
                } else {
                    // 恢复: 注入续写消息
                    state.incrementRecoveryCount();
                    Message.UserMessage recovery = new Message.UserMessage(
                            UUID.randomUUID().toString(), Instant.now(),
                            List.of(new ContentBlock.TextBlock(MAX_TOKENS_RECOVERY_MESSAGE)),
                            null, null);
                    state.addMessage(recovery);
                }
                handler.onTurnEnd(turn, stopReason);
                continue;
            }

            // 6c: 用户中断（二次检查，工具执行后可能 aborted）
            if (aborted.get()) {
                handler.onTurnEnd(turn, "aborted");
                break;
            }

            // 6d: 超过 maxTurns（安全网，策略已通过 TERMINATE_ERROR 处理动态 maxTurns）
            if (turn >= config.maxTurns()) {
                log.warn("安全网触发: 达到硬性最大循环轮次: {}", config.maxTurns());
                handler.onTurnEnd(turn, "max_turns");
                break;
            }

            // 6e: 有工具结果 → 继续循环
            handler.onTurnEnd(turn, stopReason);

            // ===== Step 7: 工具使用摘要注入 =====
            // 处理过大的工具结果：截断或标记旧结果可清理
            List<Message> currentMessages = state.getMessages();
            List<Message> processedMessages = toolResultSummarizer
                    .processToolResults(currentMessages, turn);
            if (processedMessages != currentMessages) {
                state.setMessages(processedMessages);
            }

            // ===== Step 8: 状态更新 → 回到 Step 1 =====
        }

        return totalUsage;
    }

    // ==================== Step 1: 压缩 ====================

    /**
     * P1-16: ThinkingConfig 降级检查 — 如果 Provider 不支持 thinking，自动降级并一次性通知。
         */
    private ThinkingConfig resolveThinking(ThinkingConfig config, LlmProvider provider,
                                            String model, QueryMessageHandler handler,
                                            QueryLoopState state) {
        if (config == null || !config.requiresThinkingSupport()) {
            return config;
        }

        if (!provider.supportsThinking(model)) {
            // Provider/Model 不支持 thinking → 降级为 Disabled
            String key = "thinking-downgrade-" + provider.getProviderName() + "-" + model;
            if (notifiedThinkingDowngrades.add(key)) {
                String msg = String.format("Extended thinking disabled: model %s on %s does not support it",
                        model, provider.getProviderName());
                log.info(msg);
                hookService.executeNotification("warn", msg);
                handler.onSystemMessage(new Message.SystemMessage(
                        java.util.UUID.randomUUID().toString(), Instant.now(),
                        msg, SystemMessageType.WARNING));
            }
            return new ThinkingConfig.Disabled();
        }

        // Adaptive 动态预算计算 — 基于上一轮上下文指标
        if (config instanceof ThinkingConfig.Adaptive) {
            int budget = thinkingBudgetCalculator.calculateBudget(
                    state.getContextMetrics());
            return new ThinkingConfig.Adaptive(budget);
        }

        return config;
    }

    private void tryAutoCompact(QueryConfig config, QueryLoopState state,
                                 QueryMessageHandler handler) {
        try {
            if (compactService.shouldAutoCompactBufferBased(
                    state.getMessages(), config.contextWindow())) {
                log.info("触发自动压缩: 当前消息数={}", state.getMessages().size());

                // 先尝试 SMC (Session Memory Compaction)
                CompactService.CompactResult smcResult = compactService.trySessionMemoryCompaction(
                        state.getMessages(), config.contextWindow());
                if (smcResult.skipReason() == null) {
                    List<Message> compactedWithFiles = compactService.reInjectFilesAfterCompact(
                            smcResult.compactedMessages(),
                            state.getToolUseContext() != null ? state.getToolUseContext().workingDirectory() : null);
                    state.setMessages(compactedWithFiles);
                    state.resetAutoCompactFailures();
                    handler.onCompactEvent("smc_compact",
                            smcResult.beforeTokens(), smcResult.afterTokens());
                    log.info("SMC 压缩完成: {}", smcResult.summary());
                    return;
                }

                // SMC 不足时回退到完整压缩
                CompactService.CompactResult result = compactService.compact(
                        state.getMessages(), config.contextWindow(), false);

                if (result.skipReason() == null) {
                    List<Message> compactedWithFiles = compactService.reInjectFilesAfterCompact(
                            result.compactedMessages(),
                            state.getToolUseContext() != null ? state.getToolUseContext().workingDirectory() : null);
                    state.setMessages(compactedWithFiles);
                    state.resetAutoCompactFailures();
                    handler.onCompactEvent("auto_compact",
                            result.beforeTokens(), result.afterTokens());
                    hookService.executeNotification("info", "Context auto-compacted: "
                            + result.beforeTokens() + " \u2192 " + result.afterTokens() + " tokens");
                    log.info("自动压缩完成: {}", result.summary());
                }
            }
        } catch (Exception e) {
            log.error("自动压缩失败", e);
            state.incrementAutoCompactFailures();
        }
    }

    private boolean tryReactiveCompact(QueryConfig config, QueryLoopState state,
                                        QueryMessageHandler handler) {
        if (state.hasAttemptedReactiveCompact()) {
            log.error("反应式压缩已尝试过，拒绝重试以防死亡螺旋");
            return false;
        }

        log.warn("触发反应式压缩 (413 prompt_too_long)");
        state.setHasAttemptedReactiveCompact(true);
        compactMetrics.recordRecoveryAttempt();
        long startTime = System.currentTimeMillis();

        try {
            CompactService.CompactResult result = compactService.reactiveCompact(
                    state.getMessages(), config.contextWindow(), false);
            if (result.skipReason() == null) {
                state.setMessages(result.compactedMessages());
                handler.onCompactEvent("reactive_compact",
                        result.beforeTokens(), result.afterTokens());
                compactMetrics.recordRecoverySuccess(
                        result.compressionRatio(),
                        System.currentTimeMillis() - startTime);
                return true;
            }
        } catch (Exception e) {
            log.error("反应式压缩失败", e);
        }
        return false;
    }

    /**
     * Context-collapse drain — 尝试更激进的压缩恢复 413。
     * 简化版: 使用 contextWindow*0.5 作为目标重新压缩。
         */
    private int tryContextCollapseDrain(QueryConfig config, QueryLoopState state,
                                         QueryMessageHandler handler) {
        compactMetrics.recordRecoveryAttempt();
        long startTime = System.currentTimeMillis();
        try {
            CompactService.CompactResult result = compactService.compact(
                    state.getMessages(),
                    (int)(config.contextWindow() * 0.5),
                    true);

            if (result.skipReason() == null) {
                state.setMessages(result.compactedMessages());
                handler.onCompactEvent("context_collapse_drain",
                        result.beforeTokens(), result.afterTokens());
                compactMetrics.recordRecoverySuccess(
                        result.compressionRatio(),
                        System.currentTimeMillis() - startTime);
                log.info("Context-collapse drain: {} → {} tokens",
                        result.beforeTokens(), result.afterTokens());
                return result.beforeTokens() - result.afterTokens();
            }
        } catch (Exception e) {
            log.warn("Context-collapse drain failed: {}", e.getMessage());
        }
        return 0;
    }

    // ==================== Step 5: 消费工具结果 ====================

    /**
     * 从 ExecutionSession 消费所有工具结果（按原始顺序）。
         */
    private List<Message> consumeToolResults(
            StreamingToolExecutor.ExecutionSession session,
            QueryMessageHandler handler,
            AtomicBoolean aborted,
            List<ContentBlock.ToolUseBlock> toolUseBlocks) {
        List<Message> results = new ArrayList<>();

        // ★ Watchdog：基于 session 中最长工具声明超时的 watchdogMultiplier 倍
        // 正常情况下不应触发，仅作灾难检测
        // tool-consume-max-wait-minutes>0 时使用固定覆盖值（调试用），否则使用动态计算
        long maxDurationMs = session.getMaxExpectedDurationMs();
        double multiplier = agentTimeoutConfig.getWatchdogMultiplier();
        long dynamicWatchdogMs = (long)(maxDurationMs * multiplier);
        // 溢出保护：硬上界 2 小时，防止极端场景下 Watchdog 失效
        if (dynamicWatchdogMs <= 0 || dynamicWatchdogMs > TimeUnit.HOURS.toMillis(2)) {
            log.warn("Watchdog timeout clamped: calculated={}ms (maxDuration={}ms, multiplier={}), clamping to 2h",
                    dynamicWatchdogMs, maxDurationMs, multiplier);
            dynamicWatchdogMs = TimeUnit.HOURS.toMillis(2);
        }
        long fixedOverrideMs = agentTimeoutConfig.getToolConsumeMaxWaitMinutes() > 0
                ? TimeUnit.MINUTES.toMillis(agentTimeoutConfig.getToolConsumeMaxWaitMinutes())
                : -1L;
        final long watchdogMs = fixedOverrideMs > 0 ? fixedOverrideMs : dynamicWatchdogMs;
        long startTime = System.currentTimeMillis();

        while (!session.isAllCompleted()) {
            // 检查 abort 信号
            if (aborted.get()) {
                session.discard();
                break;
            }

            // Watchdog 检查（应永远不触发）
            if (System.currentTimeMillis() - startTime > watchdogMs) {
                log.error("WATCHDOG FIRED: tool executor contract violated after {}ms. "
                                + "Pending tools: {}",
                        watchdogMs, session.getPendingToolIds());
                session.notifyWatchdogFired();
                session.discard();
                break;
            }

            List<StreamingToolExecutor.TrackedTool> yielded = session.yieldCompleted();
            for (StreamingToolExecutor.TrackedTool tt : yielded) {
                ContentBlock.ToolResultBlock resultBlock = new ContentBlock.ToolResultBlock(
                        tt.getToolUseId(), tt.getResult().content(), tt.getResult().isError());
                handler.onToolResult(tt.getToolUseId(), resultBlock);
                results.add(buildToolResultMessage(resultBlock));
            }

            if (!session.isAllCompleted()) {
                // ★ 条件等待，替代固定 50ms 轮询
                session.awaitAnyCompletion(200, TimeUnit.MILLISECONDS);
            }
        }

        // 最终一次 yield
        for (StreamingToolExecutor.TrackedTool tt : session.yieldCompleted()) {
            ContentBlock.ToolResultBlock resultBlock = new ContentBlock.ToolResultBlock(
                    tt.getToolUseId(), tt.getResult().content(), tt.getResult().isError());
            handler.onToolResult(tt.getToolUseId(), resultBlock);
            results.add(buildToolResultMessage(resultBlock));
        }

        // ★★★ 三层保护第三层：兜底补全 —— 确保每个 tool_use 都有 tool_result
        Set<String> collectedIds = results.stream()
                .filter(m -> m instanceof Message.UserMessage)
                .flatMap(m -> ((Message.UserMessage) m).content().stream())
                .filter(b -> b instanceof ContentBlock.ToolResultBlock)
                .map(b -> ((ContentBlock.ToolResultBlock) b).toolUseId())
                .collect(Collectors.toSet());

        for (ContentBlock.ToolUseBlock block : toolUseBlocks) {
            if (!collectedIds.contains(block.id())) {
                log.warn("Generating synthetic error for orphaned tool_use: id={}, name={}",
                        block.id(), block.name());
                session.notifySyntheticError();
                ContentBlock.ToolResultBlock synthetic = new ContentBlock.ToolResultBlock(
                        block.id(),
                        "<tool_use_error>Tool execution did not complete: "
                                + "executor contract violated or watchdog timeout</tool_use_error>",
                        true);
                handler.onToolResult(block.id(), synthetic);
                results.add(buildToolResultMessage(synthetic));
            }
        }

        return results;
    }

    /**
     * 为所有未产出 tool_result 的 tool_use 生成 synthetic error 结果。
         */
    private List<Message> generateSyntheticResults(
            List<ContentBlock.ToolUseBlock> toolUseBlocks,
            StreamingToolExecutor.ExecutionSession session,
            String reason) {
        Set<String> completedIds = new HashSet<>();
        for (StreamingToolExecutor.TrackedTool tt : session.yieldCompleted()) {
            completedIds.add(tt.getToolUseId());
        }

        List<Message> results = new ArrayList<>();
        for (ContentBlock.ToolUseBlock block : toolUseBlocks) {
            if (completedIds.contains(block.id())) {
                continue;
            }
            ContentBlock.ToolResultBlock synthetic = new ContentBlock.ToolResultBlock(
                    block.id(),
                    "<tool_use_error>" + reason + "</tool_use_error>",
                    true);
            results.add(buildToolResultMessage(synthetic));
        }
        return results;
    }

    /**
     * 移除消息历史中的 thinking blocks，防止跨模型 API 400。
         */
    private void stripThinkingBlocks(QueryLoopState state) {
        List<Message> messages = state.getMessages();
        List<Message> cleaned = new ArrayList<>();
        for (Message msg : messages) {
            if (msg instanceof Message.AssistantMessage assistant && assistant.content() != null) {
                List<ContentBlock> filtered = assistant.content().stream()
                        .filter(b -> !(b instanceof ContentBlock.ThinkingBlock)
                                && !(b instanceof ContentBlock.RedactedThinkingBlock))
                        .toList();
                if (filtered.size() != assistant.content().size()) {
                    // 如果过滤后为空，添加一个空文本块防止 API 400
                    if (filtered.isEmpty()) {
                        filtered = List.of(new ContentBlock.TextBlock(""));
                    }
                    cleaned.add(new Message.AssistantMessage(
                            assistant.uuid(), assistant.timestamp(), filtered,
                            assistant.stopReason(), assistant.usage()));
                } else {
                    cleaned.add(msg);
                }
            } else {
                cleaned.add(msg);
            }
        }
        state.setMessages(cleaned);
    }

    // ==================== 媒体恢复辅助方法 ====================

    /**
     * 判断LLM错误是否与媒体内容（图片/PDF/文件）相关
     */
    private boolean isMediaRelatedError(LlmApiException e) {
        if (e.getMessage() == null) return false;
        String msg = e.getMessage().toLowerCase();
        return (msg.contains("image") && (msg.contains("invalid") || msg.contains("too_large")))
                || msg.contains("file_too_large") || msg.contains("invalid_image")
                || msg.contains("could not process image")
                || msg.contains("media_type_not_supported");
    }

    /**
     * 从消息历史中移除媒体块（图片/文件附件），保留文本内容
     */
    private boolean tryStripMediaBlocks(QueryLoopState state, QueryMessageHandler handler) {
        List<Message> messages = state.getMessages();
        boolean stripped = false;
        int originalBlockCount = 0;
        int filteredBlockCount = 0;

        List<Message> cleaned = new ArrayList<>(messages.size());
        for (Message msg : messages) {
            if (msg instanceof Message.UserMessage userMsg && userMsg.content() != null) {
                List<ContentBlock> filteredBlocks = userMsg.content().stream()
                        .filter(block -> !(block instanceof ContentBlock.ImageBlock))
                        .toList();

                if (filteredBlocks.size() < userMsg.content().size()) {
                    stripped = true;
                    originalBlockCount += userMsg.content().size();
                    filteredBlockCount += filteredBlocks.size();
                    if (filteredBlocks.isEmpty()) {
                        filteredBlocks = List.of(new ContentBlock.TextBlock(
                                "[Media content was present but removed to reduce context size. " +
                                "The original content included image(s) that exceeded size limits.]"));
                    }
                    cleaned.add(new Message.UserMessage(
                            userMsg.uuid(), userMsg.timestamp(), filteredBlocks,
                            userMsg.toolUseResult(), userMsg.sourceToolAssistantUUID()));
                } else {
                    cleaned.add(msg);
                }
            } else {
                cleaned.add(msg);
            }
        }

        if (stripped) {
            state.setMessages(cleaned);
            handler.onCompactEvent("media_strip", originalBlockCount, filteredBlockCount);
            log.info("Stripped media blocks from message history for recovery: {} blocks → {} blocks",
                    originalBlockCount, filteredBlockCount);
        }
        return stripped;
    }

    // ==================== 辅助方法 ====================

    /**
     * 从消息历史中查找指定toolUseId对应的工具结果内容
     */
    private String findToolResultContent(String toolUseId, List<Message> messages) {
        // 从后向前查找包含对应ToolResultBlock的UserMessage
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (msg instanceof Message.UserMessage userMsg && userMsg.content() != null) {
                for (ContentBlock block : userMsg.content()) {
                    if (block instanceof ContentBlock.ToolResultBlock trb
                            && toolUseId.equals(trb.toolUseId())) {
                        return trb.content();
                    }
                }
            }
        }
        return null;
    }

    /**
     * 从 ToolUseContext.workingDirectory 推导 SWE-bench 仓库名。
     * 约定：路径末尾目录名形如 "owner__repo" 时转为 "owner/repo"，
     * 否则返回 "unknown"（自动回落到 MAX_ATTEMPTS_DEFAULT=3）。
     */
    private String extractRepoNameFromContext(QueryLoopState state) {
        if (state == null || state.getToolUseContext() == null) {
            return "unknown";
        }
        String wd = state.getToolUseContext().workingDirectory();
        if (wd == null || wd.isBlank()) {
            return "unknown";
        }
        String trimmed = wd.endsWith("/") || wd.endsWith("\\")
                ? wd.substring(0, wd.length() - 1) : wd;
        int idx = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        String last = idx >= 0 ? trimmed.substring(idx + 1) : trimmed;
        int sep = last.indexOf("__");
        if (sep <= 0 || sep >= last.length() - 2) {
            return "unknown";
        }
        return last.substring(0, sep) + "/" + last.substring(sep + 2);
    }

    private List<ContentBlock.ToolUseBlock> extractToolUseBlocks(Message.AssistantMessage message) {
        if (message.content() == null) return List.of();
        return message.content().stream()
                .filter(b -> b instanceof ContentBlock.ToolUseBlock)
                .map(b -> (ContentBlock.ToolUseBlock) b)
                .toList();
    }

    private Tool findTool(String name, List<Tool> tools) {
        return tools.stream()
                .filter(t -> t.getName().equals(name)
                        || t.getAliases().contains(name))
                .findFirst()
                .orElse(null);
    }

    private Message.UserMessage buildToolResultMessage(ContentBlock.ToolResultBlock result) {
        return new Message.UserMessage(
                UUID.randomUUID().toString(), Instant.now(),
                List.of(result), result.content(), result.toolUseId());
    }

    // ==================== 流式收集器 ====================

    /**
     * 流式收集器 — 将 StreamChatCallback 事件收集为 AssistantMessage。
     * 持有 ExecutionSession 引用，在 flushToolBlock 时立即启动工具并行执行。
     */
    private static class StreamCollector implements StreamChatCallback {

        private final QueryMessageHandler handler;
        private final StreamingToolExecutor.ExecutionSession session;
        private final List<Tool> tools;
        private final ToolUseContext toolUseContext;
        private final ObjectMapper objectMapper;
        private final List<ContentBlock> contentBlocks = new ArrayList<>();
        private final StringBuilder currentThinking = new StringBuilder();
        private final StringBuilder currentText = new StringBuilder();
        private String currentToolId;
        private String currentToolName;
        private final StringBuilder currentToolInput = new StringBuilder();
        private Usage usage;
        private String stopReason;

        StreamCollector(QueryMessageHandler handler,
                        StreamingToolExecutor.ExecutionSession session,
                        List<Tool> tools,
                        ToolUseContext toolUseContext,
                        ObjectMapper objectMapper) {
            this.handler = handler;
            this.session = session;
            this.tools = tools;
            this.toolUseContext = toolUseContext;
            this.objectMapper = objectMapper;
        }

        @Override
        public void onEvent(LlmStreamEvent event) {
            switch (event) {
                case LlmStreamEvent.TextDelta delta -> {
                    // 首次收到文本时，将已累积的 thinking 内容 flush 为 ThinkingBlock
                    flushThinkingBlock();
                    currentText.append(delta.text());
                    handler.onTextDelta(delta.text());
                }
                case LlmStreamEvent.ThinkingDelta delta -> {
                    currentThinking.append(delta.thinking());
                    handler.onThinkingDelta(delta.thinking());
                }
                case LlmStreamEvent.ToolUseStart start -> {
                    // 结束之前的文本块，并 flush 上一个未完成的 tool block（多工具场景）
                    flushTextBlock();
                    flushToolBlock();
                    currentToolId = start.id();
                    currentToolName = start.name();
                    currentToolInput.setLength(0);
                    handler.onToolUseStart(start.id(), start.name());
                }
                case LlmStreamEvent.ToolInputDelta delta -> {
                    currentToolInput.append(delta.jsonDelta());
                    handler.onToolInputDelta(delta.toolUseId(), delta.jsonDelta());
                }
                case LlmStreamEvent.MessageDelta delta -> {
                    this.usage = delta.usage();
                    this.stopReason = delta.stopReason();
                    log.info("[DIAG-TOOL] MessageDelta: stopReason={}, currentToolId={}", delta.stopReason(), currentToolId);
                    // ★ 修复：不在 MessageDelta 中触发 flushToolBlock()。
                    // Qwen 等 OpenAI 兼容模型可能在最后一批 ToolInputDelta 到达前
                    // 先发送 finish_reason，导致工具参数被截断。
                    // 工具块的 flush 由 BlockStop（Anthropic）或 onComplete（兜底）负责。
                    flushTextBlock();
                }
                case LlmStreamEvent.Error error -> {
                    handler.onError(new LlmApiException(error.message(), error.retryable()));
                }
                // Anthropic 细粒度事件 — 无需额外处理
                case LlmStreamEvent.MessageStart ms -> { /* no-op */ }
                case LlmStreamEvent.TextStart ts -> { /* no-op */ }
                case LlmStreamEvent.ThinkingStart ths -> { /* no-op */ }
                case LlmStreamEvent.BlockStop bs -> {
                    // ★ 流式即时启动：tool_use block 结束时立即 flush 并提交执行
                    // 无需等待 MessageDelta，实现 "收到即启动" 而非 "收集完再启动"
                    log.info("[DIAG-TOOL] BlockStop event: currentToolId={}, currentToolName={}", currentToolId, currentToolName);
                    flushToolBlock();
                }
            }
        }

        @Override
        public void onComplete() {
            log.info("[DIAG-TOOL] onComplete: contentBlocks={}, stopReason={}", contentBlocks.size(), stopReason);
            flushTextBlock();
            flushToolBlock();
        }

        @Override
        public void onError(Throwable error) {
            handler.onError(error);
        }

        private void flushThinkingBlock() {
            if (!currentThinking.isEmpty()) {
                contentBlocks.add(new ContentBlock.ThinkingBlock(currentThinking.toString()));
                currentThinking.setLength(0);
            }
        }

        private void flushTextBlock() {
            flushThinkingBlock();
            if (!currentText.isEmpty()) {
                contentBlocks.add(new ContentBlock.TextBlock(currentText.toString()));
                currentText.setLength(0);
            }
        }

        private void flushToolBlock() {
            if (currentToolId != null) {
                String inputStr = currentToolInput.toString();
                log.info("[DIAG-TOOL] flushToolBlock: toolId={}, toolName={}, inputLen={}, rawInput='{}'",
                        currentToolId, currentToolName, inputStr.length(),
                        inputStr.length() <= 200 ? inputStr : inputStr.substring(0, 200) + "...[truncated]");
                JsonNode inputNode;
                try {
                    inputNode = inputStr.isEmpty()
                            ? objectMapper.createObjectNode()
                            : objectMapper.readTree(inputStr);
                } catch (Exception e) {
                    // ★ P1: 先尝试 JSON 自动补全，再降级到空对象
                    JsonNode recovered = tryRecoverJson(inputStr, e);
                    if (recovered != null) {
                        log.info("[DIAG-TOOL] JSON auto-recovered for tool '{}', toolId={}", currentToolName, currentToolId);
                        inputNode = recovered;
                    } else {
                        log.warn("[DIAG-TOOL] flushToolBlock JSON parse error: toolId={}, inputLen={}, error={}",
                                currentToolId, inputStr.length(), e.getMessage());
                        inputNode = objectMapper.createObjectNode();
                    }
                }
                ContentBlock.ToolUseBlock toolBlock = new ContentBlock.ToolUseBlock(
                        currentToolId, currentToolName, inputNode);
                contentBlocks.add(toolBlock);

                // 立即提交到 StreamingToolExecutor 开始并行执行
                log.info("[DIAG-TOOL] flushToolBlock submit: session={}, discarded={}",
                        session != null, session != null && session.isDiscarded());
                if (session != null && !session.isDiscarded()) {
                    Tool tool = findToolByName(currentToolName);
                    log.info("[DIAG-TOOL] findToolByName({}): found={}", currentToolName, tool != null);
                    if (tool != null) {
                        ToolInput toolInput = ToolInput.fromJsonNode(inputNode);
                        session.addTool(tool, toolInput, currentToolId, toolUseContext);
                        log.info("[DIAG-TOOL] addTool submitted: toolId={}, toolName={}", currentToolId, currentToolName);
                    } else {
                        // 工具未找到 — 直接标记 COMPLETED + error，附带可用工具列表引导恢复
                        log.warn("Tool not found in streaming phase: {}", currentToolName);
                        String availableToolNames = (tools != null)
                                ? tools.stream().map(Tool::getName).collect(Collectors.joining(", "))
                                : "Read, Edit, Write, Bash, Grep, Glob";
                        session.addErrorResult(currentToolId,
                                "<tool_use_error>Error: Tool '" + currentToolName + "' does not exist in this environment. "
                                + "You ONLY have access to these tools: [" + availableToolNames + "]. "
                                + "Do NOT attempt to use '" + currentToolName + "' again. "
                                + "Continue solving the problem using ONLY the available tools listed above.</tool_use_error>");
                    }
                }

                currentToolId = null;
                currentToolName = null;
                currentToolInput.setLength(0);
            }
        }

        /**
         * 尝试修复截断的 JSON：补全引号/括号。
         * 在 flushToolBlock 解析失败时作为降级策略，避免空对象丢失工具参数。
         */
        private JsonNode tryRecoverJson(String input, Exception originalError) {
            String trimmed = input.trim();
            if (trimmed.isEmpty()) return null;

            // 策略1：如果错误是 "expecting closing quote"，尝试补引号+括号
            String errMsg = originalError.getMessage();
            if (errMsg != null && (errMsg.contains("closing") || errMsg.contains("quote") || errMsg.contains("Unexpected end"))) {
                String[] suffixes = {"\"}", "\"}}"  , "\"}}}" };
                for (String suffix : suffixes) {
                    try {
                        return objectMapper.readTree(trimmed + suffix);
                    } catch (Exception ignored) {}
                }
            }

            // 策略2：逐步补全括号
            String[] closers = {"}", "}}", "}}}", "]}", "]}}"};
            for (String closer : closers) {
                try {
                    return objectMapper.readTree(trimmed + closer);
                } catch (Exception ignored) {}
            }

            return null;
        }

        private Tool findToolByName(String name) {
            if (tools == null) return null;
            return tools.stream()
                    .filter(t -> t.getName().equals(name) || t.getAliases().contains(name))
                    .findFirst().orElse(null);
        }

        Message.AssistantMessage buildAssistantMessage() {
            log.info("[DIAG-TOOL] buildAssistantMessage: contentBlocks={}, stopReason={}", contentBlocks.size(), stopReason);
            flushTextBlock();
            flushToolBlock();
            return new Message.AssistantMessage(
                    UUID.randomUUID().toString(), Instant.now(),
                    List.copyOf(contentBlocks),
                    stopReason != null ? stopReason : "end_turn",
                    usage != null ? usage : Usage.zero());
        }
    }

    // ==================== 后台代理结果格式化 ====================

    /**
     * 格式化后台代理的执行结果，用于注入 LLM 上下文。
     */
    private String formatAgentResults(List<BackgroundAgentTracker.AgentStatus> agents) {
        StringBuilder sb = new StringBuilder();
        for (var agent : agents) {
            sb.append("### Agent: ").append(agent.agentId()).append("\n");
            sb.append("Status: ").append(agent.status()).append("\n");
            if ("completed".equals(agent.status()) && agent.outputFile() != null) {
                try {
                    String output = Files.readString(Path.of(agent.outputFile()));
                    // 截断过长输出（保护 token 预算）
                    if (output.length() > 4000) {
                        output = output.substring(0, 4000) + "\n... [truncated]";
                    }
                    sb.append("Output:\n").append(output).append("\n");
                } catch (IOException e) {
                    sb.append("Output: [file read failed: ").append(e.getMessage()).append("]\n");
                }
            } else if (agent.error() != null) {
                sb.append("Error: ").append(agent.error()).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    // ==================== 查询结果 ====================

    /**
     * 查询结果。
     */
    public record QueryResult(
            List<Message> messages,
            Usage totalUsage,
            String stopReason,
            String error,
            int turnCount
    ) {
        public boolean isSuccess() { return error == null; }
    }
}
