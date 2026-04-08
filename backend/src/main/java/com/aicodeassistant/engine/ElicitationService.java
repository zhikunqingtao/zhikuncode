package com.aicodeassistant.engine;

import com.aicodeassistant.websocket.WebSocketSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/**
 * Elicitation 服务 — Hook↔用户双向交互协议。
 *
 * 流程:
 * 1. Hook/工具触发 elicitation 请求
 * 2. 后端推送 elicitation 消息到前端
 * 3. 前端显示 ElicitationDialog
 * 4. 用户响应通过 WebSocket 回传
 * 5. 后端解除 CompletableFuture 阻塞，返回给调用方
 *
 * 注意: 不注入 WebSocketController（避免循环依赖），
 *       直接使用 SimpMessagingTemplate 推送。
 */
@Service
public class ElicitationService {

    private static final Logger log = LoggerFactory.getLogger(ElicitationService.class);

    private final SimpMessagingTemplate messaging;
    private final WebSocketSessionManager wsSessionManager;

    /** 等待中的 elicitation 请求 */
    private final Map<String, CompletableFuture<ElicitationResponse>> pending =
            new ConcurrentHashMap<>();

    private static final long DEFAULT_TIMEOUT_MS = 60_000;

    public ElicitationService(SimpMessagingTemplate messaging,
                              WebSocketSessionManager wsSessionManager) {
        this.messaging = messaging;
        this.wsSessionManager = wsSessionManager;
    }

    /**
     * 发送 elicitation 请求并等待用户响应 — 阻塞调用。
     * 在 Virtual Thread 中安全使用。
     */
    public ElicitationResponse requestAndWait(
            String sessionId, String question,
            List<ElicitationOption> options, long timeoutMs) {

        String requestId = UUID.randomUUID().toString();
        CompletableFuture<ElicitationResponse> future = new CompletableFuture<>();
        pending.put(requestId, future);

        try {
            // 直接通过 SimpMessagingTemplate 推送到前端
            sendElicitation(sessionId, requestId, question, options);
            log.debug("Elicitation sent: requestId={}, question='{}'", requestId, question);

            // 阻塞等待
            long timeout = timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS;
            return future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.info("Elicitation timed out: requestId={}", requestId);
            return ElicitationResponse.timeout();
        } catch (Exception e) {
            log.warn("Elicitation error: requestId={}, error={}", requestId, e.getMessage());
            return ElicitationResponse.error(e.getMessage());
        } finally {
            pending.remove(requestId);
        }
    }

    /** 推送 elicitation 消息到前端 — 复用 WebSocketController 的 push 模式 */
    private void sendElicitation(String sessionId, String requestId,
                                 String question, List<?> options) {
        String principal = wsSessionManager.getPrincipalForSession(sessionId);
        if (principal == null) {
            log.warn("No principal for session {}, cannot send elicitation", sessionId);
            return;
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "elicitation");
        message.put("ts", System.currentTimeMillis());
        message.put("requestId", requestId);
        message.put("question", question);
        message.put("options", options);
        messaging.convertAndSendToUser(principal, "/queue/messages", message);
    }

    /** 接收前端响应 — 由 WebSocketController 调用 */
    public void resolveElicitation(String requestId, Object response) {
        CompletableFuture<ElicitationResponse> future = pending.get(requestId);
        if (future != null) {
            future.complete(ElicitationResponse.success(response));
            log.debug("Elicitation resolved: requestId={}", requestId);
        } else {
            log.warn("No pending elicitation for requestId={}", requestId);
        }
    }

    /** 取消 — 用户关闭对话框 */
    public void cancelElicitation(String requestId) {
        CompletableFuture<ElicitationResponse> future = pending.get(requestId);
        if (future != null) {
            future.complete(ElicitationResponse.cancelled());
        }
    }

    // ── 内部类型 ──

    public record ElicitationOption(String label, String value, String description) {}

    public record ElicitationResponse(Status status, Object value, String error) {
        public enum Status { SUCCESS, CANCELLED, TIMEOUT, ERROR }

        public static ElicitationResponse success(Object v) { return new ElicitationResponse(Status.SUCCESS, v, null); }
        public static ElicitationResponse cancelled()       { return new ElicitationResponse(Status.CANCELLED, null, null); }
        public static ElicitationResponse timeout()         { return new ElicitationResponse(Status.TIMEOUT, null, "Request timed out"); }
        public static ElicitationResponse error(String msg) { return new ElicitationResponse(Status.ERROR, null, msg); }
    }
}
