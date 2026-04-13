package com.aicodeassistant.websocket;

import com.aicodeassistant.context.ProjectContextService;
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
import org.springframework.stereotype.Controller;

import java.nio.file.Path;
import java.security.Principal;
import java.time.Instant;
import java.util.*;

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
                                PermissionModeManager permissionModeManager) {
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
    public void sendSessionRestored(String sessionId, Object messages, Object metadata) {
        push(sessionId, "session_restored",
                Map.of("messages", messages, "metadata", metadata));
    }

    // ───── #25: 心跳 ─────

    /** #25 心跳响应 */
    public void sendPong(String sessionId) {
        push(sessionId, "pong", Map.of());
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
        log.info("WS user_message: sessionId={}, text={}", sessionId,
                msg.text() != null ? msg.text().substring(0, Math.min(50, msg.text().length())) : "");

        // 在 Virtual Thread 中执行 QueryEngine
        Thread.startVirtualThread(() -> {
            try {
                executeQuery(sessionId, msg.text());
            } catch (Exception e) {
                log.error("QueryEngine 执行异常: sessionId={}", sessionId, e);
                sendError(sessionId, "query_error", e.getMessage() != null ? e.getMessage() : "Unknown error", true);
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
        SystemPromptConfig promptConfig = SystemPromptConfig.defaults();
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
                10, "websocket"
        );

        // 4. 初始化循环状态 + 添加用户消息
        // 确保 WebSocket 会话使用 DEFAULT 权限模式（允许交互式权限确认）
        if (!permissionModeManager.hasExplicitMode(sessionId)) {
            permissionModeManager.setMode(sessionId, com.aicodeassistant.model.PermissionMode.DEFAULT);
        }
        log.debug("WebSocket session permissionMode: {}",
                permissionModeManager.getMode(sessionId));
        ToolUseContext toolUseContext = ToolUseContext.of(
                workingDir.toString(), sessionId)
                .withPermissionNotifier(this);
        QueryLoopState state = new QueryLoopState(new ArrayList<>(), toolUseContext);
        state.addMessage(new Message.UserMessage(
                UUID.randomUUID().toString(), Instant.now(),
                List.of(new ContentBlock.TextBlock(userText)),
                null, null));

        // 5. 执行查询，通过 WsMessageHandler 流式推送
        log.info("QueryEngine 开始执行: sessionId={}, model={}", sessionId, model);
        WsMessageHandler handler = new WsMessageHandler(sessionId);
        QueryEngine.QueryResult result = queryEngine.execute(config, state, handler);

        // 6. 发送完成消息
        Usage totalUsage = result.totalUsage() != null ? result.totalUsage() : Usage.zero();
        sendMessageComplete(sessionId, totalUsage, result.stopReason());
        log.info("QueryEngine 完成: sessionId={}, stopReason={}, turns={}",
                sessionId, result.stopReason(), result.turnCount());
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
            sendCostUpdate(sessionId, 0.0, 0.0, usage);
        }

        @Override
        public void onCompactEvent(String type, int beforeTokens, int afterTokens) {
            if ("auto_compact".equals(type) || "reactive_compact".equals(type)) {
                sendCompactStart(sessionId);
                sendCompactComplete(sessionId, type, beforeTokens - afterTokens);
            }
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
        push(sessionId, "permission_mode_changed", Map.of("mode", payload.mode()));
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
            if (cmdResult.isSuccess() && cmdResult.value() != null) {
                push(sessionId, "command_result", Map.of(
                        "command", payload.command(),
                        "output", cmdResult.value()));
            } else if (!cmdResult.isSuccess()) {
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
                        "messages", data.messages(),
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
}
