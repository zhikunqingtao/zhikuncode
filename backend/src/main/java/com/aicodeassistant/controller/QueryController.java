package com.aicodeassistant.controller;

import com.aicodeassistant.engine.CompactService;
import com.aicodeassistant.engine.QueryConfig;
import com.aicodeassistant.engine.QueryEngine;
import com.aicodeassistant.engine.QueryLoopState;
import com.aicodeassistant.engine.QueryMessageHandler;
import com.aicodeassistant.engine.TokenCounter;
import com.aicodeassistant.exception.SessionNotFoundException;
import com.aicodeassistant.llm.LlmProviderRegistry;
import com.aicodeassistant.llm.ModelCapabilities;
import com.aicodeassistant.llm.ModelRegistry;
import com.aicodeassistant.llm.ThinkingConfig;
import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.aicodeassistant.model.PermissionMode;
import com.aicodeassistant.permission.PermissionModeManager;
import com.aicodeassistant.model.Usage;
import com.aicodeassistant.prompt.EffectiveSystemPromptBuilder;
import com.aicodeassistant.prompt.SystemPromptConfig;
import com.aicodeassistant.session.SessionData;
import com.aicodeassistant.session.SessionManager;
import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.ToolRegistry;
import com.aicodeassistant.tool.ToolUseContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * QueryController — 查询 API (CLI/SDK 专用)。
 * <p>
 * 端点:
 * <ul>
 *   <li>POST /api/query — 同步单次查询</li>
 *   <li>POST /api/query/stream — SSE 流式查询</li>
 *   <li>POST /api/query/conversation — 多轮会话查询</li>
 * </ul>
 * <p>
 * 与 WebSocket API (§6.2) 的分工:
 * - WebSocket STOMP: Web 前端长连接，双向实时交互
 * - REST /api/query: CLI/脚本单次调用，请求-响应模式
 * - SSE /api/query/stream: CLI 流式输出，实时展示
 *
 * @see <a href="SPEC §6.1.6a">QueryController 完整实现</a>
 */
@RestController
@RequestMapping("/api/query")
public class QueryController {

    private static final Logger log = LoggerFactory.getLogger(QueryController.class);

    private final QueryEngine queryEngine;
    private final ToolRegistry toolRegistry;
    private final SessionManager sessionManager;
    private final LlmProviderRegistry providerRegistry;
    private final TokenCounter tokenCounter;
    private final ObjectMapper objectMapper;
    private final EffectiveSystemPromptBuilder systemPromptBuilder;
    private final PermissionModeManager permissionModeManager;
    private final ModelRegistry modelRegistry;

    public QueryController(QueryEngine queryEngine,
                           ToolRegistry toolRegistry,
                           SessionManager sessionManager,
                           LlmProviderRegistry providerRegistry,
                           TokenCounter tokenCounter,
                           ObjectMapper objectMapper,
                           EffectiveSystemPromptBuilder systemPromptBuilder,
                           PermissionModeManager permissionModeManager,
                           ModelRegistry modelRegistry) {
        this.queryEngine = queryEngine;
        this.toolRegistry = toolRegistry;
        this.sessionManager = sessionManager;
        this.providerRegistry = providerRegistry;
        this.tokenCounter = tokenCounter;
        this.objectMapper = objectMapper;
        this.systemPromptBuilder = systemPromptBuilder;
        this.permissionModeManager = permissionModeManager;
        this.modelRegistry = modelRegistry;
    }

    // ════════════════════════════════════════════
    // 端点 1: 同步查询 — 等待完成后返回完整结果
    // ════════════════════════════════════════════

    /**
     * POST /api/query — 同步单次查询。
     * <p>
     * 阻塞直到 LLM 完成所有轮次，返回完整结果。
     * 在 Virtual Thread 中执行，不阻塞平台线程。
     * 权限策略: 默认 DONT_ASK (非交互场景)。
     */
    @PostMapping
    public ResponseEntity<QueryResponse> query(@RequestBody QueryRequest request) {
        // 1. 创建或复用会话
        String sessionId = resolveSessionId(request);

        // INC-3 fix: 使用请求传入的 permissionMode，默认仍为 BYPASS
        PermissionMode effectiveMode = request.permissionMode() != null
                ? request.permissionMode()
                : PermissionMode.BYPASS_PERMISSIONS;  // REST API 默认仍为 BYPASS
        permissionModeManager.setMode(sessionId, effectiveMode);
        log.debug("REST API /api/query: permissionMode={} (requested={})",
                effectiveMode, request.permissionMode());

        // 2. 组装工具池
        List<Tool> tools = assembleToolPool(request.allowedTools(), request.disallowedTools());

        // 3. 构建系统提示（使用新的 SystemPromptBuilder）
        SystemPromptConfig promptConfig = SystemPromptConfig.defaults()
                .withCustom(request.systemPrompt())
                .withAppend(request.appendSystemPrompt())
                .withSessionId(sessionId);
        String systemPrompt = systemPromptBuilder.buildEffectiveSystemPrompt(
                promptConfig, tools, request.model(), Path.of(System.getProperty("user.dir")));

        // 4. 组装用户消息
        String userMessage = buildUserMessage(request.prompt(), request.context());

        // 5. 构建 QueryConfig
        String model = request.model() != null ? request.model() : providerRegistry.getDefaultModel();
        int maxTurns = request.maxTurns() != null ? request.maxTurns() : 10;
        int contextWindow = getContextWindow(model);

        QueryConfig config = QueryConfig.withDefaults(
                model, systemPrompt, tools,
                toolRegistry.getToolDefinitions(),
                QueryConfig.DEFAULT_MAX_TOKENS,
                contextWindow,
                new ThinkingConfig.Disabled(),
                maxTurns, "rest-api"
        );

        // 6. 初始化循环状态
        ToolUseContext toolCtx = ToolUseContext.of(
                System.getProperty("user.dir"), sessionId);
        QueryLoopState state = new QueryLoopState(new ArrayList<>(), toolCtx);
        // 添加用户消息
        state.addMessage(new Message.UserMessage(
                UUID.randomUUID().toString(), Instant.now(),
                List.of(new ContentBlock.TextBlock(userMessage)),
                null, null));

        // 7. 执行查询
        ResultCollectingHandler handler = new ResultCollectingHandler();
        QueryEngine.QueryResult result = queryEngine.execute(config, state, handler);

        // 7.5 ★ 持久化消息到数据库 ★
        // SessionManager.addMessage 签名: (sessionId, role, content, stopReason, inputTokens, outputTokens)
        try {
            for (Message msg : result.messages()) {
                switch (msg) {
                    case Message.UserMessage user -> sessionManager.addMessage(
                            sessionId, "user", user.content(), null, 0, 0);
                    case Message.AssistantMessage assistant -> sessionManager.addMessage(
                            sessionId, "assistant", assistant.content(),
                            assistant.stopReason(),
                            assistant.usage() != null ? assistant.usage().inputTokens() : 0,
                            assistant.usage() != null ? assistant.usage().outputTokens() : 0);
                    case Message.SystemMessage system -> sessionManager.addMessage(
                            sessionId, "system", system.content(), null, 0, 0);
                }
            }
            log.debug("REST API /api/query: 已持久化 {} 条消息到会话 {}",
                    result.messages().size(), sessionId);
        } catch (Exception e) {
            log.error("REST API 消息持久化失败, sessionId={}", sessionId, e);
            // 持久化失败不阻塞响应返回（降级策略）
        }

        // 8. 提取最终文本
        String finalText = extractFinalText(result.messages());

        return ResponseEntity.ok(new QueryResponse(
                sessionId,
                finalText,
                result.totalUsage(),
                0.0, // costUsd — 后续 CostTracker 实现
                handler.getToolCalls(),
                result.stopReason(),
                result.isSuccess() ? null : result.error()
        ));
    }

    // ════════════════════════════════════════════
    // 端点 2: SSE 流式查询 — 实时事件流
    // ════════════════════════════════════════════

    /**
     * POST /api/query/stream — SSE 流式查询。
     * <p>
     * 通过 Server-Sent Events 实时推送:
     * stream_delta / thinking_delta / tool_use / tool_result / message_complete / error
     */
    @PostMapping("/stream")
    public SseEmitter streamQuery(@RequestBody QueryRequest request) {
        long timeoutMs = (request.timeoutSeconds() != null
                ? request.timeoutSeconds() : 600) * 1000L;
        SseEmitter emitter = new SseEmitter(timeoutMs);

        // 在 Virtual Thread 中执行
        Thread.startVirtualThread(() -> {
            try {
                String sessionId = resolveSessionId(request);
                // INC-3 fix: 使用请求传入的 permissionMode
                PermissionMode effectiveMode = request.permissionMode() != null
                        ? request.permissionMode()
                        : PermissionMode.BYPASS_PERMISSIONS;
                permissionModeManager.setMode(sessionId, effectiveMode);
                List<Tool> tools = assembleToolPool(
                        request.allowedTools(), request.disallowedTools());
                SystemPromptConfig promptConfig = SystemPromptConfig.defaults()
                        .withCustom(request.systemPrompt())
                        .withAppend(request.appendSystemPrompt())
                        .withSessionId(sessionId);
                String systemPrompt = systemPromptBuilder.buildEffectiveSystemPrompt(
                        promptConfig, tools, request.model(), Path.of(System.getProperty("user.dir")));
                String userMessage = buildUserMessage(request.prompt(), request.context());
                String model = request.model() != null
                        ? request.model() : providerRegistry.getDefaultModel();
                int maxTurns = request.maxTurns() != null ? request.maxTurns() : 10;
                int contextWindow = getContextWindow(model);

                QueryConfig config = QueryConfig.withDefaults(
                        model, systemPrompt, tools,
                        toolRegistry.getToolDefinitions(),
                        QueryConfig.DEFAULT_MAX_TOKENS,
                        contextWindow,
                        new ThinkingConfig.Disabled(),
                        maxTurns, "rest-api-stream"
                );

                ToolUseContext toolCtx = ToolUseContext.of(
                        System.getProperty("user.dir"), sessionId);
                QueryLoopState state = new QueryLoopState(new ArrayList<>(), toolCtx);
                state.addMessage(new Message.UserMessage(
                        UUID.randomUUID().toString(), Instant.now(),
                        List.of(new ContentBlock.TextBlock(userMessage)),
                        null, null));

                // 流式回调 → SSE 推送（使用独立 SseStreamHandler）
                SseStreamHandler handler = new SseStreamHandler(emitter, objectMapper);
                QueryEngine.QueryResult result = queryEngine.execute(config, state, handler);

                // ★ 持久化消息（使用实际 6 参数签名）★
                try {
                    for (Message msg : result.messages()) {
                        switch (msg) {
                            case Message.UserMessage user -> sessionManager.addMessage(
                                    sessionId, "user", user.content(), null, 0, 0);
                            case Message.AssistantMessage assistant -> sessionManager.addMessage(
                                    sessionId, "assistant", assistant.content(),
                                    assistant.stopReason(),
                                    assistant.usage() != null ? assistant.usage().inputTokens() : 0,
                                    assistant.usage() != null ? assistant.usage().outputTokens() : 0);
                            case Message.SystemMessage system -> sessionManager.addMessage(
                                    sessionId, "system", system.content(), null, 0, 0);
                        }
                    }
                } catch (Exception e) {
                    log.error("SSE 消息持久化失败, sessionId={}", sessionId, e);
                }

                // 完成事件
                sendEvent(emitter, "message_complete", Map.of(
                        "sessionId", sessionId,
                        "usage", result.totalUsage(),
                        "stopReason", result.stopReason()));
                emitter.complete();

            } catch (Exception e) {
                log.error("Stream query error", e);
                sendEvent(emitter, "error", Map.of(
                        "type", "error", "message", e.getMessage()));
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    // ════════════════════════════════════════════
    // 端点 3: 多轮会话查询 — 关联已有会话
    // ════════════════════════════════════════════

    /**
     * POST /api/query/conversation — 在已有会话中追加查询。
     * <p>
     * 与 /api/query 的区别:
     * - 必须提供 sessionId
     * - 自动加载会话历史消息
     */
    @PostMapping("/conversation")
    public ResponseEntity<QueryResponse> conversationQuery(
            @RequestBody ConversationRequest request) {

        // 1. 加载会话
        SessionData session = sessionManager.loadSession(request.sessionId())
                .orElseThrow(() -> new SessionNotFoundException(request.sessionId()));

        // INC-3 fix: 使用请求传入的 permissionMode
        PermissionMode effectiveMode = request.permissionMode() != null
                ? request.permissionMode()
                : PermissionMode.BYPASS_PERMISSIONS;
        permissionModeManager.setMode(request.sessionId(), effectiveMode);

        // 2. 准备工具和系统提示
        List<Tool> tools = assembleToolPool(
                request.allowedTools(), request.disallowedTools());
        SystemPromptConfig promptConfig = SystemPromptConfig.defaults()
                .withCustom(request.systemPrompt())
                .withAppend(request.appendSystemPrompt())
                .withSessionId(request.sessionId());
        String systemPrompt = systemPromptBuilder.buildEffectiveSystemPrompt(
                promptConfig, tools, request.model(), Path.of(System.getProperty("user.dir")));
        String model = request.model() != null ? request.model() : session.model();
        int maxTurns = request.maxTurns() != null ? request.maxTurns() : 10;
        int contextWindow = getContextWindow(model);

        QueryConfig config = QueryConfig.withDefaults(
                model, systemPrompt, tools,
                toolRegistry.getToolDefinitions(),
                QueryConfig.DEFAULT_MAX_TOKENS,
                contextWindow,
                new ThinkingConfig.Disabled(),
                maxTurns, "rest-api-conversation"
        );

        // 3. 初始化状态 — 加载历史消息
        // REST API 无交互式权限确认能力，设置 null notifier + BYPASS 模式双保险
        ToolUseContext toolCtx = ToolUseContext.of(
                System.getProperty("user.dir"), request.sessionId())
                .withPermissionNotifier(null);  // 明确标注: REST无pusher
        log.debug("REST API conversation: permissionMode=BYPASS, notifier=null (by design)");
        QueryLoopState state = new QueryLoopState(new ArrayList<>(session.messages()), toolCtx);

        // 追加新用户消息
        state.addMessage(new Message.UserMessage(
                UUID.randomUUID().toString(), Instant.now(),
                List.of(new ContentBlock.TextBlock(request.prompt())),
                null, null));

        // 4. 执行查询
        ResultCollectingHandler handler = new ResultCollectingHandler();
        QueryEngine.QueryResult result = queryEngine.execute(config, state, handler);

        // ★ 持久化新增消息（排除已有历史消息，使用实际 6 参数签名）★
        try {
            int existingCount = session.messages().size();
            List<Message> newMessages = result.messages().subList(
                    Math.min(existingCount, result.messages().size()),
                    result.messages().size());
            for (Message msg : newMessages) {
                switch (msg) {
                    case Message.UserMessage user -> sessionManager.addMessage(
                            request.sessionId(), "user", user.content(), null, 0, 0);
                    case Message.AssistantMessage assistant -> sessionManager.addMessage(
                            request.sessionId(), "assistant", assistant.content(),
                            assistant.stopReason(),
                            assistant.usage() != null ? assistant.usage().inputTokens() : 0,
                            assistant.usage() != null ? assistant.usage().outputTokens() : 0);
                    case Message.SystemMessage system -> sessionManager.addMessage(
                            request.sessionId(), "system", system.content(), null, 0, 0);
                }
            }
            log.debug("REST API /api/query/conversation: 已持久化 {} 条新消息到会话 {}",
                    newMessages.size(), request.sessionId());
        } catch (Exception e) {
            log.error("Conversation 消息持久化失败, sessionId={}",
                    request.sessionId(), e);
        }

        String finalText = extractFinalText(result.messages());

        return ResponseEntity.ok(new QueryResponse(
                request.sessionId(),
                finalText,
                result.totalUsage(),
                0.0,
                handler.getToolCalls(),
                result.stopReason(),
                result.isSuccess() ? null : result.error()
        ));
    }

    // ═══ 私有方法 ═══

    private String resolveSessionId(QueryRequest request) {
        if (request.sessionId() != null) {
            return request.sessionId();
        }
        String model = request.model() != null ? request.model() : providerRegistry.getDefaultModel();
        String workingDir = request.workingDirectory() != null
                ? request.workingDirectory() : System.getProperty("user.dir");
        return sessionManager.createSession(model, workingDir);
    }

    private List<Tool> assembleToolPool(List<String> allowedTools, List<String> disallowedTools) {
        List<Tool> tools = toolRegistry.getEnabledTools();
        if (allowedTools != null && !allowedTools.isEmpty()) {
            Set<String> allowed = Set.copyOf(allowedTools);
            tools = tools.stream().filter(t -> allowed.contains(t.getName())).toList();
        }
        if (disallowedTools != null && !disallowedTools.isEmpty()) {
            Set<String> denied = Set.copyOf(disallowedTools);
            tools = tools.stream().filter(t -> !denied.contains(t.getName())).toList();
        }
        return tools;
    }

    private String buildUserMessage(String prompt, QueryContext context) {
        StringBuilder sb = new StringBuilder();
        if (context != null && context.stdin() != null && !context.stdin().isBlank()) {
            sb.append("<stdin>\n").append(context.stdin()).append("\n</stdin>\n\n");
        }
        if (prompt != null) {
            sb.append(prompt);
        }
        return sb.toString();
    }

    private int getContextWindow(String model) {
        // 使用 ModelRegistry 的四级查询（自定义→Provider→内置→默认），
        // 避免 Provider 未注册千问模型能力时 fallback 到 DEFAULT(8192) 导致 effectiveWindow 为负
        return modelRegistry.getContextWindowForModel(model);
    }

    private String extractFinalText(List<Message> messages) {
        // 从最后一条 AssistantMessage 提取文本
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            if (msg instanceof Message.AssistantMessage assistant && assistant.content() != null) {
                StringBuilder sb = new StringBuilder();
                for (var block : assistant.content()) {
                    if (block instanceof ContentBlock.TextBlock text) {
                        sb.append(text.text());
                    }
                }
                if (!sb.isEmpty()) return sb.toString();
            }
        }
        return "";
    }

    private void sendEvent(SseEmitter emitter, String eventName, Object data) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(data, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            // 客户端断开连接，静默忽略
        }
    }

    // ═══ 内部回调实现 ═══

    /**
     * 结果收集处理器 — 收集工具调用信息。
     */
    private static class ResultCollectingHandler implements QueryMessageHandler {
        private final List<ToolCallSummary> toolCalls = new ArrayList<>();

        @Override public void onTextDelta(String text) {}

        @Override
        public void onToolUseStart(String toolUseId, String toolName) {
            toolCalls.add(new ToolCallSummary(toolName, null, null, false));
        }

        @Override
        public void onToolUseComplete(String toolUseId, ContentBlock.ToolUseBlock toolUse) {}

        @Override
        public void onToolResult(String toolUseId, ContentBlock.ToolResultBlock result) {
            // 更新最后一个工具调用的结果
            if (!toolCalls.isEmpty()) {
                ToolCallSummary last = toolCalls.getLast();
                toolCalls.set(toolCalls.size() - 1,
                        new ToolCallSummary(last.tool(), last.input(),
                                result.content(), result.isError()));
            }
        }

        @Override
        public void onAssistantMessage(Message.AssistantMessage message) {}

        public List<ToolCallSummary> getToolCalls() { return toolCalls; }
    }

    // ═══ DTO Records ═══

    public record QueryRequest(
            String prompt,
            String model,
            String systemPrompt,
            String appendSystemPrompt,
            PermissionMode permissionMode,
            Integer maxTurns,
            Double maxBudgetUsd,
            List<String> allowedTools,
            List<String> disallowedTools,
            String sessionId,
            String workingDirectory,
            Integer timeoutSeconds,
            String outputFormat,
            QueryContext context
    ) {}

    public record QueryContext(
            String stdin,
            List<String> files
    ) {}

    public record ConversationRequest(
            String sessionId,
            String prompt,
            String model,
            String systemPrompt,
            String appendSystemPrompt,
            PermissionMode permissionMode,
            Integer maxTurns,
            Double maxBudgetUsd,
            List<String> allowedTools,
            List<String> disallowedTools,
            String workingDirectory,
            Integer timeoutSeconds
    ) {}

    public record QueryResponse(
            String sessionId,
            String result,
            Usage usage,
            double costUsd,
            List<ToolCallSummary> toolCalls,
            String stopReason,
            String error
    ) {}

    public record ToolCallSummary(
            String tool,
            Object input,
            String output,
            boolean isError
    ) {}
}
