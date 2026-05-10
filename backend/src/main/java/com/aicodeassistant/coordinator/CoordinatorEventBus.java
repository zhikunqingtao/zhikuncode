package com.aicodeassistant.coordinator;

import com.aicodeassistant.websocket.WebSocketSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Coordinator 事件总线 — 将多 Agent 协作过程事件单播到前端实时时间线。
 *
 * <p>对齐 ZhikunCode 差异化升级方案 v1.5 §3.5 升级项 B（多 Agent 协作可观测）：
 * <ul>
 *   <li>独立 topic：{@code /user/queue/coordinator/{sessionId}}，与现有
 *       {@code /queue/messages} 主信道解耦，便于前端 AgentDAGChart 按需订阅</li>
 *   <li>推送失败仅 {@code log.warn} 兜底，业务线程零感知（不改调用侧异常语义）</li>
 *   <li>事件 envelope：{type, ts, uuid, sessionId, workflowId, eventType, payload}</li>
 * </ul>
 *
 * <p>埋点位置严格遵循"调用侧埋点，不侵入 domain 类"原则（方案 L427）：
 * {@link TeamMailbox} / {@link SharedTaskList} 等内部组件不持有 workflowId 字段，
 * 埋点由 {@link CoordinatorWorkflowEngine} / {@link SwarmService} 等调用侧负责。
 */
@Component
public class CoordinatorEventBus {

    private static final Logger log = LoggerFactory.getLogger(CoordinatorEventBus.class);

    /** 消息 envelope 顶层 type 值 */
    private static final String MESSAGE_TYPE = "coordinator_event";

    private final SimpMessagingTemplate messaging;
    private final WebSocketSessionManager wsSessionManager;

    public CoordinatorEventBus(SimpMessagingTemplate messaging,
                               WebSocketSessionManager wsSessionManager) {
        this.messaging = messaging;
        this.wsSessionManager = wsSessionManager;
    }

    // ═══════════════════════════════════════════════════════════════
    // 公开事件方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 阶段推进事件。
     *
     * @param sessionId     会话 ID
     * @param workflowId    工作流 ID（null 时以 sessionId 作为 fallback，方案 L440）
     * @param previousPhase 前一阶段名（首次进入时传 null）
     * @param nextPhase     目标阶段名（null 表示工作流完成）
     */
    public void publishPhaseTransition(String sessionId, String workflowId,
                                        String previousPhase, String nextPhase) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("previousPhase", previousPhase);
        payload.put("nextPhase", nextPhase);
        safeSend(sessionId, workflowId, "phase_transition", payload);
    }

    /**
     * 邮箱单发事件。
     *
     * @param sessionId   会话 ID
     * @param workflowId  工作流 ID（可为 null）
     * @param senderId    发送者 Agent ID
     * @param recipientId 接收者 Agent ID
     * @param content     消息内容（长内容会被前端截断显示）
     */
    public void publishMailboxWrite(String sessionId, String workflowId,
                                     String senderId, String recipientId, String content) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("senderId", senderId);
        payload.put("recipientId", recipientId);
        payload.put("content", truncate(content, 500));
        payload.put("contentLength", content != null ? content.length() : 0);
        safeSend(sessionId, workflowId, "mailbox_write", payload);
    }

    /**
     * 邮箱广播事件。
     *
     * @param sessionId  会话 ID
     * @param workflowId 工作流 ID（可为 null）
     * @param teamPrefix 团队前缀
     * @param senderId   发送者 Agent ID
     * @param content    广播内容
     */
    public void publishMailboxBroadcast(String sessionId, String workflowId,
                                         String teamPrefix, String senderId, String content) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("teamPrefix", teamPrefix);
        payload.put("senderId", senderId);
        payload.put("content", truncate(content, 500));
        payload.put("contentLength", content != null ? content.length() : 0);
        safeSend(sessionId, workflowId, "mailbox_broadcast", payload);
    }

    // ═══════════════════════════════════════════════════════════════
    // 内部推送
    // ═══════════════════════════════════════════════════════════════

    /**
     * 安全推送 — 任何异常都被吞没并记录，确保业务线程不受影响。
     */
    private void safeSend(String sessionId, String workflowId,
                           String eventType, Map<String, Object> payload) {
        if (sessionId == null || sessionId.isBlank()) {
            log.debug("CoordinatorEventBus skipped: blank sessionId, eventType={}", eventType);
            return;
        }
        try {
            String principal = wsSessionManager.getPrincipalForSession(sessionId);
            if (principal == null) {
                log.debug("CoordinatorEventBus skipped: no principal for sessionId={}, eventType={}",
                        sessionId, eventType);
                return;
            }

            String effectiveWorkflowId = (workflowId != null && !workflowId.isBlank())
                    ? workflowId : sessionId;

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("type", MESSAGE_TYPE);
            envelope.put("ts", System.currentTimeMillis());
            envelope.put("uuid", UUID.randomUUID().toString());
            envelope.put("sessionId", sessionId);
            envelope.put("workflowId", effectiveWorkflowId);
            envelope.put("eventType", eventType);
            envelope.put("payload", payload != null ? payload : Map.of());

            messaging.convertAndSendToUser(principal,
                    "/queue/coordinator/" + sessionId, envelope);
            log.debug("Coordinator event sent: sessionId={}, workflowId={}, eventType={}",
                    sessionId, effectiveWorkflowId, eventType);
        } catch (Exception e) {
            // 推送失败兜底 — 业务线程零感知
            log.warn("CoordinatorEventBus.safeSend failed: sessionId={}, eventType={}, err={}",
                    sessionId, eventType, e.getMessage());
        }
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
