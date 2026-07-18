package com.aicodeassistant.websocket;

import com.aicodeassistant.context.ProjectContextService;
import com.aicodeassistant.service.ActivityRepository;
import com.aicodeassistant.service.CostTrackerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.aicodeassistant.engine.AbortReason;
import com.aicodeassistant.engine.ElicitationService;
import com.aicodeassistant.engine.QueryConfig;
import com.aicodeassistant.engine.QueryEngine;
import com.aicodeassistant.engine.QueryLoopState;
import com.aicodeassistant.engine.QueryMessageHandler;
import com.aicodeassistant.command.Command;
import com.aicodeassistant.command.CommandContext;
import com.aicodeassistant.command.CommandRegistry;
import com.aicodeassistant.command.CommandResult;
import com.aicodeassistant.command.CommandType;
import com.aicodeassistant.command.PromptCommand;
import com.aicodeassistant.history.FileHistoryService;
import com.aicodeassistant.mcp.McpClientManager;
import com.aicodeassistant.permission.PermissionModeManager;
import com.aicodeassistant.permission.PermissionNotifier;
import com.aicodeassistant.llm.LlmProviderRegistry;
import com.aicodeassistant.llm.ModelCapabilities;
import com.aicodeassistant.llm.ModelRegistry;
import com.aicodeassistant.llm.ThinkingConfig;
import com.aicodeassistant.llm.VisionModelRouter;
import com.aicodeassistant.interaction.InteractionRequest;
import com.aicodeassistant.interaction.InteractionCreatedEvent;
import com.aicodeassistant.interaction.InteractionTerminalEvent;
import com.aicodeassistant.interaction.InteractionView;
import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.aicodeassistant.model.Usage;
import com.aicodeassistant.prompt.EffectiveSystemPromptBuilder;
import com.aicodeassistant.prompt.SystemPromptConfig;
import com.aicodeassistant.run.RunEnvelopeRepository;
import com.aicodeassistant.run.RunEventRepository;
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
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.stereotype.Controller;
import org.springframework.scheduling.annotation.Scheduled;

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
    private static final int WS_PROTOCOL_VERSION = 3;

    private final SimpMessagingTemplate messaging;
    private final WebSocketSessionManager wsSessionManager;
    private final QueryEngine queryEngine;
    private final ToolRegistry toolRegistry;
    private final LlmProviderRegistry providerRegistry;
    private final EffectiveSystemPromptBuilder systemPromptBuilder;
    private final ModelRegistry modelRegistry;            // P0-1 新增
    private final VisionModelRouter visionModelRouter;   // 视觉模型路由
    private final SessionManager sessionManager;           // P1-0 新增
    private final ElicitationService elicitationService;   // P1-5 新增
    private final CommandRegistry commandRegistry;             // P1 Slash命令
    private final McpClientManager mcpClientManager;           // P1 MCP操作
    private final FileHistoryService fileHistoryService;       // P1 文件回退
    private final ProjectContextService projectContextService;  // F5 项目上下文
    private final PermissionModeManager permissionModeManager;    // P1-03 权限模式
    private final CostTrackerService costTrackerService;                                          // F2 费用追踪
    private final ActivityRepository activityRepository;                                            // Activity 持久化
    private final ObjectMapper objectMapper;                                                        // JSON 序列化
    private final com.aicodeassistant.permission.PermissionInteractionService permissionInteractions;
    private final RunEnvelopeRepository runEnvelopeRepository;
    private final RunEventRepository runEventRepository;
    private final com.aicodeassistant.run.RunRecoveryProjectionService runRecoveryProjectionService;
    private final com.aicodeassistant.run.RunExecutionRegistry runExecutions;
    private final com.aicodeassistant.run.RunTerminationCoordinator runTermination;

    /** 会话级查询运行守卫 — 防止同一会话并发执行多个 QueryEngine */
    private final ConcurrentHashMap<String, AtomicBoolean> sessionQueryRunning = new ConcurrentHashMap<>();

    /** 会话级模型选择 — 记录每个会话当前使用的模型 */
    private final ConcurrentHashMap<String, String> sessionModels = new ConcurrentHashMap<>();

    public WebSocketController(SimpMessagingTemplate messaging,
                                WebSocketSessionManager wsSessionManager,
                                QueryEngine queryEngine,
                                ToolRegistry toolRegistry,
                                LlmProviderRegistry providerRegistry,
                                EffectiveSystemPromptBuilder systemPromptBuilder,
                                ModelRegistry modelRegistry,
                                SessionManager sessionManager,
                                ElicitationService elicitationService,
                                CommandRegistry commandRegistry,
                                McpClientManager mcpClientManager,
                                FileHistoryService fileHistoryService,
                                ProjectContextService projectContextService,
                                PermissionModeManager permissionModeManager,
                                CostTrackerService costTrackerService,
                                ActivityRepository activityRepository,
                                ObjectMapper objectMapper,
                                VisionModelRouter visionModelRouter,
                                com.aicodeassistant.permission.PermissionInteractionService permissionInteractions,
                                RunEnvelopeRepository runEnvelopeRepository,
                                RunEventRepository runEventRepository,
                                com.aicodeassistant.run.RunRecoveryProjectionService runRecoveryProjectionService,
                                com.aicodeassistant.run.RunExecutionRegistry runExecutions,
                                com.aicodeassistant.run.RunTerminationCoordinator runTermination) {
        this.messaging = messaging;
        this.wsSessionManager = wsSessionManager;
        this.queryEngine = queryEngine;
        this.toolRegistry = toolRegistry;
        this.providerRegistry = providerRegistry;
        this.systemPromptBuilder = systemPromptBuilder;
        this.modelRegistry = modelRegistry;
        this.sessionManager = sessionManager;
        this.elicitationService = elicitationService;
        this.commandRegistry = commandRegistry;
        this.mcpClientManager = mcpClientManager;
        this.fileHistoryService = fileHistoryService;
        this.projectContextService = projectContextService;
        this.permissionModeManager = permissionModeManager;
        this.costTrackerService = costTrackerService;
        this.activityRepository = activityRepository;
        this.objectMapper = objectMapper;
        this.visionModelRouter = visionModelRouter;
        this.permissionInteractions = permissionInteractions;
        this.runEnvelopeRepository = runEnvelopeRepository;
        this.runEventRepository = runEventRepository;
        this.runRecoveryProjectionService = runRecoveryProjectionService;
        this.runExecutions = runExecutions;
        this.runTermination = runTermination;
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
        boolean isCritical = isCriticalMessage(type);
        Set<String> principals = wsSessionManager.getPrincipalsForSession(sessionId, isCritical);
        if (principals.isEmpty()) {
            log.warn("push skipped: eventType={}, sessionId={}, runId=unknown, seq=unknown, isRecoverable={}",
                    type,sessionId,isCritical);
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

        principals.forEach(principal -> {
            Map<String, Object> routed = new LinkedHashMap<>(message);
            routed.put("_sessionId", sessionId);
            routed.put("_bindingEpoch", wsSessionManager.getBindingEpochForPrincipal(principal));
            messaging.convertAndSendToUser(principal, "/queue/messages", routed);
        });
    }

    /**
     * 推送 Map payload（扁平结构）。
     */
    private void push(String sessionId, String type, Map<String, Object> fields) {
        boolean isCritical = isCriticalMessage(type);
        Set<String> principals = wsSessionManager.getPrincipalsForSession(sessionId, isCritical);
        if (principals.isEmpty()) {
            if (isCritical) {
                log.warn("push unavailable: eventType={}, sessionId={}, runId={}, seq={}, isRecoverable=true",
                        type,sessionId,fields.getOrDefault("runId","unknown"),fields.getOrDefault("seq","unknown"));
            } else {
                log.debug("push unavailable: eventType={}, sessionId={}, runId={}, seq={}, isRecoverable=false",
                        type,sessionId,fields.getOrDefault("runId","unknown"),fields.getOrDefault("seq","unknown"));
            }
            return;
        }

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", type);
        message.put("ts", System.currentTimeMillis());
        message.putAll(fields);

        if ("stream_delta".equals(type) || "thinking_delta".equals(type)) {
            log.trace("push {} to principals={}, len={}", type, principals.size(), fields.getOrDefault("delta", "").toString().length());
        } else {
            log.info("push({}) to principals={}, sessionId={}", type, principals.size(), sessionId);
        }
        principals.forEach(principal -> {
            Map<String, Object> routed = new LinkedHashMap<>(message);
            routed.put("_sessionId", sessionId);
            routed.put("_bindingEpoch", wsSessionManager.getBindingEpochForPrincipal(principal));
            messaging.convertAndSendToUser(principal, "/queue/messages", routed);
        });
    }

    private void pushToPrincipal(String principal, String type, Map<String, Object> fields) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", type);
        message.put("ts", System.currentTimeMillis());
        message.putAll(fields);
        messaging.convertAndSendToUser(principal, "/queue/messages", message);
    }

    private static final Set<String> CRITICAL_MESSAGE_TYPES = Set.of(
        "permission_request", "tool_result", "tool_finished", "message_complete",
        "run_completed", "run_failed", "error", "cost_update",
        "interaction_created", "interaction_terminal", "interaction_updated",
        "permission_mode_changed", "tool_use_start"
    );

    private boolean isCriticalMessage(String type) {
        return CRITICAL_MESSAGE_TYPES.contains(type);
    }

    @EventListener
    public void onInteractionCreated(InteractionCreatedEvent event) {
        try {
            deliverInteraction(event.request(), InteractionDelivery.INITIAL);
        } catch (RuntimeException deliveryFailure) {
            log.warn("Interaction initial delivery deferred: interactionId={}, error={}",
                    event.request().interactionId(), deliveryFailure.getMessage());
        }
    }

    @EventListener
    public void onInteractionTerminal(InteractionTerminalEvent event) {
        try {
            pushInteractionView(event.request(), "interaction_terminal");
        } catch (RuntimeException deliveryFailure) {
            log.warn("Interaction terminal notification unavailable: interactionId={}, error={}",
                    event.request().interactionId(), deliveryFailure.getMessage());
        }
    }

    private enum InteractionDelivery { INITIAL, RETRY, RECOVERY }

    private void deliverInteraction(InteractionRequest request, InteractionDelivery delivery) {
        if (permissionInteractions == null) return;
        String transport = wsSessionManager.getTransportIdsForSession(request.sessionId()).stream()
                .findFirst().orElse(null);
        if (transport == null) {
            log.info("Interaction pending without bound transport: interactionId={}, sessionId={}",
                    request.interactionId(), request.sessionId());
            return;
        }
        boolean claimed;
        if (delivery == InteractionDelivery.RECOVERY) {
            request = permissionInteractions.prepareRecoveryDelivery(request.interactionId(), transport);
            claimed = request.status() == InteractionRequest.Status.PENDING;
        } else {
            claimed = delivery == InteractionDelivery.RETRY
                    ? permissionInteractions.claimRedelivery(request.interactionId(), request.dispatchAttempts(), transport)
                    : permissionInteractions.markInteractionDispatched(request.interactionId(), transport);
        }
        if (!claimed) return;
        pushInteractionView(permissionInteractions.findInteraction(request.interactionId()), "interaction_created");
    }

    private void pushInteractionView(InteractionRequest request, String type) {
        InteractionView view = permissionInteractions.view(request);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = objectMapper.convertValue(view, Map.class);
        push(request.sessionId(), type, payload);
    }

    /** 对已投递但未收到 ACK 的交互按 1/2/4 秒退避重投。 */
    @Scheduled(fixedDelay = 250)
    public void redeliverUnacknowledgedInteractions() {
        if (permissionInteractions == null) return;
        for (InteractionRequest request : permissionInteractions.redeliveryCandidates(Instant.now())) {
            try {
                deliverInteraction(request, InteractionDelivery.RETRY);
            } catch (Exception error) {
                log.warn("Interaction redelivery failed: interactionId={}, attempt={}, error={}",
                        request.interactionId(), request.dispatchAttempts() + 1, error.getMessage());
            }
        }
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
        // 生产环境始终注入持久化权威；null 分支只保留给隔离 Controller 测试和嵌入式构造器。
        if (permissionInteractions != null) return; // 生产环境只允许 InteractionCreatedEvent 触发投递。
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("toolUseId", toolUseId); payload.put("toolName", toolName);
        payload.put("input", input); payload.put("riskLevel", riskLevel); payload.put("reason", reason);
        payload.put("scopeOptions", ("Bash".equals(toolName) || "BashTool".equals(toolName))
                ? List.of("session") : List.of("session", "workspace"));
        push(sessionId, "permission_request", payload);
    }

    /** #6b 来自子代理的权限请求 — 转发到父会话 */
    @Override
    public void sendPermissionRequestFromChild(String parentSessionId, String childSessionId,
                                                String toolUseId, String toolName,
                                                Object input, String riskLevel, String reason) {
        Map<String,Object> fields=new HashMap<>();
        fields.put("toolUseId",toolUseId);fields.put("toolName",toolName);fields.put("input",input);
        fields.put("riskLevel",riskLevel);fields.put("reason",reason);fields.put("source","subagent");
        fields.put("childSessionId",childSessionId);fields.put("scopeOptions",List.of("session"));
        if (permissionInteractions != null) return; // delivered by the durable interaction event
        push(parentSessionId,"permission_request",fields);
    }

    /** #6c 工具权限被拒绝通知 — 前端应清除对应 changedFiles */
    @Override
    public void sendToolPermissionDenied(String sessionId, String toolUseId, String toolName) {
        push(sessionId, "tool_permission_denied",
                Map.of("toolUseId", toolUseId, "toolName", toolName));
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

    // ───── #28: tokenWarningStore ─────

    /**
     * Token 告警推送 — 向前端通知当前token使用率告警级别。
     *
     * @param sessionId 会话ID
     * @param level 告警级别（normal/warning/critical/trigger_compact）
     * @param currentTokens 当前使用token数
     * @param effectiveWindow 有效窗口大小
     * @param usagePercent 使用百分比（0-100）
     */
    public void pushTokenWarning(String sessionId, String level,
                                  int currentTokens, int effectiveWindow, double usagePercent) {
        push(sessionId, "token_warning",
                Map.of("currentTokens", currentTokens,
                       "maxTokens", effectiveWindow,
                       "usagePercent", usagePercent,
                       "warningLevel", level));
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

        // 提取图片附件 → ContentBlock.ImageBlock
        List<ContentBlock.ImageBlock> imageBlocks = new ArrayList<>();
        if (msg.attachments() != null) {
            for (ClientMessage.UserMessagePayload.Attachment att : msg.attachments()) {
                if (att != null && "image".equals(att.type()) && att.base64Data() != null && !att.base64Data().isEmpty()) {
                    imageBlocks.add(new ContentBlock.ImageBlock(
                            att.mediaType() != null ? att.mediaType() : "image/png",
                            att.base64Data()));
                }
            }
        }
        if (!imageBlocks.isEmpty()) {
            log.info("WS user_message attached {} image(s): sessionId={}", imageBlocks.size(), sessionId);
        }

        // ★ 图片附件校验：模型能力 + 单张大小硬上限 + 视觉模型自动路由
        String routedModelOverride = null;
        if (!imageBlocks.isEmpty()) {
            ImageValidationResult validationResult = validateImageAttachments(sessionId, imageBlocks);
            if (!validationResult.valid()) {
                sendError(sessionId, "image_validation_failed", validationResult.errorMessage(), false);
                running.set(false);
                return;
            }
            if (validationResult.routedModel() != null) {
                routedModelOverride = validationResult.routedModel();
                String originalModel = sessionModels.getOrDefault(sessionId, providerRegistry.getDefaultModel());
                ModelCapabilities routedCaps = modelRegistry.getCapabilities(routedModelOverride);
                push(sessionId, "model_routed", Map.of(
                        "originalModel", originalModel,
                        "routedModel", routedModelOverride,
                        "routedModelName", routedCaps.displayName(),
                        "reason", "当前模型不支持图片，已自动切换到 " + routedCaps.displayName()
                ));
            }
        }

        // 在 Virtual Thread 中执行 QueryEngine
        final List<ContentBlock.ImageBlock> imagesForQuery = imageBlocks;
        final String modelOverride = routedModelOverride;
        Thread.ofVirtual().name("zhiku-ws-query-" + sessionId).start(() -> {
            try {
                executeQuery(sessionId, msg.text(), imagesForQuery, modelOverride);
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
     * 执行普通用户查询（无图片附件）。使用全量工具和会话默认模型。
     */
    private void executeQuery(String sessionId, String userText) {
        executeQuery(sessionId, userText, List.of(), null);
    }

    /**
     * 执行普通用户查询。支持图片附件（多模态）。使用全量工具和会话默认模型。
     */
    private void executeQuery(String sessionId, String userText,
                              List<ContentBlock.ImageBlock> images) {
        executeQuery(sessionId, userText, images, null);
    }

    /**
     * 执行普通用户查询。支持图片附件（多模态）+ 模型覆盖（视觉路由用途）。
     *
     * @param modelOverride 模型覆盖（null=使用会话默认模型；非 null=本次请求使用指定模型，例如视觉路由后的目标模型）
     */
    private void executeQuery(String sessionId, String userText,
                              List<ContentBlock.ImageBlock> images,
                              String modelOverride) {
        // 0. 异步预加载项目上下文
        Path workingDir = Path.of(System.getProperty("user.dir"));
        projectContextService.ensureContext(workingDir);

        // 1. 组装全量工具池
        List<Tool> tools = toolRegistry.getEnabledTools();
        List<Map<String, Object>> toolDefs = toolRegistry.getToolDefinitions();

        // 2. 模型覆盖优先 → 会话模型 → 全局默认（★ 别名解析）
        String rawModel = modelOverride != null ? modelOverride
                : sessionModels.getOrDefault(sessionId, providerRegistry.getDefaultModel());
        String model = providerRegistry.resolveModelAlias(rawModel);
        log.info("executeQuery: sessionId={}, model={} (raw={}, override={}), images={}",
                sessionId, model, rawModel, modelOverride, images != null ? images.size() : 0);

        executeQueryInternal(sessionId, userText, images, tools, toolDefs, model);
    }

    /**
     * 执行 PROMPT 命令注入 LLM 对话。
     * 支持 PromptCommand 扩展字段：allowedTools 工具过滤、model 模型覆盖。
     *
     * @param sessionId        会话ID
     * @param promptText       PROMPT命令生成的提示词
     * @param allowedToolNames 允许的工具名称集合（null或空=全部工具）
     * @param modelOverride    模型覆盖（null=使用会话默认模型）
     */
    private void executePromptCommand(String sessionId, String promptText,
                                      Set<String> allowedToolNames, String modelOverride) {
        Path workingDir = Path.of(System.getProperty("user.dir"));
        projectContextService.ensureContext(workingDir);

        // 工具池组装：按 allowedToolNames 过滤
        List<Tool> tools;
        List<Map<String, Object>> toolDefs;
        if (allowedToolNames != null && !allowedToolNames.isEmpty()) {
            tools = toolRegistry.getEnabledTools().stream()
                    .filter(t -> allowedToolNames.contains(t.getName()))
                    .toList();
            toolDefs = tools.stream().map(Tool::toToolDefinition).toList();
        } else {
            tools = toolRegistry.getEnabledTools();
            toolDefs = toolRegistry.getToolDefinitions();
        }

        // 模型覆盖优先 → 会话模型 → 全局默认（★ 别名解析）
        String rawModel = modelOverride != null ? modelOverride
                : sessionModels.getOrDefault(sessionId, providerRegistry.getDefaultModel());
        String model = providerRegistry.resolveModelAlias(rawModel);

        executeQueryInternal(sessionId, promptText, List.of(), tools, toolDefs, model);
    }

    /**
     * 查询执行核心引擎。
     * 包含权限初始化、历史加载、QueryEngine执行、消息持久化、完成通知及异常保障。
     *
     * @param sessionId 会话ID
     * @param userText  用户文本（普通消息或PROMPT命令生成的提示词）
     * @param tools     工具列表（全量或过滤后子集）
     * @param toolDefs  工具API定义列表（与tools对应）
     * @param model     模型标识
     */
    private void executeQueryInternal(String sessionId, String userText,
                                      List<ContentBlock.ImageBlock> images,
                                      List<Tool> tools, List<Map<String, Object>> toolDefs,
                                      String model) {
        Path workingDir = Path.of(System.getProperty("user.dir"));

        // 构建系统提示
        SystemPromptConfig promptConfig = SystemPromptConfig.defaults()
                .withSessionId(sessionId);
        String systemPrompt = systemPromptBuilder.buildEffectiveSystemPrompt(
                promptConfig, tools, model, workingDir);

        // 构建 QueryConfig
        int contextWindow = getContextWindow(model);
        QueryConfig config = QueryConfig.withDefaults(
                model, systemPrompt, tools, toolDefs,
                QueryConfig.getRecommendedMaxTokens(modelRegistry, model),
                contextWindow,
                new ThinkingConfig.Adaptive(),
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

        // 注册消息增量持久化 listener：每条新消息进入 state 的瞬间即落库
        state.addMessageListener(msg -> {
            try {
                switch (msg) {
                    case Message.UserMessage user -> sessionManager.addMessageWithId(
                            user.uuid(), sessionId, "user", user.content(), null, 0, 0);
                    case Message.AssistantMessage assistant -> sessionManager.addMessageWithId(
                            assistant.uuid(), sessionId, "assistant", assistant.content(),
                            assistant.stopReason(),
                            assistant.usage() != null ? assistant.usage().inputTokens() : 0,
                            assistant.usage() != null ? assistant.usage().outputTokens() : 0);
                    case Message.SystemMessage system -> sessionManager.addMessageWithId(
                            system.uuid(), sessionId, "system", system.content(), null, 0, 0);
                }
            } catch (Exception e) {
                log.error("消息增量持久化失败, sessionId={}, msgId={}", sessionId, msg.uuid(), e);
                // 不抛出 —— 持久化失败不应中断 LLM 流；L615-L638 兜底可恢复
            }
        });

        // 组装用户消息 content：TextBlock + 所有 ImageBlock（多模态）
        List<ContentBlock> userContent = new ArrayList<>();
        userContent.add(new ContentBlock.TextBlock(userText != null ? userText : ""));
        if (images != null) {
            userContent.addAll(images);
        }
        state.addMessage(new Message.UserMessage(
                UUID.randomUUID().toString(), Instant.now(),
                userContent,
                null, null));

        // 5. 执行查询，通过 WsMessageHandler 流式推送
        log.info("QueryEngine 开始执行: sessionId={}, model={}", sessionId, model);
        WsMessageHandler handler = new WsMessageHandler(sessionId);
        QueryEngine.QueryResult result = null;
        try {
            result = queryEngine.execute(config, state, handler);

            // 6. ★ 幂等兜底：execute 正常返回后再补写一次，INSERT OR IGNORE 自动去重（与 listener 使用相同 UUID）
            try {
                List<Message> allMessages = result.messages();
                int newStartIndex = historyMessages.size();
                List<Message> newMessages = allMessages.subList(
                        Math.min(newStartIndex, allMessages.size()), allMessages.size());
                int fallbackCount = 0;
                for (Message msg : newMessages) {
                    switch (msg) {
                        case Message.UserMessage user -> {
                            sessionManager.addMessageWithId(
                                    user.uuid(), sessionId, "user", user.content(), null, 0, 0);
                            fallbackCount++;
                        }
                        case Message.AssistantMessage assistant -> {
                            sessionManager.addMessageWithId(
                                    assistant.uuid(), sessionId, "assistant", assistant.content(),
                                    assistant.stopReason(),
                                    assistant.usage() != null ? assistant.usage().inputTokens() : 0,
                                    assistant.usage() != null ? assistant.usage().outputTokens() : 0);
                            fallbackCount++;
                        }
                        case Message.SystemMessage system -> {
                            sessionManager.addMessageWithId(
                                    system.uuid(), sessionId, "system", system.content(), null, 0, 0);
                            fallbackCount++;
                        }
                    }
                }
                if (fallbackCount > 0) {
                    log.info("WS 兜底补写 {} 条消息（INSERT OR IGNORE 去重）, sessionId={}", fallbackCount, sessionId);
                }
            } catch (Exception e) {
                log.error("WS 兜底持久化失败, sessionId={}", sessionId, e);
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
        } catch (Throwable t) {
            log.error("Query execution failed: sessionId={}, exType={}, message={}",
                    sessionId, t.getClass().getName(), t.getMessage(), t);
            if (t instanceof Exception e) {
                handler.onError(e);
            } else {
                handler.onError(new RuntimeException("Internal error: " + t.getClass().getName(), t));
            }
        } finally {
            // ★ 保障: 即使异常也确保发送 message_complete，防止前端卡在加载态
            log.debug("Query execution finished: sessionId={}, resultPresent={}", sessionId, result != null);
            if (result == null) {
                sendMessageComplete(sessionId, Usage.zero(), "error");
            }
        }
    }

    private int getContextWindow(String model) {
        return modelRegistry.getContextWindowForModel(model);
    }

    /** 单张图片 base64 解码后的大小硬上限（10MB） */
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;

    /**
     * 图片校验结果。
     * <ul>
     *   <li>{@code valid=true, routedModel=null}：校验通过，使用会话默认模型</li>
     *   <li>{@code valid=true, routedModel!=null}：校验通过，本次请求需路由到指定视觉模型</li>
     *   <li>{@code valid=false}：校验失败，{@code errorMessage} 为提示信息</li>
     * </ul>
     */
    private record ImageValidationResult(
            boolean valid,
            String errorMessage,
            String routedModel
    ) {
        static ImageValidationResult ok() { return new ImageValidationResult(true, null, null); }
        static ImageValidationResult routed(String model) { return new ImageValidationResult(true, null, model); }
        static ImageValidationResult error(String msg) { return new ImageValidationResult(false, msg, null); }
    }

    /**
     * 校验图片附件：
     * 1. 当前模型不支持图片时尝试路由到视觉模型（同 Provider 优先 → 全局兜底）
     * 2. 图片数量不超过（路由后）模型 maxImages
     * 3. 单张图片解码后大小不超过 10MB
     *
     * @return 校验结果（包含错误信息或路由目标模型）
     */
    private ImageValidationResult validateImageAttachments(String sessionId, List<ContentBlock.ImageBlock> images) {
        // 解析当前会话使用的模型（与 executeQuery 一致）
        String rawModel = sessionModels.getOrDefault(sessionId, providerRegistry.getDefaultModel());
        String model = providerRegistry.resolveModelAlias(rawModel);

        ModelCapabilities caps;
        try {
            caps = modelRegistry.getCapabilities(model);
        } catch (Exception e) {
            log.warn("Failed to load capabilities for model {}, falling back to DEFAULT: {}", model, e.getMessage());
            caps = ModelCapabilities.DEFAULT;
        }

        // 核心变化：不再直接报错，尝试路由到视觉模型
        String effectiveModel = model;
        if (!caps.supportsImages()) {
            String visionModel = visionModelRouter.resolveVisionModel(model);
            if (visionModel == null) {
                return ImageValidationResult.error("当前模型不支持图片输入且无可用视觉模型");
            }
            effectiveModel = visionModel;
            caps = modelRegistry.getCapabilities(effectiveModel);
        }

        // 使用路由后的模型能力进行数量校验
        if (images.size() > caps.maxImages()) {
            return ImageValidationResult.error("图片数量超出限制：当前模型最多支持 " + caps.maxImages() + " 张图片，本次提交了 " + images.size() + " 张");
        }

        // 单张大小硬上限：base64 解码后约为字符串长度 * 3/4
        for (int i = 0; i < images.size(); i++) {
            ContentBlock.ImageBlock img = images.get(i);
            String b64 = img.base64Data();
            if (b64 == null) continue;
            long approxBytes = (long) b64.length() * 3L / 4L;
            if (approxBytes > MAX_IMAGE_BYTES) {
                return ImageValidationResult.error("第 " + (i + 1) + " 张图片大小超出限制（约 "
                        + (approxBytes / (1024 * 1024)) + "MB），单张图片不得超过 10MB");
            }
        }

        if (!effectiveModel.equals(model)) {
            return ImageValidationResult.routed(effectiveModel);
        }
        return ImageValidationResult.ok();
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
            // 发送完整的工具 input 到前端（此时 input 已完全接收）
            push(sessionId, "tool_use_input",
                    Map.of("toolUseId", toolUseId, "toolName", toolUse.name(), "input", toolUse.input()));
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
        sendError(sessionId, "interaction_rest_required",
                "协议 v2 的交互决定必须提交到 /api/interactions/{id}/decisions", false);
    }

    @MessageMapping("/interaction-received")
    public void handleInteractionReceived(@Payload Map<String, Object> payload, Principal principal,
                                          SimpMessageHeaderAccessor headers) {
        String interactionId = String.valueOf(payload.getOrDefault("interactionId", ""));
        String transportId = headers == null ? null : headers.getSessionId();
        if (transportId == null) return;
        if (!interactionId.isBlank()) {
            try {
                String sessionId = resolveSessionId(principal);
                var requested = permissionInteractions.findInteraction(interactionId);
                if (!sessionId.equals(requested.sessionId())
                        || !wsSessionManager.getTransportIdsForSession(sessionId).contains(transportId)) {
                    log.warn("Interaction ACK rejected: interactionId={}, session={}, transport={}",
                            interactionId, sessionId, transportId);
                    return;
                }
                int deliveryGeneration = payload.get("deliveryGeneration") instanceof Number number
                        ? number.intValue() : -1;
                if (permissionInteractions.acknowledgeInteraction(
                        interactionId, deliveryGeneration, transportId)) {
                    var interaction = permissionInteractions.findInteraction(interactionId);
                    push(interaction.sessionId(), "interaction_updated", Map.of(
                            "interactionId", interactionId,
                            "decisionDeadlineAt", interaction.decisionDeadlineAt().toEpochMilli(),
                            "serverNow", System.currentTimeMillis(),
                            "version", interaction.version()));
                }
            }
            catch (Exception e) { log.debug("Interaction ACK ignored: id={}, error={}", interactionId, e.getMessage()); }
            return;
        }
        log.debug("Ignoring legacy interaction ACK without interactionId");
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
        String activeRunId = runExecutions.activeRunForSession(sessionId).orElse(null);
        if (activeRunId != null) runTermination.cancelByUser(activeRunId, reason.name().toLowerCase());
        else queryEngine.abort(sessionId, reason);

        // 推送 interrupt_ack 到前端
        push(sessionId, "interrupt_ack", Map.of("reason", reason.name()));
    }

    /** 传输连接断开不等于用户取消 Run。 */
    @EventListener
    public void handleWebSocketDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        // 从 session attributes 中获取 app sessionId
        Map<String, Object> attrs = accessor.getSessionAttributes();
        String appSessionId = attrs != null ? (String) attrs.get("sessionId") : null;
        if (appSessionId != null && !"default".equals(appSessionId))
            log.info("WebSocket transport disconnected; Run retained for reconnect: sessionId={}", appSessionId);
    }

    /**
     * WebSocket 连接建立监听 — 预注册 AbortContext。
     * <p>
     * 避免连接断开时查询尚未启动/已结束场景下 abort() 找不到 AbortContext
     * 产生频繁 WARN 日志。sessionId 为 null 或 "default" 跳过。
     */
    @EventListener
    public void handleWebSocketConnected(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Map<String, Object> attrs = accessor.getSessionAttributes();
        String appSessionId = attrs != null ? (String) attrs.get("sessionId") : null;
        if (appSessionId != null && !"default".equals(appSessionId)) {
            queryEngine.getOrCreateAbortContext(appSessionId);
            log.debug("AbortContext pre-registered on WS connect: sessionId={}", appSessionId);
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
            // 存储模型选择到内存
            sessionModels.put(sessionId, payload.model());
            // 持久化到 DB
            try {
                sessionManager.updateSessionModel(sessionId, payload.model());
            } catch (Exception dbEx) {
                log.warn("Failed to persist model change to DB: sessionId={}, model={}", sessionId, payload.model(), dbEx);
            }
            push(sessionId, "model_changed", Map.of("model", payload.model()));
            log.info("Model stored for session: sessionId={}, model={}", sessionId, payload.model());
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
    private static final Set<String> ALLOWED_PERMISSION_MODES = Set.of(
            "DEFAULT", "PLAN", "ACCEPT_EDITS", "DONT_ASK");

    @MessageMapping("/permission-mode")
    public void handleSetPermissionMode(@Payload ClientMessage.SetPermissionModePayload payload,
                                         Principal principal) {
        String sessionId = resolveSessionId(principal);
        log.info("WS set_permission_mode: sessionId={}, mode={}", sessionId, payload.mode());
        if (!ALLOWED_PERMISSION_MODES.contains(payload.mode())) {
            log.warn("Permission mode not in allowlist: {}", payload.mode());
            return;
        }
        try {
            com.aicodeassistant.model.PermissionMode mode =
                    com.aicodeassistant.model.PermissionMode.valueOf(payload.mode());
            permissionModeManager.setMode(sessionId, mode);
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
                            if (cmd.getType() == CommandType.PROMPT) {
                                // ★ PROMPT 命令 → 注入 LLM 对话

                                // 1. 推送简洁通知到前端（不含完整提示词）
                                push(sessionId, "command_result", Map.of(
                                        "command", payload.command(),
                                        "resultType", "prompt"));

                                // 2. 提取 PromptCommand 扩展字段
                                //    GitReviewCommand 实现 Command（非 PromptCommand），
                                //    instanceof 为 false → allowedTools=null → 全部工具
                                Set<String> allowedTools = (cmd instanceof PromptCommand pc)
                                        ? pc.getAllowedTools() : null;
                                String modelOverride = (cmd instanceof PromptCommand pc2)
                                        ? pc2.getModel() : null;

                                // 3. 并发保护（复用 sessionQueryRunning）
                                AtomicBoolean running = sessionQueryRunning
                                        .computeIfAbsent(sessionId, k -> new AtomicBoolean(false));
                                if (!running.compareAndSet(false, true)) {
                                    sendError(sessionId, "query_busy",
                                            "当前会话正在处理中，请等待上一个请求完成", false);
                                    return;
                                }

                                // 4. Virtual Thread 异步执行
                                Thread.ofVirtual()
                                        .name("zhiku-prompt-cmd-" + sessionId)
                                        .start(() -> {
                                            try {
                                                executePromptCommand(sessionId, cmdResult.value(),
                                                        allowedTools, modelOverride);
                                            } catch (Exception e) {
                                                log.error("PromptCommand 执行异常: cmd=/{}, sessionId={}",
                                                        payload.command(), sessionId, e);
                                                sendError(sessionId, "query_error",
                                                        e.getMessage() != null ? e.getMessage()
                                                                : "Unknown error", true);
                                            } finally {
                                                running.set(false);
                                            }
                                        });
                            } else {
                                // 非 PROMPT 命令（LOCAL 等）: 保持原有行为
                                push(sessionId, "command_result", Map.of(
                                        "command", payload.command(),
                                        "resultType", "text",
                                        "output", cmdResult.value()));
                            }
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
        sendError(sessionId, "interaction_rest_required",
                "协议 v2 的交互决定必须提交到 /api/interactions/{id}/decisions", false);
    }

    /**
     * #10 心跳 → /app/ping
     */
    @MessageMapping("/ping")
    public void handlePing(Principal principal, SimpMessageHeaderAccessor headers) {
        String transportId = headers == null ? null : headers.getSessionId();
        wsSessionManager.refreshTransport(transportId);
        try {
            String sessionId = resolveSessionId(principal);
            sendPong(sessionId);
        } catch (IllegalStateException e) {
            if ("SESSION_NOT_BOUND".equals(e.getMessage())) {
                // 心跳仍有效：刷新transport活跃时间，响应pong但标记需要绑定
                if (principal != null) {
                    pushToPrincipal(principal.getName(), "pong",
                            Map.of("bindRequired", true, "serverNow", System.currentTimeMillis()));
                }
                log.debug("Heartbeat from unbound session, responded with bindRequired: principal={}, transport={}",
                        principal != null ? principal.getName() : "unknown", transportId);
            } else {
                throw e;
            }
        }
    }

    /**
     * #11 绑定会话 → /app/bind-session
     */
    @MessageMapping("/bind-session")
    public void handleBindSession(@Payload Map<String, Object> payload, Principal principal,
                                  SimpMessageHeaderAccessor headers) {
        String sessionId = payload.get("sessionId") instanceof String value ? value : null;
        String bindRequestId = payload.get("bindRequestId") instanceof String value ? value : null;
        long bindingEpoch = payload.get("bindingEpoch") instanceof Number number
                ? number.longValue() : -1;
        int protocolVersion = payload.get("protocolVersion") instanceof Number number
                ? number.intValue() : -1;
        if (principal != null && protocolVersion != WS_PROTOCOL_VERSION) {
            pushBindError(principal.getName(), "UPGRADE_REQUIRED", bindRequestId, bindingEpoch);
            log.warn("WS bind rejected: unsupported protocol principal={}, supplied={}",
                    principal.getName(), protocolVersion);
            return;
        }
        if (principal != null && (bindRequestId == null || bindRequestId.isBlank())) {
            pushBindError(principal.getName(), "BIND_REQUEST_ID_REQUIRED", bindRequestId, bindingEpoch);
            return;
        }
        if (principal != null && bindingEpoch < 1) {
            pushBindError(principal.getName(), "BINDING_EPOCH_REQUIRED", bindRequestId, bindingEpoch);
            return;
        }
        if (sessionId != null && principal != null) {
            String transportId = headers == null ? null : headers.getSessionId();
            if (transportId == null) {
                pushBindError(principal.getName(), "TRANSPORT_ID_UNAVAILABLE", bindRequestId, bindingEpoch);
                return;
            }
            Optional<SessionData> dataOpt;
            try { dataOpt = sessionManager.loadSession(sessionId); }
            catch (Exception e) {
                pushBindError(principal.getName(), "BIND_RECOVERY_FAILED", bindRequestId, bindingEpoch);
                return;
            }
            if (dataOpt.isEmpty()) {
                pushBindError(principal.getName(), "SESSION_NOT_FOUND", bindRequestId, bindingEpoch);
                return;
            }
            try {
                wsSessionManager.bindSession(principal.getName(), transportId, sessionId, bindingEpoch);
            } catch (IllegalStateException stale) {
                pushBindError(principal.getName(), stale.getMessage(), bindRequestId, bindingEpoch);
                return;
            }
            log.info("WS bind-session: principal={}, transport={}, sessionId={}",
                    principal.getName(), transportId, sessionId);

            // 先绑定再生成恢复快照，使后续帧全部进入客户端恢复门。
            // 绑定后重新读取会话，避免丢失授权校验与绑定之间已提交的消息。
            dataOpt = sessionManager.loadSession(sessionId);
            if (dataOpt.isEmpty()) {
                wsSessionManager.unbindSession(transportId);
                pushBindError(principal.getName(), "SESSION_NOT_FOUND", bindRequestId, bindingEpoch);
                return;
            }

            SessionData boundData = dataOpt.get();
            if (boundData.model() != null && !boundData.model().isEmpty()) {
                sessionModels.put(sessionId, boundData.model());
                log.info("Restored session model from DB: sessionId={}, model={}", sessionId, boundData.model());
            }

            // ── 新增: 推送 session_restored（含 activities）──
            try {
                if (dataOpt.isPresent()) {
                    SessionData data = dataOpt.get();
                    Map<String, Object> metadata = Map.of(
                        "sessionId", sessionId,
                        "model", data.model() != null ? data.model() : "",
                        "permissionMode", "DEFAULT",
                        "status", data.status() != null ? data.status() : "idle"
                    );

                    // 查询 activities（限制最近 50 条，避免 STOMP 帧超限）
                    List<Map<String, Object>> activityRows = activityRepository.findBySessionId(sessionId);
                    int totalActivityCount = activityRows.size();
                    boolean hasMore = totalActivityCount > 50;
                    List<Map<String, Object>> recentRows = hasMore
                        ? activityRows.subList(totalActivityCount - 50, totalActivityCount)
                        : activityRows;
                    List<Map<String, Object>> activities = recentRows.stream()
                        .map(this::convertActivityRowForWs)
                        .map(this::truncateActivityPayload)
                        .toList();

                    Map<String, Object> restoredPayload = new HashMap<>();
                    restoredPayload.put("messages", convertMessagesForWs(data.messages()));
                    restoredPayload.put("metadata", metadata);
                    restoredPayload.put("activities", activities);
                    restoredPayload.put("totalActivityCount", totalActivityCount);
                    restoredPayload.put("hasMore", hasMore);
                    restoredPayload.put("protocolVersion", WS_PROTOCOL_VERSION);
                    restoredPayload.put("bindRequestId", bindRequestId);
                    restoredPayload.put("bindingEpoch", bindingEpoch);
                    restoredPayload.put("serverNow", System.currentTimeMillis());
                    if (runRecoveryProjectionService != null) {
                        var projection = runRecoveryProjectionService.latestForSession(sessionId);
                        if (projection.runSnapshot() != null) restoredPayload.put("runSnapshot", projection.runSnapshot());
                        restoredPayload.put("snapshotEventSeq", projection.snapshotEventSeq());
                        restoredPayload.put("activeToolCalls", projection.activeToolCalls());
                    }
                    restoredPayload.put("costSummary", costTrackerService == null
                            ? Map.of() : costTrackerService.getSessionCost(sessionId));
                    pushToPrincipal(principal.getName(), "session_restored", restoredPayload);
                    log.info("Pushed session_restored: sessionId={}, messages={}, activities={}, total={}, hasMore={}",
                        sessionId, data.messages().size(), activities.size(), totalActivityCount, hasMore);
                } else {
                    pushBindError(principal.getName(), "SESSION_NOT_FOUND", bindRequestId, bindingEpoch);
                }
            } catch (Exception e) {
                log.warn("Failed to push session_restored for {}: {}", sessionId, e.getMessage());
                pushBindError(principal.getName(), "BIND_RECOVERY_FAILED", bindRequestId, bindingEpoch);
            }

            // ★ 重连后先推送当前权限模式（前端收到后会 clearPermissions）
            com.aicodeassistant.model.PermissionMode currentMode = permissionModeManager.getMode(sessionId);
            if (currentMode != null) {
                push(sessionId, "permission_mode_changed", Map.of("mode", currentMode.name()));
            }

            // ★ 再重放全部 pending interaction（此时 clearPermissions 已执行完毕）
            try {
                var pendingInteractions = permissionInteractions.getPendingInteractions(sessionId);
                int permissionCount = 0;
                for (var interaction : pendingInteractions) {
                    deliverInteraction(interaction, InteractionDelivery.RECOVERY);
                    if (interaction.type() == InteractionRequest.Type.PERMISSION) permissionCount++;
                }
                if (!pendingInteractions.isEmpty()) {
                    log.info("Replayed {} pending interactions ({} permissions) for session={}",
                            pendingInteractions.size(), permissionCount, sessionId);
                }
            } catch (Exception e) {
                log.warn("Failed to replay pending permissions for {}: {}", sessionId, e.getMessage());
            }

        } else {
            log.warn("WS bind-session failed: principal={}, sessionId={}",
                    principal != null ? principal.getName() : "null", sessionId);
            if (principal != null) {
                pushBindError(principal.getName(), "BIND_SESSION_REQUIRED", bindRequestId, bindingEpoch);
            }
        }
    }

    private void pushBindError(String principal, String code, String bindRequestId, long bindingEpoch) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("code", code == null ? "BIND_FAILED" : code);
        payload.put("supportedVersion", WS_PROTOCOL_VERSION);
        if (bindRequestId != null) payload.put("bindRequestId", bindRequestId);
        if (bindingEpoch >= 0) payload.put("bindingEpoch", bindingEpoch);
        pushToPrincipal(principal, "protocol_error", payload);
    }

    // ───── 辅助方法 ─────

    /**
     * 从 Principal 解析应用层 sessionId。
     */
    private String resolveSessionId(Principal principal) {
        if (principal == null) throw new IllegalStateException("SESSION_NOT_BOUND");
        String principalName = principal.getName();
        String sessionId = wsSessionManager.getSessionForPrincipal(principalName);
        if (sessionId != null) {
            return sessionId;
        }
        throw new IllegalStateException("SESSION_NOT_BOUND");
    }

    // ───── Activity STOMP 端点 ─────

    private static final int MAX_ACTIVITY_JSON_SIZE = 10 * 1024; // 10KB per JSON field
    private static final int MAX_ACTIVITY_ID_LENGTH = 128;

    /**
     * Activity 保存 → /app/activity-save
     */
    @MessageMapping("/activity-save")
    public void handleActivitySave(@Payload Map<String, Object> payload, Principal principal) {
        String sessionId = resolveSessionId(principal);
        try {
            String id = (String) payload.get("id");
            // 输入验证：id 非空 + 长度限制
            if (id == null || id.isBlank() || id.length() > MAX_ACTIVITY_ID_LENGTH) {
                log.warn("Activity save rejected: invalid id (null/blank/too long), sessionId={}", sessionId);
                return;
            }

            String operationType = (String) payload.get("operationType");
            String summary = (String) payload.get("summary");
            String status = (String) payload.get("status");
            long timestamp = payload.get("timestamp") != null ? ((Number) payload.get("timestamp")).longValue() : System.currentTimeMillis();
            Integer duration = payload.get("duration") != null ? ((Number) payload.get("duration")).intValue() : null;
            int fileCount = payload.get("fileCount") != null ? ((Number) payload.get("fileCount")).intValue() : 0;
            String decision = (String) payload.get("decision");

            String toolResultJson = boundedActivityJson(payload.get("toolResult"));
            String changedFilesJson = boundedActivityJson(payload.get("changedFiles"));
            String insightJson = boundedActivityJson(payload.get("insight"));

            activityRepository.upsert(id, sessionId, operationType, summary, status, timestamp, duration, fileCount, decision, toolResultJson, changedFilesJson, insightJson);
            log.debug("Activity saved: id={}, sessionId={}, type={}", id, sessionId, operationType);
        } catch (Exception e) {
            log.warn("Failed to save activity: {}", e.getMessage());
        }
    }

    /** 诊断值过大时保留可审计的有界记录。 */
    private String boundedActivityJson(Object value) throws Exception {
        if (value == null) return null;
        String full = objectMapper.writeValueAsString(value);
        byte[] bytes = full.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= MAX_ACTIVITY_JSON_SIZE) return full;
        var digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        String hash = java.util.HexFormat.of().formatHex(digest);
        int previewLength = Math.min(2048, full.length());
        String bounded = objectMapper.writeValueAsString(Map.of(
                "truncated", true,
                "originalBytes", bytes.length,
                "sha256", hash,
                "preview", full.substring(0, previewLength)));
        // 防御性限制：JSON 转义可能使预览长度意外增大。
        if (bounded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_ACTIVITY_JSON_SIZE) {
            bounded = objectMapper.writeValueAsString(Map.of(
                    "truncated", true, "originalBytes", bytes.length, "sha256", hash));
        }
        return bounded;
    }

    /**
     * Activity 更新 → /app/activity-update
     */
    @MessageMapping("/activity-update")
    public void handleActivityUpdate(@Payload Map<String, Object> payload, Principal principal) {
        try {
            String id = (String) payload.get("id");
            if (payload.containsKey("decision")) {
                activityRepository.updateDecision(id, (String) payload.get("decision"));
            }
            if (payload.containsKey("insight")) {
                String insightJson = objectMapper.writeValueAsString(payload.get("insight"));
                activityRepository.updateInsight(id, insightJson);
            }
            log.debug("Activity updated: id={}", id);
        } catch (Exception e) {
            log.warn("Failed to update activity: {}", e.getMessage());
        }
    }

    // ───── Activity 辅助方法 ─────

    /**
     * 将数据库 Activity 行转换为前端兼容的 JSON 格式。
     */
    private Map<String, Object> convertActivityRowForWs(Map<String, Object> row) {
        Map<String, Object> result = new HashMap<>();
        result.put("id", row.get("id"));
        result.put("sessionId", row.get("session_id"));
        result.put("operationType", row.get("operation_type"));
        result.put("summary", row.get("summary"));
        result.put("status", row.get("status"));
        result.put("timestamp", row.get("timestamp"));
        result.put("duration", row.get("duration"));
        result.put("fileCount", row.get("file_count"));
        result.put("decision", row.get("decision"));

        // JSON 字段需要反序列化为对象
        parseAndPut(result, "toolResult", (String) row.get("tool_result_json"));
        parseAndPut(result, "changedFiles", (String) row.get("changed_files_json"));
        parseAndPut(result, "insight", (String) row.get("insight_json"));

        return result;
    }

    private void parseAndPut(Map<String, Object> target, String key, String json) {
        if (json != null && !json.isEmpty()) {
            try {
                target.put(key, objectMapper.readValue(json, Object.class));
            } catch (Exception e) {
                log.warn("Failed to parse {} JSON: {}", key, e.getMessage());
            }
        }
    }

    /**
     * 截断 Activity payload 中的大型 JSON 字段，防止 STOMP 帧超限。
     */
    private Map<String, Object> truncateActivityPayload(Map<String, Object> activity) {
        // toolResult 超过 1KB 时截断
        Object toolResult = activity.get("toolResult");
        if (toolResult != null) {
            try {
                String json = objectMapper.writeValueAsString(toolResult);
                if (json.length() > 1024) {
                    activity.put("toolResult", Map.of("truncated", true, "size", json.length()));
                }
            } catch (Exception e) {
                // 序列化失败时移除该字段
                activity.put("toolResult", Map.of("truncated", true, "size", -1));
            }
        }
        return activity;
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
                    map.put("content", convertContentBlocksForWs(
                            com.aicodeassistant.engine.MessageContentAccessor.viewOf(u).blocks()));
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
