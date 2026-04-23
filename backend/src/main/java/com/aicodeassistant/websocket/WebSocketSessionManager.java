package com.aicodeassistant.websocket;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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

    /** 映射最大存活时间（无活跃连接时），防止 disconnect 事件丢失导致内存泄漏 */
    private static final long STALE_ENTRY_TTL_MS = 30 * 60 * 1000L; // 30 分钟

    /** principal name → application sessionId */
    private final ConcurrentHashMap<String, String> principalToSession = new ConcurrentHashMap<>();

    /** application sessionId → principal name */
    private final ConcurrentHashMap<String, String> sessionToPrincipal = new ConcurrentHashMap<>();

    /** STOMP transport sessionId → principal name (用于 disconnect 清理) */
    private final ConcurrentHashMap<String, String> transportToSession = new ConcurrentHashMap<>();

    /** sessionId → 最后活跃时间戳（用于定时清理过期映射） */
    private final ConcurrentHashMap<String, Long> sessionLastActive = new ConcurrentHashMap<>();

    private final ScheduledExecutorService cleanupScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "ws-session-cleanup");
                thread.setDaemon(true);
                return thread;
            });

    /** 启动定时清理任务 — 每 5 分钟扫描一次过期映射 */
    @PostConstruct
    public void init() {
        cleanupScheduler.scheduleAtFixedRate(this::cleanupStaleEntries,
                5, 5, TimeUnit.MINUTES);
    }

    /** 关闭清理调度器 */
    @PreDestroy
    public void destroy() {
        cleanupScheduler.shutdownNow();
    }

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
            sessionLastActive.put(appSessionId, System.currentTimeMillis());
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
                sessionLastActive.remove(appSessionId);
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
     * 同时刷新活跃时间戳，防止活跃 session 被误清理。
     */
    public String getPrincipalForSession(String sessionId) {
        String principal = sessionToPrincipal.get(sessionId);
        if (principal != null) {
            sessionLastActive.put(sessionId, System.currentTimeMillis());
        }
        return principal;
    }

    /**
     * 手动绑定 principal ↔ sessionId（用于 REST API 创建会话后绑定）。
     */
    public void bindSession(String principalName, String sessionId) {
        principalToSession.put(principalName, sessionId);
        sessionToPrincipal.put(sessionId, principalName);
        sessionLastActive.put(sessionId, System.currentTimeMillis());
        log.debug("Session manually bound: principal={}, sessionId={}", principalName, sessionId);
    }

    /**
     * 检查指定 sessionId 是否有在线连接。
     */
    public boolean isSessionOnline(String sessionId) {
        return sessionToPrincipal.containsKey(sessionId);
    }

    /**
     * ★ 新增：获取所有活跃的应用层 sessionId。
     * 用于广播式推送（如 MCP 健康状态变更）。
     */
    public Set<String> getActiveSessionIds() {
        return Set.copyOf(sessionToPrincipal.keySet());
    }

    // ───── 定时清理 ─────

    /**
     * 清理过期的 session 映射 — 防止 disconnect 事件丢失导致内存泄漏。
     * 每 5 分钟执行一次，移除超过 30 分钟未活跃的映射条目。
     */
    private void cleanupStaleEntries() {
        long now = System.currentTimeMillis();
        int cleaned = 0;
        Iterator<Map.Entry<String, Long>> iterator = sessionLastActive.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Long> entry = iterator.next();
            if (now - entry.getValue() > STALE_ENTRY_TTL_MS) {
                String staleSessionId = entry.getKey();
                String principal = sessionToPrincipal.remove(staleSessionId);
                if (principal != null) {
                    principalToSession.remove(principal);
                }
                iterator.remove();
                cleaned++;
                log.info("Cleaned stale session mapping: sessionId={}, principal={}", staleSessionId, principal);
            }
        }
        if (cleaned > 0) {
            log.info("Session cleanup completed: removed {} stale entries, active={}", cleaned, sessionToPrincipal.size());
        }
    }
}
