package com.aicodeassistant.engine;

import com.aicodeassistant.history.FileHistoryService;
import com.aicodeassistant.hook.HookRegistry;
import com.aicodeassistant.hook.HookService;
import com.aicodeassistant.llm.*;
import com.aicodeassistant.model.*;
import com.aicodeassistant.permission.PermissionPipeline;
import com.aicodeassistant.permission.PermissionRuleRepository;
import com.aicodeassistant.tool.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

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

    /** 单条工具结果最大占上下文窗口的 30% */
    private static final double TOOL_RESULT_BUDGET_RATIO = 0.3;
    /** MicroCompact 保护尾部消息数 */
    private static final int MICRO_COMPACT_PROTECTED_TAIL = 10;

    /** 记录已通知的 thinking 降级 (once-only) */
    private final Set<String> notifiedThinkingDowngrades = ConcurrentHashMap.newKeySet();

    /** 会话级中断上下文 — sessionId → AbortContext */
    private final ConcurrentHashMap<String, AbortContext> abortContexts = new ConcurrentHashMap<>();

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
                       CompactMetrics compactMetrics) {
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
            log.warn("No active AbortContext for sessionId={}", sessionId);
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

        try {
            totalUsage = queryLoop(config, state, handler, aborted);
        } catch (Exception e) {
            log.error("QueryEngine 执行异常", e);
            handler.onError(e);
            return new QueryResult(state.getMessages(), totalUsage,
                    "error", e.getMessage(), state.getTurnCount());
        } finally {
            // P1-04: 确保清理 AbortContext，防止内存泄漏
            if (sessionId != null) {
                abortContexts.remove(sessionId);
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
        TokenBudgetTracker tokenBudgetTracker = config.tokenBudget() != null
                ? new TokenBudgetTracker() : null;

        log.info("[DIAG] queryLoop 进入: model={}, messageCount={}, maxTurns={}, aborted={}",
                config.model(), state.getMessages().size(), config.maxTurns(), aborted.get());

        while (!aborted.get()) {
            state.incrementTurnCount();
            int turn = state.getTurnCount();
            log.info("[DIAG] Turn {} 开始: messageCount={}, model={}", turn, state.getMessages().size(), currentModel[0]);
            handler.onTurnStart(turn);

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
                    int recoveryAttempt = state.getWithheldErrors().size();
                    handler.onRecovery(RecoveryEvent.of413(recoveryAttempt, "collapse drain"));

                    // Phase 1: context-collapse drain (防止重复)
                    if (!"collapse_drain_retry".equals(state.getLastTransitionReason())) {
                        int drained = tryContextCollapseDrain(config, state, handler);
                        if (drained > 0) {
                            state.setLastTransitionReason("collapse_drain_retry");
                            state.clearWithheldErrors();
                            continue;
                        }
                    }

                    // Phase 2: reactive compact (单次 guard)
                    handler.onRecovery(RecoveryEvent.of413(recoveryAttempt, "reactive compact"));
                    if (tryReactiveCompact(config, state, handler)) {
                        state.setLastTransitionReason("reactive_compact_retry");
                        state.clearWithheldErrors();
                        continue;
                    }

                    // 恢复耗尽，释放扣留错误给消费者
                    log.error("413 recovery exhausted: collapse drain failed, reactive compact failed");
                    for (LlmApiException withheld : state.getWithheldErrors()) {
                        handler.onError(withheld);
                    }
                    state.clearWithheldErrors();
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
                List<Message> toolResults = consumeToolResults(session, handler, aborted);
                state.addMessages(toolResults);

                // ★ 新增：获取工具执行后更新的 context（contextModifier 传播）
                ToolUseContext updatedContext = session.getCurrentContext();
                if (updatedContext != null) {
                    state.setToolUseContext(updatedContext);
                }
            }

            // ===== 事务边界: 提交 =====
            if (txSessionId != null) {
                fileHistoryService.commitTransaction(txSessionId);
            }

            // ===== Step 6: 继续/终止判定 =====
            String stopReason = assistantMessage.stopReason();

            // 6a: end_turn 且无工具调用 → 执行 stopHooks 后终止
            // 防御性检查: 同时接受 Anthropic 的 "end_turn" 和 OpenAI 的 "stop"
            if (("end_turn".equals(stopReason) || "stop".equals(stopReason)) && toolUseBlocks.isEmpty()) {
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
                    TokenBudgetTracker.Decision decision = tokenBudgetTracker.check(
                            agentId, config.tokenBudget(), globalTurnTokens);

                    if (decision instanceof TokenBudgetTracker.ContinueDecision cont) {
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

            // 6d: 超过 maxTurns
            if (turn >= config.maxTurns()) {
                log.warn("达到最大循环轮次: {}", config.maxTurns());
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
            AtomicBoolean aborted) {
        List<Message> results = new ArrayList<>();

        // ★ 安全网: 工具结果消费最大等待时间 (10分钟)
        final long TOOL_CONSUME_TIMEOUT_MS = 10 * 60 * 1000L;
        long startTime = System.currentTimeMillis();

        // 轮询等待所有工具完成
        while (!session.isAllCompleted()) {
            // 检查 abort 信号
            if (aborted.get()) {
                session.discard();
                break;
            }

            // ★ 超时保护: 防止工具执行卡死导致无限等待
            if (System.currentTimeMillis() - startTime > TOOL_CONSUME_TIMEOUT_MS) {
                log.error("consumeToolResults timed out after {}ms, discarding remaining tools",
                        TOOL_CONSUME_TIMEOUT_MS);
                session.discard();
                break;
            }

            List<StreamingToolExecutor.TrackedTool> yielded = session.yieldCompleted();
            for (StreamingToolExecutor.TrackedTool tt : yielded) {
                ContentBlock.ToolResultBlock resultBlock = new ContentBlock.ToolResultBlock(
                        tt.getToolUseId(),
                        tt.getResult().content(),
                        tt.getResult().isError());
                handler.onToolResult(tt.getToolUseId(), resultBlock);
                results.add(buildToolResultMessage(resultBlock));
            }

            if (!session.isAllCompleted()) {
                try { Thread.sleep(50); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // 最终一次 yield
        for (StreamingToolExecutor.TrackedTool tt : session.yieldCompleted()) {
            ContentBlock.ToolResultBlock resultBlock = new ContentBlock.ToolResultBlock(
                    tt.getToolUseId(), tt.getResult().content(), tt.getResult().isError());
            handler.onToolResult(tt.getToolUseId(), resultBlock);
            results.add(buildToolResultMessage(resultBlock));
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

    // ==================== 辅助方法 ====================

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
                    // 结束之前的文本块
                    flushTextBlock();
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
                    // 结束所有待处理的块
                    flushTextBlock();
                    flushToolBlock();
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
                log.info("[DIAG-TOOL] flushToolBlock: toolId={}, toolName={}, inputLen={}",
                        currentToolId, currentToolName, currentToolInput.length());
                JsonNode inputNode;
                try {
                    String inputStr = currentToolInput.toString();
                    inputNode = inputStr.isEmpty()
                            ? objectMapper.createObjectNode()
                            : objectMapper.readTree(inputStr);
                } catch (Exception e) {
                    log.warn("[DIAG-TOOL] flushToolBlock JSON parse error: toolId={}, error={}", currentToolId, e.getMessage());
                    inputNode = objectMapper.createObjectNode();
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
                        // 工具未找到 — 直接标记 COMPLETED + error
                        log.warn("Tool not found in streaming phase: {}", currentToolName);
                        session.addErrorResult(currentToolId,
                                "<tool_use_error>Error: No such tool available: "
                                + currentToolName + "</tool_use_error>");
                    }
                }

                currentToolId = null;
                currentToolName = null;
                currentToolInput.setLength(0);
            }
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
