package com.aicodeassistant.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 会话管理器 — 维护 Principal ↔ SessionId 双向映射 (§6.2, §8.5.4)。
 * <p>
 * 职责:
 * <ul>
 *   <li>监听 STOMP CONNECTED/DISCONNECT 事件</li>
 *   <li>从 CONNECT 帧 session attributes 中提取 X-Session-Id</li>
 *   <li>维护 principal → sessionId 和 sessionId → principal 映射</li>
 *   <li>为 {@link WebSocketController} 提供 Principal/SessionId 查找</li>
 * </ul>
 */
@Component
public class WebSocketSessionManager {

    private static final Logger log = LoggerFactory.getLogger(WebSocketSessionManager.class);

    /** principal name → application sessionId */
    private final ConcurrentHashMap<String, String> principalToSession = new ConcurrentHashMap<>();

    /** application sessionId → principal name */
    private final ConcurrentHashMap<String, String> sessionToPrincipal = new ConcurrentHashMap<>();

    /** STOMP transport sessionId → principal name (用于 disconnect 清理) */
    private final ConcurrentHashMap<String, String> transportToSession = new ConcurrentHashMap<>();

    /**
     * STOMP 连接建立 — 注册 Principal ↔ SessionId 映射。
     */
    @EventListener
    public void handleSessionConnected(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        Principal user = accessor.getUser();
        String transportSessionId = accessor.getSessionId();

        if (user == null) {
            log.warn("STOMP CONNECTED without principal, transportSession={}", transportSessionId);
            return;
        }

        String principalName = user.getName();

        // 从 session attributes 中取出应用层 sessionId
        Map<String, Object> attrs = accessor.getSessionAttributes();
        String appSessionId = attrs != null ? (String) attrs.get("sessionId") : null;

        if (appSessionId != null) {
            principalToSession.put(principalName, appSessionId);
            sessionToPrincipal.put(appSessionId, principalName);
            transportToSession.put(transportSessionId, principalName);
            log.info("WebSocket session bound: principal={}, sessionId={}", principalName, appSessionId);
        } else {
            transportToSession.put(transportSessionId, principalName);
            log.debug("WebSocket connected without sessionId: principal={}", principalName);
        }
    }

    /**
     * STOMP 断开 — 清理映射。
     */
    @EventListener
    public void handleSessionDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String transportSessionId = accessor.getSessionId();

        String principalName = transportToSession.remove(transportSessionId);
        if (principalName != null) {
            String appSessionId = principalToSession.remove(principalName);
            if (appSessionId != null) {
                sessionToPrincipal.remove(appSessionId);
            }
            log.info("WebSocket session unbound: principal={}, sessionId={}", principalName, appSessionId);
        }
    }

    // ───── 查询方法 ─────

    /**
     * 根据 principal name 获取应用层 sessionId。
     */
    public String getSessionForPrincipal(String principalName) {
        return principalToSession.get(principalName);
    }

    /**
     * 根据应用层 sessionId 获取 principal name。
     */
    public String getPrincipalForSession(String sessionId) {
        return sessionToPrincipal.get(sessionId);
    }

    /**
     * 手动绑定 principal ↔ sessionId（用于 REST API 创建会话后绑定）。
     */
    public void bindSession(String principalName, String sessionId) {
        principalToSession.put(principalName, sessionId);
        sessionToPrincipal.put(sessionId, principalName);
        log.debug("Session manually bound: principal={}, sessionId={}", principalName, sessionId);
    }

    /**
     * 检查指定 sessionId 是否有在线连接。
     */
    public boolean isSessionOnline(String sessionId) {
        return sessionToPrincipal.containsKey(sessionId);
    }
}
