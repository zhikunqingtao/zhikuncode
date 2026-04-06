package com.aicodeassistant.engine;

import com.aicodeassistant.llm.*;
import com.aicodeassistant.model.*;
import com.aicodeassistant.permission.PermissionPipeline;
import com.aicodeassistant.permission.PermissionRuleRepository;
import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * QueryEngine — 查询引擎核心循环。
 * <p>
 * 8 步循环: 压缩检查 → 流式执行器初始化 → API 调用 → 流处理 →
 *          工具执行 → 继续/终止判定 → 工具摘要注入 → 状态更新
 * <p>
 * 在 Virtual Thread 中执行，配合 StreamChatCallback 实现流式输出。
 *
 * @see <a href="SPEC §3.1.1">核心流程</a>
 * @see <a href="SPEC §3.1.1a">查询主循环实现细节</a>
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

    public QueryEngine(LlmProviderRegistry providerRegistry,
                       CompactService compactService,
                       ApiRetryService apiRetryService,
                       PermissionPipeline permissionPipeline,
                       PermissionRuleRepository permissionRuleRepository,
                       TokenCounter tokenCounter,
                       ObjectMapper objectMapper) {
        this.providerRegistry = providerRegistry;
        this.compactService = compactService;
        this.apiRetryService = apiRetryService;
        this.permissionPipeline = permissionPipeline;
        this.permissionRuleRepository = permissionRuleRepository;
        this.tokenCounter = tokenCounter;
        this.objectMapper = objectMapper;
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

        try {
            totalUsage = queryLoop(config, state, handler, aborted);
        } catch (Exception e) {
            log.error("QueryEngine 执行异常", e);
            handler.onError(e);
            return new QueryResult(state.getMessages(), totalUsage,
                    "error", e.getMessage(), state.getTurnCount());
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

        while (!aborted.get()) {
            state.incrementTurnCount();
            int turn = state.getTurnCount();
            handler.onTurnStart(turn);

            // ===== Step 1: 压缩检查 =====
            if (state.isAutoCompactEnabled() && !state.isAutoCompactCircuitBroken()) {
                tryAutoCompact(config, state, handler);
            }

            // ===== Step 2: 流式工具执行器初始化 (P0: 非流式模式) =====
            // P0 使用非流式模式: 等 API 完成后批量执行工具

            // ===== Step 3: API 调用 =====
            int effectiveMaxTokens = state.getEffectiveMaxTokens(config.maxTokens());
            LlmProvider provider = providerRegistry.getProvider(config.model());

            // 构建 API 消息格式
            List<Map<String, Object>> apiMessages = buildApiMessages(state.getMessages());

            // 收集流式响应
            StreamCollector collector = new StreamCollector(handler);

            try {
                apiRetryService.executeWithRetry(() -> {
                    provider.streamChat(
                            config.model(),
                            apiMessages,
                            config.systemPrompt(),
                            config.toolDefinitions(),
                            effectiveMaxTokens,
                            config.thinkingConfig(),
                            collector
                    );
                    return null;
                }, config.querySource());
            } catch (LlmApiException e) {
                // 413 prompt_too_long → 反应式压缩
                if (e.getStatusCode() == 413 || (e.getMessage() != null
                        && e.getMessage().contains("prompt_too_long"))) {
                    if (tryReactiveCompact(config, state, handler)) {
                        continue; // 压缩后重试
                    }
                }
                throw e;
            }

            // ===== Step 4: 流处理完成，收集结果 =====
            Message.AssistantMessage assistantMessage = collector.buildAssistantMessage();
            state.addMessage(assistantMessage);
            handler.onAssistantMessage(assistantMessage);

            // 累计 usage
            if (assistantMessage.usage() != null) {
                totalUsage = totalUsage.add(assistantMessage.usage());
                handler.onUsage(assistantMessage.usage());
            }

            // ===== Step 5: 工具执行 =====
            List<ContentBlock.ToolUseBlock> toolUseBlocks = extractToolUseBlocks(assistantMessage);

            if (!toolUseBlocks.isEmpty()) {
                List<Message> toolResults = executeTools(
                        toolUseBlocks, config, state, handler);
                state.addMessages(toolResults);
            }

            // ===== Step 6: 继续/终止判定 =====
            String stopReason = assistantMessage.stopReason();

            // 6a: end_turn 且无工具调用 → 终止
            if ("end_turn".equals(stopReason) && toolUseBlocks.isEmpty()) {
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

            // 6c: 用户中断
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

            // ===== Step 7: 工具使用摘要注入 (P1, P0 跳过) =====

            // ===== Step 8: 状态更新 → 回到 Step 1 =====
        }

        return totalUsage;
    }

    // ==================== Step 1: 压缩 ====================

    private void tryAutoCompact(QueryConfig config, QueryLoopState state,
                                 QueryMessageHandler handler) {
        try {
            if (compactService.shouldAutoCompactBufferBased(
                    state.getMessages(), config.contextWindow())) {
                log.info("触发自动压缩: 当前消息数={}", state.getMessages().size());
                int beforeTokens = tokenCounter.estimateTokens(state.getMessages());

                CompactService.CompactResult result = compactService.compact(
                        state.getMessages(), config.contextWindow(), false);

                if (result.skipReason() == null) {
                    state.setMessages(result.compactedMessages());
                    state.resetAutoCompactFailures();
                    handler.onCompactEvent("auto_compact",
                            result.beforeTokens(), result.afterTokens());
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

        try {
            CompactService.CompactResult result = compactService.reactiveCompact(
                    state.getMessages(), config.contextWindow(), false);
            if (result.skipReason() == null) {
                state.setMessages(result.compactedMessages());
                handler.onCompactEvent("reactive_compact",
                        result.beforeTokens(), result.afterTokens());
                return true;
            }
        } catch (Exception e) {
            log.error("反应式压缩失败", e);
        }
        return false;
    }

    // ==================== Step 5: 工具执行 ====================

    private List<Message> executeTools(
            List<ContentBlock.ToolUseBlock> toolUseBlocks,
            QueryConfig config, QueryLoopState state,
            QueryMessageHandler handler) {

        List<Message> results = new ArrayList<>();

        for (ContentBlock.ToolUseBlock toolUse : toolUseBlocks) {
            // onToolUseStart 已在 StreamCollector 流式阶段通知过，此处不再重复

            // 查找工具
            Tool tool = findTool(toolUse.name(), config.tools());
            if (tool == null) {
                ContentBlock.ToolResultBlock errorResult = new ContentBlock.ToolResultBlock(
                        toolUse.id(), "Tool not found: " + toolUse.name(), true);
                handler.onToolResult(toolUse.id(), errorResult);
                results.add(buildToolResultMessage(errorResult));
                continue;
            }

            // 构建 ToolInput
            ToolInput input = ToolInput.fromJsonNode(toolUse.input());

            // 权限检查
            PermissionContext permContext = permissionRuleRepository.buildContext(
                    PermissionMode.DEFAULT, false, false);
            PermissionDecision decision = permissionPipeline.checkPermission(
                    tool, input, state.getToolUseContext(), permContext);

            if (decision.isDenied()) {
                ContentBlock.ToolResultBlock denyResult = new ContentBlock.ToolResultBlock(
                        toolUse.id(),
                        "Permission denied: " + (decision.reason() != null
                                ? decision.reason() : "Operation not allowed"),
                        true);
                handler.onToolResult(toolUse.id(), denyResult);
                results.add(buildToolResultMessage(denyResult));
                continue;
            }

            // 执行工具
            try {
                ToolResult toolResult = tool.call(input, state.getToolUseContext());
                ContentBlock.ToolResultBlock resultBlock = new ContentBlock.ToolResultBlock(
                        toolUse.id(), toolResult.content(), toolResult.isError());
                handler.onToolResult(toolUse.id(), resultBlock);
                handler.onToolUseComplete(toolUse.id(), toolUse);
                results.add(buildToolResultMessage(resultBlock));
            } catch (Exception e) {
                log.error("工具执行异常: tool={}", toolUse.name(), e);
                ContentBlock.ToolResultBlock errorResult = new ContentBlock.ToolResultBlock(
                        toolUse.id(), "Tool execution error: " + e.getMessage(), true);
                handler.onToolResult(toolUse.id(), errorResult);
                results.add(buildToolResultMessage(errorResult));
            }
        }

        return results;
    }

    // ==================== 辅助方法 ====================

    /**
     * 构建 API 消息格式 — 将内部 Message 转为 Map 格式。
     */
    private List<Map<String, Object>> buildApiMessages(List<Message> messages) {
        List<Map<String, Object>> apiMessages = new ArrayList<>();

        for (Message msg : messages) {
            switch (msg) {
                case Message.UserMessage user -> {
                    if (user.toolUseResult() != null) {
                        // tool_result 消息
                        apiMessages.add(Map.of(
                                "role", "user",
                                "content", List.of(Map.of(
                                        "type", "tool_result",
                                        "tool_use_id", user.sourceToolAssistantUUID() != null
                                                ? user.sourceToolAssistantUUID() : "",
                                        "content", user.toolUseResult()
                                ))
                        ));
                    } else {
                        // 普通用户消息
                        List<Map<String, Object>> contentBlocks = new ArrayList<>();
                        if (user.content() != null) {
                            for (ContentBlock block : user.content()) {
                                contentBlocks.add(contentBlockToMap(block));
                            }
                        }
                        apiMessages.add(Map.of("role", "user", "content", contentBlocks));
                    }
                }
                case Message.AssistantMessage assistant -> {
                    List<Map<String, Object>> contentBlocks = new ArrayList<>();
                    if (assistant.content() != null) {
                        for (ContentBlock block : assistant.content()) {
                            contentBlocks.add(contentBlockToMap(block));
                        }
                    }
                    apiMessages.add(Map.of("role", "assistant", "content", contentBlocks));
                }
                case Message.SystemMessage system -> {
                    // 系统消息不直接发送给 API，已在 systemPrompt 中处理
                }
            }
        }

        return apiMessages;
    }

    private Map<String, Object> contentBlockToMap(ContentBlock block) {
        return switch (block) {
            case ContentBlock.TextBlock text ->
                    Map.of("type", "text", "text", text.text() != null ? text.text() : "");
            case ContentBlock.ToolUseBlock toolUse -> {
                Map<String, Object> map = new HashMap<>();
                map.put("type", "tool_use");
                map.put("id", toolUse.id());
                map.put("name", toolUse.name());
                map.put("input", toolUse.input() != null ? toolUse.input() : objectMapper.createObjectNode());
                yield map;
            }
            case ContentBlock.ToolResultBlock result -> {
                Map<String, Object> map = new HashMap<>();
                map.put("type", "tool_result");
                map.put("tool_use_id", result.toolUseId());
                map.put("content", result.content() != null ? result.content() : "");
                if (result.isError()) map.put("is_error", true);
                yield map;
            }
            case ContentBlock.ImageBlock image ->
                    Map.of("type", "image", "source", Map.of(
                            "type", "base64",
                            "media_type", image.mediaType(),
                            "data", image.base64Data()));
            case ContentBlock.ThinkingBlock thinking ->
                    Map.of("type", "thinking", "thinking", thinking.thinking() != null ? thinking.thinking() : "");
            case ContentBlock.RedactedThinkingBlock redacted ->
                    Map.of("type", "redacted_thinking", "data", redacted.data() != null ? redacted.data() : "");
        };
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
     */
    private static class StreamCollector implements StreamChatCallback {

        private final QueryMessageHandler handler;
        private final List<ContentBlock> contentBlocks = new ArrayList<>();
        private final StringBuilder currentText = new StringBuilder();
        private String currentToolId;
        private String currentToolName;
        private final StringBuilder currentToolInput = new StringBuilder();
        private Usage usage;
        private String stopReason;

        StreamCollector(QueryMessageHandler handler) {
            this.handler = handler;
        }

        @Override
        public void onEvent(LlmStreamEvent event) {
            switch (event) {
                case LlmStreamEvent.TextDelta delta -> {
                    currentText.append(delta.text());
                    handler.onTextDelta(delta.text());
                }
                case LlmStreamEvent.ThinkingDelta delta -> {
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
                    // 结束所有待处理的块
                    flushTextBlock();
                    flushToolBlock();
                }
                case LlmStreamEvent.Error error -> {
                    handler.onError(new LlmApiException(error.message(), error.retryable()));
                }
            }
        }

        @Override
        public void onComplete() {
            flushTextBlock();
            flushToolBlock();
        }

        @Override
        public void onError(Throwable error) {
            handler.onError(error);
        }

        private void flushTextBlock() {
            if (!currentText.isEmpty()) {
                contentBlocks.add(new ContentBlock.TextBlock(currentText.toString()));
                currentText.setLength(0);
            }
        }

        private void flushToolBlock() {
            if (currentToolId != null) {
                JsonNode inputNode;
                try {
                    ObjectMapper mapper = new ObjectMapper();
                    String inputStr = currentToolInput.toString();
                    inputNode = inputStr.isEmpty()
                            ? mapper.createObjectNode()
                            : mapper.readTree(inputStr);
                } catch (Exception e) {
                    inputNode = new ObjectMapper().createObjectNode();
                }
                contentBlocks.add(new ContentBlock.ToolUseBlock(
                        currentToolId, currentToolName, inputNode));
                currentToolId = null;
                currentToolName = null;
                currentToolInput.setLength(0);
            }
        }

        Message.AssistantMessage buildAssistantMessage() {
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
