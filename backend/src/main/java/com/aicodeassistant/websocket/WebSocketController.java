package com.aicodeassistant.websocket;

import com.aicodeassistant.context.ProjectContextService;
import com.aicodeassistant.service.CostTrackerService;
import com.aicodeassistant.engine.AbortReason;
import com.aicodeassistant.engine.ElicitationService;
import com.aicodeassistant.engine.QueryConfig;
import com.aicodeassistant.engine.QueryEngine;
import com.aicodeassistant.engine.QueryLoopState;
import com.aicodeassistant.engine.QueryMessageHandler;
import com.aicodeassistant.command.CommandRegistry;
import com.aicodeassistant.command.CommandResult;
import com.aicodeassistant.command.CommandContext;
import com.aicodeassistant.history.FileHistoryService;
import com.aicodeassistant.mcp.McpClientManager;
import com.aicodeassistant.permission.PermissionModeManager;
import com.aicodeassistant.permission.PermissionNotifier;
import com.aicodeassistant.llm.LlmProviderRegistry;
import com.aicodeassistant.llm.ModelCapabilities;
import com.aicodeassistant.llm.ModelRegistry;
import com.aicodeassistant.llm.ThinkingConfig;
import com.aicodeassistant.model.PermissionBehavior;
import com.aicodeassistant.model.PermissionDecision;
import com.aicodeassistant.model.RuleScope;
import com.aicodeassistant.permission.PermissionPipeline;
import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.aicodeassistant.model.Usage;
import com.aicodeassistant.prompt.EffectiveSystemPromptBuilder;
import com.aicodeassistant.prompt.SystemPromptConfig;
import com.aicodeassistant.session.SessionData;
import com.aicodeassistant.session.SessionManager;
import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.ToolRegistry;
import com.aicodeassistant.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.stereotype.Controller;

import java.nio.file.Path;
import java.security.Principal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebSocket STOMP 消息控制器 (§8.5.4)。
 * <p>
 * 覆盖全部 10 种 Client→Server 消息处理 + 25 种 Server→Client 推送方法。
 * <p>
 * 消息格式: 扁平 JSON，字段名与前端 handler 完全一致。
 * 使用 {@link SimpMessagingTemplate} 通过 user destination 定向推送。
 *
 * @see ServerMessage 25 种服务端消息类型定义
 * @see ClientMessage 10 种客户端消息类型定义
 */
@Controller
public class WebSocketController implements PermissionNotifier {

    private static final Logger log = LoggerFactory.getLogger(WebSocketController.class);

    private final SimpMessagingTemplate messaging;
    private final WebSocketSessionManager wsSessionManager;
    private final QueryEngine queryEngine;
    private final ToolRegistry toolRegistry;
    private final LlmProviderRegistry providerRegistry;
    private final EffectiveSystemPromptBuilder systemPromptBuilder;
    private final ModelRegistry modelRegistry;            // P0-1 新增
    private final SessionManager sessionManager;           // P1-0 新增
    private final ElicitationService elicitationService;   // P1-5 新增
    private final PermissionPipeline permissionPipeline;    // P0 权限闭环
    private final CommandRegistry commandRegistry;             // P1 Slash命令
    private final McpClientManager mcpClientManager;           // P1 MCP操作
    private final FileHistoryService fileHistoryService;       // P1 文件回退
    private final ProjectContextService projectContextService;  // F5 项目上下文
    private final PermissionModeManager permissionModeManager;    // P1-03 权限模式
    private final com.aicodeassistant.coordinator.LeaderPermissionBridge leaderPermissionBridge;  // Swarm 权限冒泡
    private final CostTrackerService costTrackerService;                                          // F2 费用追踪

    /** 会话级查询运行守卫 — 防止同一会话并发执行多个 QueryEngine */
    private final ConcurrentHashMap<String, AtomicBoolean> sessionQueryRunning = new ConcurrentHashMap<>();

    public WebSocketController(SimpMessagingTemplate messaging,
                                WebSocketSessionManager wsSessionManager,
                                QueryEngine queryEngine,
                                ToolRegistry toolRegistry,
                                LlmProviderRegistry providerRegistry,
                                EffectiveSystemPromptBuilder systemPromptBuilder,
                                ModelRegistry modelRegistry,
                                SessionManager sessionManager,
                                ElicitationService elicitationService,
                                PermissionPipeline permissionPipeline,
                                CommandRegistry commandRegistry,
                                McpClientManager mcpClientManager,
                                FileHistoryService fileHistoryService,
                                ProjectContextService projectContextService,
                                PermissionModeManager permissionModeManager,
                                @org.springframework.context.annotation.Lazy com.aicodeassistant.coordinator.LeaderPermissionBridge leaderPermissionBridge,
                                CostTrackerService costTrackerService) {
        this.messaging = messaging;
        this.wsSessionManager = wsSessionManager;
        this.queryEngine = queryEngine;
        this.toolRegistry = toolRegistry;
        this.providerRegistry = providerRegistry;
        this.systemPromptBuilder = systemPromptBuilder;
        this.modelRegistry = modelRegistry;
        this.sessionManager = sessionManager;
        this.elicitationService = elicitationService;
        this.permissionPipeline = permissionPipeline;
        this.commandRegistry = commandRegistry;
        this.mcpClientManager = mcpClientManager;
        this.fileHistoryService = fileHistoryService;
        this.projectContextService = projectContextService;
        this.permissionModeManager = permissionModeManager;
        this.leaderPermissionBridge = leaderPermissionBridge;
        this.costTrackerService = costTrackerService;
    }

    // ══════════════════════════════════════════════════════════════
    // Server → Client 推送方法 (25 种)
    // ══════════════════════════════════════════════════════════════

    /**
     * 推送消息到指定会话的用户 (通过 STOMP user destination /user/queue/messages)。
     *
     * @param sessionId 应用层会话 ID
     * @param type      消息类型 (如 "stream_delta")
     * @param payload   消息负载 (会被 Jackson 序列化为 JSON 字段)
     */
    public void pushToUser(String sessionId, String type, Object payload) {
        String principal = wsSessionManager.getPrincipalForSession(sessionId);
        if (principal == null) {
            log.debug("No principal found for session {}, skipping push of type={}", sessionId, type);
            return;
        }

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", type);
        message.put("ts", System.currentTimeMillis());

        // 将 payload 的字段平铺到 message 中
        if (payload instanceof Map<?, ?> mapPayload) {
            mapPayload.forEach((k, v) -> message.put(String.valueOf(k), v));
        } else if (payload != null) {
            // record 类型 — 通过 Jackson 会自动序列化，这里直接发送包含 type/ts 的 wrapper
            // 为了保持扁平结构，需要手动提取 record 字段
            message.put("payload", payload); // 降级: 非 Map 时嵌套
        }

        messaging.convertAndSendToUser(principal, "/queue/messages", message);
    }

    /**
     * 推送 Map payload（扁平结构）。
     */
    private void push(String sessionId, String type, Map<String, Object> fields) {
        String principal = wsSessionManager.getPrincipalForSession(sessionId);
        if (principal == null) {
            log.warn("push({}) skipped: no principal for sessionId={}", type, sessionId);
            return;
        }

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", type);
        message.put("ts", System.currentTimeMillis());
        message.putAll(fields);

        if ("stream_delta".equals(type)) {
            log.debug("push stream_delta to principal={}, len={}", principal, fields.getOrDefault("delta", "").toString().length());
        } else {
            log.info("push({}) to principal={}, sessionId={}", type, principal, sessionId);
        }
        messaging.convertAndSendToUser(principal, "/queue/messages", message);
    }

    // ───── #1-5: messageStore ─────

    /** #1 流式文本增量 */
    public void sendStreamDelta(String sessionId, String delta) {
        push(sessionId, "stream_delta", Map.of("delta", delta));
    }

    /** #2 思考流增量 */
    public void sendThinkingDelta(String sessionId, String delta) {
        push(sessionId, "thinking_delta", Map.of("delta", delta));
    }

    /** #3 工具调用开始 */
    public void sendToolUseStart(String sessionId, String toolUseId, String toolName, Object input) {
        push(sessionId, "tool_use_start",
                Map.of("toolUseId", toolUseId, "toolName", toolName, "input", input));
    }

    /** #4 工具执行进度 */
    public void sendToolUseProgress(String sessionId, String toolUseId, String progress) {
        push(sessionId, "tool_use_progress",
                Map.of("toolUseId", toolUseId, "progress", progress));
    }

    /** #5 工具结果返回 */
    public void sendToolResult(String sessionId, String toolUseId,
                                String content, boolean isError) {
        push(sessionId, "tool_result",
                Map.of("toolUseId", toolUseId, "result",
                        Map.of("content", content, "isError", isError)));
    }

    // ───── #6: permissionStore + sessionStore ─────

    /** #6 权限请求 */
    public void sendPermissionRequest(String sessionId, String toolUseId,
                                       String toolName, Object input,
                                       String riskLevel, String reason) {
        push(sessionId, "permission_request",
                Map.of("toolUseId", toolUseId, "toolName", toolName,
                        "input", input, "riskLevel", riskLevel, "reason", reason));
    }

    /** #6b 来自子代理的权限请求 — 转发到父会话 */
    @Override
    public void sendPermissionRequestFromChild(String parentSessionId, String childSessionId,
                                                String toolUseId, String toolName,
                                                Object input, String riskLevel, String reason) {
        push(parentSessionId, "permission_request",
                Map.of("toolUseId", toolUseId, "toolName", toolName,
                        "input", input, "riskLevel", riskLevel, "reason", reason,
                        "source", "subagent", "childSessionId", childSessionId));
    }

    // ───── #7: messageStore + sessionStore ─────

    /** #7 助手回合完成 */
    public void sendMessageComplete(String sessionId, Usage usage, String stopReason) {
        Map<String, Object> usageMap = Map.of(
                "inputTokens", usage.inputTokens(),
                "outputTokens", usage.outputTokens(),
                "cacheReadInputTokens", usage.cacheReadInputTokens(),
                "cacheCreationInputTokens", usage.cacheCreationInputTokens()
        );
        push(sessionId, "message_complete",
                Map.of("usage", usageMap, "stopReason", stopReason));
    }

    // ───── #8: messageStore + sessionStore ─────

    /** #8 错误消息 */
    public void sendError(String sessionId, String code, String message, boolean retryable) {
        push(sessionId, "error",
                Map.of("code", code, "message", message, "retryable", retryable));
    }

    // ───── #9-10: sessionStore ─────

    /** #9 上下文压缩开始 */
    public void sendCompactStart(String sessionId) {
        push(sessionId, "compact_start", Map.of());
    }

    /** #10 压缩完成 */
    public void sendCompactComplete(String sessionId, String summary, int tokensSaved) {
        push(sessionId, "compact_complete",
                Map.of("summary", summary, "tokensSaved", tokensSaved));
    }

    // ───── #11: appUiStore ─────

    /** #11 AI 反向提问 */
    public void sendElicitation(String sessionId, String requestId,
                                 String question, Object options) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("requestId", requestId);
        fields.put("question", question);
        fields.put("options", options);
        push(sessionId, "elicitation", fields);
    }

    // ───── #12-14: taskStore ─────

    /** #12 子代理启动 */
    public void sendAgentSpawn(String sessionId, String taskId,
                                String agentName, String agentType) {
        push(sessionId, "agent_spawn",
                Map.of("taskId", taskId, "agentName", agentName, "agentType", agentType));
    }

    /** #13 子代理进度 */
    public void sendAgentUpdate(String sessionId, String taskId, String progress) {
        push(sessionId, "agent_update", Map.of("taskId", taskId, "progress", progress));
    }

    /** #14 子代理完成 */
    public void sendAgentComplete(String sessionId, String taskId, String result) {
        push(sessionId, "agent_complete", Map.of("taskId", taskId, "result", result));
    }

    // ───── #15: costStore ─────

    /** #15 费用/Token 更新 */
    public void sendCostUpdate(String sessionId, double sessionCost,
                                double totalCost, Usage usage) {
        Map<String, Object> usageMap = Map.of(
                "inputTokens", usage.inputTokens(),
                "outputTokens", usage.outputTokens(),
                "cacheReadInputTokens", usage.cacheReadInputTokens(),
                "cacheCreationInputTokens", usage.cacheCreationInputTokens()
        );
        push(sessionId, "cost_update",
                Map.of("sessionCost", sessionCost, "totalCost", totalCost, "usage", usageMap));
    }

    // ───── #16: sessionStore ─────

    /** #16 限流通知 */
    public void sendRateLimit(String sessionId, long retryAfterMs, String limitType) {
        push(sessionId, "rate_limit",
                Map.of("retryAfterMs", retryAfterMs, "limitType", limitType));
    }

    // ───── #17: notificationStore ─────

    /** #17 系统通知推送 */
    public void sendNotification(String sessionId, String key, String level,
                                  String message, int timeout) {
        push(sessionId, "notification",
                Map.of("key", key, "level", level, "message", message, "timeout", timeout));
    }

    // ───── #18: taskStore ─────

    /** #18 后台任务状态 */
    public void sendTaskUpdate(String sessionId, String taskId,
                                String status, String progress) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("taskId", taskId);
        fields.put("status", status);
        if (progress != null) fields.put("progress", progress);
        push(sessionId, "task_update", fields);
    }

    // ───── #19: appUiStore ─────

    /** #19 提示建议推送 */
    public void sendPromptSuggestion(String sessionId, java.util.List<String> suggestions) {
        push(sessionId, "prompt_suggestion", Map.of("suggestions", suggestions));
    }

    // ───── #20: bridgeStore ─────

    /** #20 桥接连接状态 */
    public void sendBridgeStatus(String sessionId, String status, String url) {
        push(sessionId, "bridge_status", Map.of("status", status, "url", url));
    }

    // ───── #21: inboxStore ─────

    /** #21 Swarm 队友消息 */
    public void sendTeammateMessage(String sessionId, String fromId, String content) {
        push(sessionId, "teammate_message", Map.of("fromId", fromId, "content", content));
    }

    // ───── #22: appUiStore ─────

    /** #22 推测执行结果 */
    public void sendSpeculationResult(String sessionId, String id, boolean accepted) {
        push(sessionId, "speculation_result", Map.of("id", id, "accepted", accepted));
    }

    // ───── #23: mcpStore ─────

    /** #23 MCP 工具列表变更 */
    public void sendMcpToolUpdate(String sessionId, String serverId, Object tools) {
        push(sessionId, "mcp_tool_update", Map.of("serverId", serverId, "tools", tools));
    }

    // ───── #24: 断线重连 ─────

    /** #24 断线重连恢复 */
    @SuppressWarnings("unchecked")
    public void sendSessionRestored(String sessionId, Object messages, Object metadata) {
        Object convertedMessages = messages;
        if (messages instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof com.aicodeassistant.model.Message) {
            convertedMessages = convertMessagesForWs((List<com.aicodeassistant.model.Message>) list);
        }
        push(sessionId, "session_restored",
                Map.of("messages", convertedMessages, "metadata", metadata));
    }

    // ───── #25: 心跳 ─────

    /** #25 心跳响应 */
    public void sendPong(String sessionId) {
        push(sessionId, "pong", Map.of());
    }

    // ───── #26: planStore ─────

    /** #26 Plan Mode 状态更新 */
    public void sendPlanUpdate(String sessionId, Map<String, Object> planData) {
        push(sessionId, "plan_update", planData);
    }

    // ───── #27: 会话列表变更通知 ─────

    /** #27 会话列表变更 — 通知前端按需刷新会话列表 */
    public void sendSessionListUpdated(String sessionId) {
        push(sessionId, "session_list_updated", Map.of());
    }

    // ══════════════════════════════════════════════════════════════
    // Client → Server: 全部 10 种 @MessageMapping Handler
    // ══════════════════════════════════════════════════════════════

    /**
     * #1 用户消息 → /app/chat
     */
    @MessageMapping("/chat")
    public void handleUserMessage(@Payload ClientMessage.UserMessagePayload msg,
                                   Principal principal) {
        String sessionId = resolveSessionId(principal);
        // 刷新 session 活跃时间（明确的用户动作）
        wsSessionManager.refreshActivity(sessionId);
        log.info("WS user_message: sessionId={}, text={}", sessionId,
                msg.text() != null ? msg.text().substring(0, Math.min(50, msg.text().length())) : "");

        // ★ 并发查询保护: 同一会话同时只允许一个 QueryEngine 运行
        AtomicBoolean running = sessionQueryRunning.computeIfAbsent(sessionId, k -> new AtomicBoolean(false));
        if (!running.compareAndSet(false, true)) {
            log.warn("Rejecting concurrent query for session {}: another query is already running", sessionId);
            sendError(sessionId, "query_busy", "当前会话正在处理中，请等待上一个请求完成", false);
            return;
        }

        // 在 Virtual Thread 中执行 QueryEngine
        Thread.ofVirtual().name("zhiku-ws-query-" + sessionId).start(() -> {
            try {
                executeQuery(sessionId, msg.text());
            } catch (Exception e) {
                log.error("QueryEngine 执行异常: sessionId={}", sessionId, e);
                sendError(sessionId, "query_error", e.getMessage() != null ? e.getMessage() : "Unknown error", true);
            } finally {
                // ★ 释放并发锁
                running.set(false);
            }
        });
    }

    /**
     * 执行查询 — 组装 QueryConfig + QueryLoopState，调用 QueryEngine.execute()。
     */
    private void executeQuery(String sessionId, String userText) {
        // 0. 异步预加载项目上下文
        Path workingDir = Path.of(System.getProperty("user.dir"));
        projectContextService.ensureContext(workingDir);

        // 1. 组装工具池
        List<Tool> tools = toolRegistry.getEnabledTools();

        // 2. 构建系统提示
        SystemPromptConfig promptConfig = SystemPromptConfig.defaults()
                .withSessionId(sessionId);
        String model = providerRegistry.getDefaultModel();
        String systemPrompt = systemPromptBuilder.buildEffectiveSystemPrompt(
                promptConfig, tools, model, Path.of(System.getProperty("user.dir")));

        // 3. 构建 QueryConfig
        int contextWindow = getContextWindow(model);
        QueryConfig config = QueryConfig.withDefaults(
                model, systemPrompt, tools,
                toolRegistry.getToolDefinitions(),
                QueryConfig.DEFAULT_MAX_TOKENS,
                contextWindow,
                new ThinkingConfig.Disabled(),
                QueryConfig.DEFAULT_MAX_TURNS, "websocket"
        );

        // 4. 初始化循环状态 + 加载历史消息 + 添加用户消息
        // 确保 WebSocket 会话使用 DEFAULT 权限模式（允许交互式权限确认）
        if (!permissionModeManager.hasExplicitMode(sessionId)) {
            permissionModeManager.setMode(sessionId, com.aicodeassistant.model.PermissionMode.DEFAULT);
        }
        log.debug("WebSocket session permissionMode: {}",
                permissionModeManager.getMode(sessionId));
        ToolUseContext toolUseContext = ToolUseContext.of(
                workingDir.toString(), sessionId)
                .withPermissionNotifier(this);

        // ★ 加载历史消息到 QueryLoopState（支持多轮对话上下文）
        List<Message> historyMessages = new ArrayList<>();
        try {
            sessionManager.loadSession(sessionId).ifPresent(data -> {
                historyMessages.addAll(data.messages());
            });
        } catch (Exception e) {
            log.warn("Failed to load history messages for session {}: {}", sessionId, e.getMessage());
        }

        QueryLoopState state = new QueryLoopState(new ArrayList<>(historyMessages), toolUseContext);
        state.addMessage(new Message.UserMessage(
                UUID.randomUUID().toString(), Instant.now(),
                List.of(new ContentBlock.TextBlock(userText)),
                null, null));

        // 5. 执行查询，通过 WsMessageHandler 流式推送
        log.info("QueryEngine 开始执行: sessionId={}, model={}", sessionId, model);
        WsMessageHandler handler = new WsMessageHandler(sessionId);
        QueryEngine.QueryResult result = null;
        try {
            result = queryEngine.execute(config, state, handler);

            // 6. ★ 持久化消息到数据库（与 QueryController 对齐）
            try {
                // 只持久化本次查询新增的消息（跳过历史消息）
                List<Message> allMessages = result.messages();
                int newStartIndex = historyMessages.size();
                List<Message> newMessages = allMessages.subList(
                        Math.min(newStartIndex, allMessages.size()), allMessages.size());
                for (Message msg : newMessages) {
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
                log.debug("WS 已持久化 {} 条新消息到会话 {}", newMessages.size(), sessionId);
            } catch (Exception e) {
                log.error("WS 消息持久化失败, sessionId={}", sessionId, e);
            }

            // 7. 发送完成消息
            Usage totalUsage = result.totalUsage() != null ? result.totalUsage() : Usage.zero();
            sendMessageComplete(sessionId, totalUsage, result.stopReason());
            // 7b. ★ 通知前端刷新会话列表（放在 message_complete 之后，确保前端先处理完状态切换）
            // 独立 try-catch: 推送失败不应影响已完成的查询结果，避免触发 handler.onError
            try {
                sendSessionListUpdated(sessionId);
            } catch (Exception ex) {
                log.warn("Failed to push session_list_updated: sessionId={}", sessionId, ex);
            }
            log.info("QueryEngine 完成: sessionId={}, stopReason={}, turns={}",
                    sessionId, result.stopReason(), result.turnCount());
        } catch (Exception e) {
            log.error("executeQuery 执行异常: sessionId={}", sessionId, e);
            handler.onError(e);
        } finally {
            // ★ 保障: 即使异常也确保发送 message_complete，防止前端卡在加载态
            if (result == null) {
                sendMessageComplete(sessionId, Usage.zero(), "error");
            }
        }
    }

    private int getContextWindow(String model) {
        return modelRegistry.getContextWindowForModel(model);
    }

    /**
     * WebSocket 流式消息处理器 — 将 QueryEngine 事件推送到前端。
     */
    private class WsMessageHandler implements QueryMessageHandler {
        private final String sessionId;

        WsMessageHandler(String sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        public void onTextDelta(String text) {
            sendStreamDelta(sessionId, text);
        }

        @Override
        public void onThinkingDelta(String thinking) {
            sendThinkingDelta(sessionId, thinking);
        }

        @Override
        public void onToolUseStart(String toolUseId, String toolName) {
            sendToolUseStart(sessionId, toolUseId, toolName, Map.of());
        }

        @Override
        public void onToolUseComplete(String toolUseId, ContentBlock.ToolUseBlock toolUse) {
            // 工具执行完成，已在 onToolResult 中处理
        }

        @Override
        public void onToolResult(String toolUseId, ContentBlock.ToolResultBlock result) {
            sendToolResult(sessionId, toolUseId,
                    result.content() != null ? result.content() : "", result.isError());
        }

        @Override
        public void onAssistantMessage(Message.AssistantMessage message) {
            // 已通过 onTextDelta 流式推送
        }

        @Override
        public void onError(Throwable error) {
            log.error("WsMessageHandler error: sessionId={}", sessionId, error);
            sendError(sessionId, "query_error",
                    error.getMessage() != null ? error.getMessage() : "Unknown error", true);
        }

        @Override
        public void onUsage(Usage usage) {
            var sessionCostSummary = costTrackerService.getSessionCost(sessionId);
            var globalCostSummary = costTrackerService.getGlobalCost();
            double sessionCost = sessionCostSummary.totalCost();
            double globalCost = globalCostSummary.totalCost();
            sendCostUpdate(sessionId, sessionCost, globalCost, usage);
        }

        @Override
        public void onCompactEvent(String type, int beforeTokens, int afterTokens) {
            if ("auto_compact".equals(type) || "reactive_compact".equals(type)) {
                sendCompactStart(sessionId);
                sendCompactComplete(sessionId, type, beforeTokens - afterTokens);
            }
        }

        @Override
        public void onTokenBudgetNudge(int pct, int currentTokens, int budgetTokens) {
            push(sessionId, "token_budget_nudge",
                    Map.of("pct", pct, "currentTokens", currentTokens, "budgetTokens", budgetTokens));
        }
    }

    /**
     * #2 权限响应 → /app/permission
     */
    @MessageMapping("/permission")
    public void handlePermissionResponse(@Payload ClientMessage.PermissionResponsePayload resp,
                                          Principal principal) {
        String sessionId = resolveSessionId(principal);
        log.info("WS permission_response: sessionId={}, toolUseId={}, decision={}",
                sessionId, resp.toolUseId(), resp.decision());

        // 根据用户决策构建 PermissionDecision
        PermissionBehavior behavior;
        try {
            behavior = PermissionBehavior.valueOf(resp.decision().toUpperCase());
        } catch (IllegalArgumentException e) {
            // 处理 "allow_always" 等非标准值
            behavior = resp.decision().toLowerCase().contains("allow")
                    ? PermissionBehavior.ALLOW : PermissionBehavior.DENY;
        }

        PermissionDecision decision;
        if (behavior == PermissionBehavior.ALLOW) {
            RuleScope scope = "global".equals(resp.scope()) ? RuleScope.GLOBAL
                    : "project".equals(resp.scope()) ? RuleScope.PROJECT
                    : RuleScope.SESSION;
            decision = PermissionDecision.allow(
                    com.aicodeassistant.model.PermissionDecisionReason.OTHER,
                    null
            ).withRemember(resp.remember(), scope);
        } else {
            decision = PermissionDecision.denyByMode("User denied");
        }

        permissionPipeline.resolvePermission(resp.toolUseId(), decision);
    }

    /**
     * #2b 权限冒泡响应 → /app/permission-bubble
     * Swarm Worker 权限冒泡决策回调。
     */
    @MessageMapping("/permission-bubble")
    public void handlePermissionBubbleResponse(@Payload Map<String, Object> payload,
                                                Principal principal) {
        String sessionId = resolveSessionId(principal);
        String requestId = (String) payload.get("requestId");
        boolean approved = Boolean.TRUE.equals(payload.get("approved"));
        log.info("WS permission_bubble_response: sessionId={}, requestId={}, approved={}",
                sessionId, requestId, approved);
        if (requestId != null && leaderPermissionBridge != null) {
            leaderPermissionBridge.resolvePermission(requestId, approved);
        }
    }

    /**
     * #3 中断当前回合 → /app/interrupt
     */
    @MessageMapping("/interrupt")
    public void handleInterrupt(@Payload(required = false) Map<String, Object> payload,
                                 Principal principal) {
        String sessionId = resolveSessionId(principal);
        boolean isSubmitInterrupt = payload != null
                && Boolean.TRUE.equals(payload.get("isSubmitInterrupt"));
        AbortReason reason = isSubmitInterrupt
                ? AbortReason.SUBMIT_INTERRUPT
                : AbortReason.USER_INTERRUPT;

        log.info("WS interrupt: sessionId={}, reason={}", sessionId, reason);
        queryEngine.abort(sessionId, reason);

        // 推送 interrupt_ack 到前端
        push(sessionId, "interrupt_ack", Map.of("reason", reason.name()));
    }

    /**
     * WebSocket 断连监听 — 主动中止孤立会话的 QueryEngine 循环。
     * <p>
     * 当前端刷新或关闭页面时，STOMP 断连会触发此事件。
     * 由于 WebSocketSessionManager 已经清理了 principal 映射，
     * 这里通过 transport sessionId 找到 app sessionId 并触发 abort。
     */
    @EventListener
    public void handleWebSocketDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        // 从 session attributes 中获取 app sessionId
        Map<String, Object> attrs = accessor.getSessionAttributes();
        String appSessionId = attrs != null ? (String) attrs.get("sessionId") : null;
        if (appSessionId != null) {
            log.info("WebSocket disconnect detected, aborting QueryEngine: sessionId={}", appSessionId);
            queryEngine.abort(appSessionId, AbortReason.SESSION_DISCONNECTED);
        }
    }

    /**
     * #4 切换模型 → /app/model
     */
    @MessageMapping("/model")
    public void handleSetModel(@Payload ClientMessage.SetModelPayload payload,
                                Principal principal) {
        String sessionId = resolveSessionId(principal);
        log.info("WS set_model: sessionId={}, model={}", sessionId, payload.model());
        // Validate model exists via ModelRegistry capabilities
        try {
            modelRegistry.getCapabilities(payload.model());
            push(sessionId, "model_changed", Map.of("model", payload.model()));
        } catch (Exception e) {
            push(sessionId, "error", Map.of(
                    "code", "INVALID_MODEL",
                    "message", "Unsupported model: " + payload.model(),
                    "retryable", false));
        }
    }

    /**
     * #5 切换权限模式 → /app/permission-mode
     */
    @MessageMapping("/permission-mode")
    public void handleSetPermissionMode(@Payload ClientMessage.SetPermissionModePayload payload,
                                         Principal principal) {
        String sessionId = resolveSessionId(principal);
        log.info("WS set_permission_mode: sessionId={}, mode={}", sessionId, payload.mode());
        try {
            com.aicodeassistant.model.PermissionMode mode =
                    com.aicodeassistant.model.PermissionMode.valueOf(payload.mode());
            permissionModeManager.setMode(sessionId, mode);
            push(sessionId, "permission_mode_changed", Map.of("mode", payload.mode()));
        } catch (IllegalArgumentException e) {
            log.warn("Invalid permission mode: {}", payload.mode());
            push(sessionId, "error", Map.of("message", "Invalid permission mode: " + payload.mode()));
        }
    }

    /**
     * #6 Slash 命令 → /app/command
     */
    @MessageMapping("/command")
    public void handleSlashCommand(@Payload ClientMessage.SlashCommandPayload payload,
                                    Principal principal) {
        String sessionId = resolveSessionId(principal);
        log.info("WS slash_command: sessionId={}, command=/{} {}", sessionId,
                payload.command(), payload.args());
        try {
            var cmd = commandRegistry.getCommand(payload.command());
            CommandContext ctx = CommandContext.of(
                    sessionId, ".", null, null);
            CommandResult cmdResult = cmd.execute(payload.args(), ctx);
            if (cmdResult.isSuccess()) {
                switch (cmdResult.type()) {
                    case TEXT -> {
                        if (cmdResult.value() != null) {
                            push(sessionId, "command_result", Map.of(
                                    "command", payload.command(),
                                    "resultType", "text",
                                    "output", cmdResult.value()));
                        }
                    }
                    case JSX -> {
                        // JSX 结果: value=null, data=结构化数据
                        push(sessionId, "command_result", Map.of(
                                "command", payload.command(),
                                "resultType", "jsx",
                                "data", cmdResult.data()));
                    }
                    case COMPACT -> {
                        // COMPACT 结果: value=displayText, data=压缩元数据
                        push(sessionId, "compact_complete", Map.of(
                                "displayText", cmdResult.value() != null ? cmdResult.value() : "",
                                "compactionData", cmdResult.data()));
                    }
                    case SKIP -> { /* 无操作 */ }
                    default -> log.warn("Unhandled command result type: {}", cmdResult.type());
                }
                // ★ 斜杠命令可能变更会话列表（如 /clear 创建新会话），通知前端刷新
                try { sendSessionListUpdated(sessionId); } catch (Exception ex) {
                    log.debug("Failed to push session_list_updated after slash command: {}", ex.getMessage());
                }
            } else {
                push(sessionId, "error", Map.of(
                        "code", "COMMAND_ERROR",
                        "message", cmdResult.error() != null ? cmdResult.error() : "Command failed",
                        "retryable", false));
            }
        } catch (CommandRegistry.CommandNotFoundException e) {
            push(sessionId, "error", Map.of(
                    "code", "COMMAND_NOT_FOUND",
                    "message", "Unknown command: /" + payload.command(),
                    "retryable", false));
        }
    }

    /**
     * #7 MCP 操作 → /app/mcp
     */
    @MessageMapping("/mcp")
    public void handleMcpOperation(@Payload ClientMessage.McpOperationPayload payload,
                                    Principal principal) {
        String sessionId = resolveSessionId(principal);
        log.info("WS mcp_operation: sessionId={}, op={}, serverId={}",
                sessionId, payload.operation(), payload.serverId());
        try {
            switch (payload.operation()) {
                case "connect" -> {
                    // McpClientManager uses addServer / name-based lookup
                    log.info("MCP connect request: serverId={}", payload.serverId());
                    mcpClientManager.getConnection(payload.serverId())
                            .ifPresentOrElse(
                                    conn -> push(sessionId, "notification", Map.of(
                                            "key", "mcp-connect", "level", "info",
                                            "message", "MCP server already connected: " + payload.serverId(),
                                            "timeout", 3000)),
                                    () -> push(sessionId, "error", Map.of(
                                            "code", "MCP_NOT_FOUND",
                                            "message", "MCP server not found: " + payload.serverId(),
                                            "retryable", false)));
                }
                case "disconnect" -> {
                    mcpClientManager.removeServer(payload.serverId());
                    push(sessionId, "notification", Map.of(
                            "key", "mcp-disconnect", "level", "info",
                            "message", "MCP server disconnected: " + payload.serverId(),
                            "timeout", 3000));
                }
                case "refresh" -> {
                    mcpClientManager.restartServer(payload.serverId());
                    push(sessionId, "notification", Map.of(
                            "key", "mcp-refresh", "level", "info",
                            "message", "MCP server refreshed: " + payload.serverId(),
                            "timeout", 3000));
                }
                case "list" -> push(sessionId, "mcp_status",
                        Map.of("servers", mcpClientManager.listConnections()));
                default -> push(sessionId, "error", Map.of(
                        "code", "INVALID_MCP_OP",
                        "message", "Unknown MCP operation: " + payload.operation(),
                        "retryable", false));
            }
        } catch (Exception e) {
            log.error("MCP operation failed: op={}, serverId={}", payload.operation(), payload.serverId(), e);
            push(sessionId, "error", Map.of(
                    "code", "MCP_ERROR",
                    "message", e.getMessage(),
                    "retryable", false));
        }
    }

    /**
     * #8 回退文件 → /app/rewind
     * F2 实装：执行真实文件恢复后再推送结果。
     */
    @MessageMapping("/rewind")
    public void handleRewindFiles(@Payload ClientMessage.RewindFilesPayload payload,
                                   Principal principal) {
        String sessionId = resolveSessionId(principal);
        log.info("WS rewind_files: sessionId={}, messageId={}, files={}",
                sessionId, payload.messageId(), payload.filePaths());

        var result = fileHistoryService.rewindFiles(
                sessionId, payload.messageId(), payload.filePaths());

        push(sessionId, "rewind_complete", Map.of(
                "messageId", payload.messageId(),
                "success", result.success(),
                "restoredFiles", result.restoredFiles(),
                "skippedFiles", result.skippedFiles(),
                "errors", result.errors(),
                "files", payload.filePaths() != null ? payload.filePaths() : List.of()));
    }

    /**
     * #9 AI 反向提问响应 → /app/elicitation
     */
    @MessageMapping("/elicitation")
    public void handleElicitationResponse(@Payload ClientMessage.ElicitationResponsePayload payload,
                                           Principal principal) {
        String sessionId = resolveSessionId(principal);
        log.info("WS elicitation_response: sessionId={}, requestId={}", sessionId, payload.requestId());
        if (payload.answer() == null) {
            // null answer = user cancelled
            elicitationService.cancelElicitation(payload.requestId());
        } else {
            elicitationService.resolveElicitation(payload.requestId(), payload.answer());
        }
    }

    /**
     * #10 心跳 → /app/ping
     */
    @MessageMapping("/ping")
    public void handlePing(Principal principal) {
        String sessionId = resolveSessionId(principal);
        sendPong(sessionId);
    }

    /**
     * #11 绑定会话 → /app/bind-session
     */
    @MessageMapping("/bind-session")
    public void handleBindSession(@Payload Map<String, String> payload, Principal principal) {
        String sessionId = payload.get("sessionId");
        if (sessionId != null && principal != null) {
            wsSessionManager.bindSession(principal.getName(), sessionId);
            log.info("WS bind-session: principal={}, sessionId={}", principal.getName(), sessionId);

            // ── 新增: 推送 session_restored ──
            try {
                Optional<SessionData> dataOpt = sessionManager.loadSession(sessionId);
                if (dataOpt.isPresent()) {
                    SessionData data = dataOpt.get();
                    Map<String, Object> metadata = Map.of(
                        "sessionId", sessionId,
                        "model", data.model() != null ? data.model() : "",
                        "permissionMode", "DEFAULT",
                        "status", data.status() != null ? data.status() : "idle"
                    );
                    push(sessionId, "session_restored", Map.of(
                        "messages", convertMessagesForWs(data.messages()),
                        "metadata", metadata
                    ));
                    log.info("Pushed session_restored: sessionId={}, messages={}",
                        sessionId, data.messages().size());
                }
            } catch (Exception e) {
                log.warn("Failed to push session_restored for {}: {}", sessionId, e.getMessage());
            }
        } else {
            log.warn("WS bind-session failed: principal={}, sessionId={}",
                    principal != null ? principal.getName() : "null", sessionId);
        }
    }

    // ───── 辅助方法 ─────

    /**
     * 从 Principal 解析应用层 sessionId。
     */
    private String resolveSessionId(Principal principal) {
        if (principal == null) return "unknown";
        String sessionId = wsSessionManager.getSessionForPrincipal(principal.getName());
        return sessionId != null ? sessionId : principal.getName();
    }

    // ───── WS 消息序列化辅助方法 ─────

    /**
     * 将 Message 列表转换为前端兼容的 Map 列表。
     * <p>
     * 解决两个关键问题:
     * 1. Map<String,Object> 导致 Jackson 丢失 @JsonTypeInfo 的 type 字段
     * 2. Instant 需转为 epoch millis (前端 timestamp: number)
     * 3. ContentBlock 字段名映射 (backend id/name → frontend toolUseId/toolName)
     */
    private List<Map<String, Object>> convertMessagesForWs(List<com.aicodeassistant.model.Message> messages) {
        if (messages == null) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (var msg : messages) {
            Map<String, Object> map = new LinkedHashMap<>();
            switch (msg) {
                case com.aicodeassistant.model.Message.UserMessage u -> {
                    map.put("type", "user");
                    map.put("uuid", u.uuid());
                    map.put("timestamp", u.timestamp() != null ? u.timestamp().toEpochMilli() : 0);
                    map.put("content", convertContentBlocksForWs(u.content()));
                    if (u.toolUseResult() != null) map.put("toolUseResult", u.toolUseResult());
                }
                case com.aicodeassistant.model.Message.AssistantMessage a -> {
                    map.put("type", "assistant");
                    map.put("uuid", a.uuid());
                    map.put("timestamp", a.timestamp() != null ? a.timestamp().toEpochMilli() : 0);
                    map.put("content", convertContentBlocksForWs(a.content()));
                    map.put("stopReason", a.stopReason());
                    if (a.usage() != null) {
                        map.put("usage", Map.of(
                            "inputTokens", a.usage().inputTokens(),
                            "outputTokens", a.usage().outputTokens(),
                            "cacheReadInputTokens", a.usage().cacheReadInputTokens(),
                            "cacheCreationInputTokens", a.usage().cacheCreationInputTokens()
                        ));
                    }
                }
                case com.aicodeassistant.model.Message.SystemMessage s -> {
                    map.put("type", "system");
                    map.put("uuid", s.uuid());
                    map.put("timestamp", s.timestamp() != null ? s.timestamp().toEpochMilli() : 0);
                    map.put("content", s.content());
                }
            }
            result.add(map);
        }
        return result;
    }

    /**
     * 将 ContentBlock 列表转换为前端兼容的 Map 列表。
     * 确保每个 block 包含 type 字段，并将字段名映射为前端期望格式。
     */
    private List<Map<String, Object>> convertContentBlocksForWs(List<ContentBlock> blocks) {
        if (blocks == null) return List.of();
        return blocks.stream().map(block -> {
            Map<String, Object> map = new LinkedHashMap<>();
            switch (block) {
                case ContentBlock.TextBlock t -> {
                    map.put("type", "text");
                    map.put("text", t.text());
                }
                case ContentBlock.ToolUseBlock tu -> {
                    map.put("type", "tool_use");
                    map.put("toolUseId", tu.id());
                    map.put("toolName", tu.name());
                    map.put("input", tu.input());
                }
                case ContentBlock.ToolResultBlock tr -> {
                    map.put("type", "tool_result");
                    map.put("toolUseId", tr.toolUseId());
                    map.put("content", tr.content());
                    map.put("isError", tr.isError());
                }
                case ContentBlock.ThinkingBlock th -> {
                    map.put("type", "thinking");
                    map.put("thinking", th.thinking());
                }
                case ContentBlock.ImageBlock img -> {
                    map.put("type", "image");
                    map.put("mediaType", img.mediaType());
                    map.put("base64Data", img.base64Data());
                }
                case ContentBlock.RedactedThinkingBlock r -> {
                    map.put("type", "redacted_thinking");
                }
            }
            return map;
        }).toList();
    }
}
