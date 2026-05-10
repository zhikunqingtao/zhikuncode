package com.aicodeassistant.service;

import com.aicodeassistant.websocket.WebSocketSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Visualization 消息推送器 — 将结构化可视化 payload 单播到前端 /queue/messages。
 *
 * <p>对齐 ZhikunCode 差异化升级方案 v1.5 §4.5 升级项 C（全栈可视化）：
 * <ul>
 *   <li>独立消息路线（非 assistant ContentBlock）— v1.4 BLK-R4-1 校准</li>
 *   <li>单播推送 — v1.1 HIGH-1 校准，与 {@code ElicitationService.sendElicitation} L80-L94 同构</li>
 *   <li>消息 envelope：{ type: "visualization", ts, uuid, viewType, props }</li>
 * </ul>
 *
 * <p>前端通过 {@code MessageItem.renderMessage} switch 的 'visualization' 分支
 * 路由到 {@code VisualizationMessage.tsx}，再按 {@code viewType} 分派到具体可视化组件
 * （mermaid / schema / timeline / ...）。
 */
@Service
public class VisualizationPayloadBuilder {

    private static final Logger log = LoggerFactory.getLogger(VisualizationPayloadBuilder.class);

    private final SimpMessagingTemplate messaging;
    private final WebSocketSessionManager wsSessionManager;

    public VisualizationPayloadBuilder(SimpMessagingTemplate messaging,
                                       WebSocketSessionManager wsSessionManager) {
        this.messaging = messaging;
        this.wsSessionManager = wsSessionManager;
    }

    /**
     * 推送 visualization 消息到前端 /queue/messages（principal 单播）。
     *
     * @param sessionId 会话 ID
     * @param viewType  视图类型（如 mermaid / schema / timeline / json / text），不区分大小写
     * @param props     视图 props；若为 null 则使用空 Map
     * @return 推送成功返回 true；若 principal 未绑定或参数非法返回 false
     */
    public boolean publish(String sessionId, String viewType, Map<String, Object> props) {
        if (sessionId == null || sessionId.isBlank()) {
            log.warn("VisualizationPayloadBuilder.publish skipped: blank sessionId");
            return false;
        }
        if (viewType == null || viewType.isBlank()) {
            log.warn("VisualizationPayloadBuilder.publish skipped: blank viewType, sessionId={}", sessionId);
            return false;
        }

        String principal = wsSessionManager.getPrincipalForSession(sessionId);
        if (principal == null) {
            log.warn("VisualizationPayloadBuilder.publish skipped: no principal for sessionId={}", sessionId);
            return false;
        }

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "visualization");
        message.put("ts", System.currentTimeMillis());
        message.put("uuid", UUID.randomUUID().toString());
        message.put("viewType", viewType);
        message.put("props", props != null ? props : Map.of());

        messaging.convertAndSendToUser(principal, "/queue/messages", message);
        log.info("Visualization published: sessionId={}, viewType={}, propsKeys={}",
                sessionId, viewType, message.get("props") instanceof Map<?, ?> m ? m.keySet() : "<n/a>");
        return true;
    }
}
