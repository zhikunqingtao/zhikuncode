package com.aicodeassistant.websocket;

import com.aicodeassistant.model.Usage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;

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
public class WebSocketController {

    private static final Logger log = LoggerFactory.getLogger(WebSocketController.class);

    private final SimpMessagingTemplate messaging;
    private final WebSocketSessionManager wsSessionManager;

    public WebSocketController(SimpMessagingTemplate messaging,
                                WebSocketSessionManager wsSessionManager) {
        this.messaging = messaging;
        this.wsSessionManager = wsSessionManager;
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
        if (principal == null) return;

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", type);
        message.put("ts", System.currentTimeMillis());
        message.putAll(fields);

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
        // TODO: 接入 QueryEngine.submitUserMessage(sessionId, msg)
        // P0 阶段: 回显确认
        sendNotification(sessionId, "msg-received", "info",
                "Message received: " + (msg.text() != null ? msg.text().substring(0, Math.min(30, msg.text().length())) : ""), 3000);
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
        // TODO: 接入 QueryEngine.resolvePermission(sessionId, resp)
    }

    /**
     * #3 中断当前回合 → /app/interrupt
     */
    @MessageMapping("/interrupt")
    public void handleInterrupt(Principal principal) {
        String sessionId = resolveSessionId(principal);
        log.info("WS interrupt: sessionId={}", sessionId);
        // TODO: 接入 QueryEngine.interrupt(sessionId)
    }

    /**
     * #4 切换模型 → /app/model
     */
    @MessageMapping("/model")
    public void handleSetModel(@Payload ClientMessage.SetModelPayload payload,
                                Principal principal) {
        String sessionId = resolveSessionId(principal);
        log.info("WS set_model: sessionId={}, model={}", sessionId, payload.model());
        // TODO: 接入 SessionManager.setModel(sessionId, payload.model())
    }

    /**
     * #5 切换权限模式 → /app/permission-mode
     */
    @MessageMapping("/permission-mode")
    public void handleSetPermissionMode(@Payload ClientMessage.SetPermissionModePayload payload,
                                         Principal principal) {
        String sessionId = resolveSessionId(principal);
        log.info("WS set_permission_mode: sessionId={}, mode={}", sessionId, payload.mode());
        // TODO: 接入 SessionManager.setPermissionMode(sessionId, payload.mode())
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
        // TODO: 接入 CommandRegistry.execute(sessionId, payload)
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
        // TODO: 接入 MCP 管理服务
    }

    /**
     * #8 回退文件 → /app/rewind
     */
    @MessageMapping("/rewind")
    public void handleRewindFiles(@Payload ClientMessage.RewindFilesPayload payload,
                                   Principal principal) {
        String sessionId = resolveSessionId(principal);
        log.info("WS rewind_files: sessionId={}, messageId={}, files={}",
                sessionId, payload.messageId(), payload.filePaths());
        // TODO: 接入 FileHistoryService.rewindFiles(sessionId, payload)
    }

    /**
     * #9 AI 反向提问响应 → /app/elicitation
     */
    @MessageMapping("/elicitation")
    public void handleElicitationResponse(@Payload ClientMessage.ElicitationResponsePayload payload,
                                           Principal principal) {
        String sessionId = resolveSessionId(principal);
        log.info("WS elicitation_response: sessionId={}, requestId={}", sessionId, payload.requestId());
        // TODO: 接入 QueryEngine.resolveElicitation(sessionId, payload)
    }

    /**
     * #10 心跳 → /app/ping
     */
    @MessageMapping("/ping")
    public void handlePing(Principal principal) {
        String sessionId = resolveSessionId(principal);
        sendPong(sessionId);
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
