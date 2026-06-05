package com.aicodeassistant.notify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * NotificationService — STOMP 通知推送服务。
 *
 * <p>对应 RV-4 验证注意通知场景：当 RV-1 验证 verdict 为 failed/inconclusive 时，
 * 主动推送 verify_attention 消息到用户队列，前端可据此弹出待批准面板。</p>
 *
 * <p>STOMP destination 约定：{@code /user/queue/messages}（复用项目统一消息队列，
 * 通过 {@code type=verify_attention} 字段区分，与其他推送保持一致）。</p>
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private static final String VERIFY_ATTENTION_DESTINATION = "/queue/messages";

    private final SimpMessagingTemplate messagingTemplate;

    public NotificationService(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * 推送 verify_attention 通知到指定用户队列。
     *
     * <p>失败不抛出异常（仅记录 warn 日志），避免影响主验证流程。</p>
     *
     * @param userId  目标用户 ID（项目当前以 sessionId 作为 STOMP user principal）
     * @param payload 通知载荷
     */
    public void sendVerifyAttention(String userId, VerifyAttentionPayload payload) {
        if (userId == null || userId.isBlank()) {
            log.warn("sendVerifyAttention skipped: empty userId");
            return;
        }
        if (payload == null) {
            log.warn("sendVerifyAttention skipped: null payload");
            return;
        }
        try {
            messagingTemplate.convertAndSendToUser(userId, VERIFY_ATTENTION_DESTINATION, payload);
            log.debug("verify_attention sent: user={} bundleId={} verdict={}",
                    userId, payload.bundleId(), payload.verdict());
        } catch (Exception e) {
            log.warn("Failed to send verify_attention via STOMP: user={} bundleId={} err={}",
                    userId, payload.bundleId(), e.getMessage());
        }
    }

    /**
     * verify_attention 消息载荷。
     *
     * @param type             固定 "verify_attention"
     * @param sessionId        会话 ID
     * @param bundleId         证据包 ID
     * @param verdict          判定: failed | inconclusive
     * @param claim            Agent 声称完成的内容
     * @param summary          简要描述
     * @param requiresApproval 是否需要用户批准
     * @param timestamp        ISO-8601 时间戳
     */
    public record VerifyAttentionPayload(
            String type,
            String sessionId,
            String bundleId,
            String verdict,
            String claim,
            String summary,
            boolean requiresApproval,
            String timestamp
    ) {}
}
