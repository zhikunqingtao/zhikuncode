package com.aicodeassistant.websocket;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
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
 *   <li>CONNECTED 时只登记 transport，v2 bind-session 成功后才绑定应用会话</li>
 *   <li>维护 principal → sessionId 和 sessionId → principal 映射</li>
 *   <li>为 {@link WebSocketController} 提供 Principal/SessionId 查找</li>
 * </ul>
 */
@Component
public class WebSocketSessionManager {

    private static final Logger log = LoggerFactory.getLogger(WebSocketSessionManager.class);
    private final JdbcTemplate jdbc;

    public WebSocketSessionManager(@Qualifier("projectJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 映射最大存活时间（无活跃连接时），防止 disconnect 事件丢失导致内存泄漏 */
    // Transport heartbeats and every inbound STOMP frame refresh this lease. 180s
    // tolerates browser timer throttling while still bounding leaked transports.
    private static final long STALE_ENTRY_TTL_MS = 180_000L;
    private static final long OFFLINE_GRACE_MS = 30_000L;

    /** Server-issued STOMP transport id → its complete liveness/bind state. */
    private final ConcurrentHashMap<String, TransportState> transports = new ConcurrentHashMap<>();

    /** Application session id → currently bound server transport ids. */
    private final ConcurrentHashMap<String, Set<String>> sessionToTransports = new ConcurrentHashMap<>();

    /** Offline marker is informational only; it never cancels a Run. */
    private final ConcurrentHashMap<String, Long> sessionOfflineSince = new ConcurrentHashMap<>();

    public record TransportState(String transportId, String principalName, String appSessionId,
                                 long connectedAtNanos, long lastSeenAtNanos,
                                 boolean bindCompleted, long bindingEpoch) {
        TransportState touch(long now) {
            return new TransportState(transportId, principalName, appSessionId,
                    connectedAtNanos, now, bindCompleted, bindingEpoch);
        }
        TransportState bind(String sessionId, long now, long epoch) {
            return new TransportState(transportId, principalName, sessionId,
                    connectedAtNanos, now, true, epoch);
        }
    }

    private final ScheduledExecutorService cleanupScheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "ws-session-cleanup");
                thread.setDaemon(true);
                return thread;
            });

    /** 启动定时清理任务 — 10 秒 heartbeat/stale 扫描。 */
    @PostConstruct
    public void init() {
        cleanupScheduler.scheduleAtFixedRate(this::cleanupStaleEntries,
                10, 10, TimeUnit.SECONDS);
        log.info("WebSocket session cleanup scheduler started (TTL={}s, interval=10s)",
                STALE_ENTRY_TTL_MS / 1000);
    }

    /** 关闭清理调度器 */
    @PreDestroy
    public void destroy() {
        cleanupScheduler.shutdownNow();
        log.info("WebSocket session cleanup scheduler stopped");
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
        registerTransport(transportSessionId, principalName);

        // 尝试从数据库恢复旧的会话绑定（服务重启后内存映射清空的情况）
        tryRecoverBinding(principalName, transportSessionId);
        log.debug("WebSocket connected and awaiting bind-session: principal={}", principalName);
    }

    /**
     * STOMP 断开 — 清理映射。
     * <p>
     * ★ 只清理 transport 映射，不立即清理 principal ↔ sessionId 映射。
     * 原因：浏览器窗口临时不可见、短暂网络波动等场景会触发 STOMP DISCONNECT，
     * 但此时可能有正在执行的工具（如 Bash）需要通过 push() 发送 permission_request。
     * 如果立即清理 session 映射，push() 会找不到 principal 而静默丢弃消息。
     * session 映射由 TTL 定时清理机制负责回收，不会内存泄漏。
     */
    @EventListener
    public void handleSessionDisconnect(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String transportSessionId = accessor.getSessionId();

        TransportState removed = disconnectTransport(transportSessionId);
        if (removed != null) {
            log.info("WebSocket transport disconnected: principal={}, sessionId={} (offline grace started)",
                    removed.principalName(), removed.appSessionId());
        }
    }

    // ───── 查询方法 ─────

    /**
     * 根据 principal name 获取应用层 sessionId。
     */
    public String getSessionForPrincipal(String principalName) {
        return transports.values().stream()
                .filter(t -> t.bindCompleted() && t.principalName().equals(principalName))
                .max(java.util.Comparator.comparingLong(TransportState::lastSeenAtNanos))
                .map(TransportState::appSessionId).orElse(null);
    }

    /**
     * 根据应用层 sessionId 获取 principal name。
     */
    public String getPrincipalForSession(String sessionId) {
        return getPrincipalsForSession(sessionId).stream().findFirst().orElse(null);
    }
    public Set<String> getPrincipalsForSession(String sessionId) {
        return getPrincipalsForSession(sessionId, false);
    }

    /**
     * 获取会话绑定的 principal 列表。
     * @param includePendingBind 为 true 时，也返回已连接但未完成 bind 的 transport 的 principal（用于关键消息降级推送）
     */
    public Set<String> getPrincipalsForSession(String sessionId, boolean includePendingBind) {
        Set<String> ids = sessionToTransports.get(sessionId);
        if (ids == null) return Set.of();
        return ids.stream().map(transports::get).filter(java.util.Objects::nonNull)
                .filter(t -> includePendingBind || t.bindCompleted())
                .map(TransportState::principalName)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    public Set<String> getTransportIdsForSession(String sessionId) {
        Set<String> ids=sessionToTransports.get(sessionId);
        if(ids==null)return Set.of();
        return ids.stream().filter(transports::containsKey)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    void registerTransport(String transportId, String principalName) {
        if (transportId == null || principalName == null)
            throw new IllegalArgumentException("transport and principal are required");
        long now = System.nanoTime();
        TransportState state = new TransportState(transportId, principalName, null,
                now, now, false, 0);
        TransportState previous = transports.putIfAbsent(transportId, state);
        if (previous != null && !previous.principalName().equals(principalName))
            throw new IllegalStateException("TRANSPORT_PRINCIPAL_MISMATCH");
    }

    public void bindSession(String principalName, String transportId, String sessionId, long bindingEpoch) {
        if (principalName == null || transportId == null || sessionId == null)
            throw new IllegalArgumentException("principal, transport and session are required");
        if (bindingEpoch < 1) throw new IllegalArgumentException("bindingEpoch must be positive");
        long now=System.nanoTime();
        transports.compute(transportId,(id,current)->{
            if(current==null||!current.principalName().equals(principalName))
                throw new IllegalStateException("TRANSPORT_PRINCIPAL_MISMATCH");
            if(current.appSessionId()!=null&&!current.appSessionId().equals(sessionId))
                removeSessionIndex(current.appSessionId(),transportId);
            if (bindingEpoch <= current.bindingEpoch())
                throw new IllegalStateException("STALE_BINDING_EPOCH");
            return current.bind(sessionId,now,bindingEpoch);
        });
        sessionToTransports.computeIfAbsent(sessionId,ignored->ConcurrentHashMap.newKeySet()).add(transportId);
        sessionOfflineSince.remove(sessionId);

        // 持久化绑定信息到数据库
        persistBinding(principalName, sessionId, bindingEpoch);
        log.debug("Session transport bound: principal={}, transport={}, sessionId={}",principalName,transportId,sessionId);
    }

    public void unbindSession(String transportId) {
        transports.computeIfPresent(transportId, (id, current) -> {
            if (current.appSessionId() != null) removeSessionIndex(current.appSessionId(), transportId);
            long now = System.nanoTime();
            return new TransportState(current.transportId(), current.principalName(), null,
                    current.connectedAtNanos(), now, false, current.bindingEpoch());
        });
    }

    public long getBindingEpochForPrincipal(String principalName) {
        return transports.values().stream()
                .filter(t -> t.bindCompleted() && t.principalName().equals(principalName))
                .mapToLong(TransportState::bindingEpoch).max().orElse(0);
    }

    /**
     * 刷新 session 活跃时间 — 仅在明确的用户动作时调用（如收到用户消息）。
     * 不在 getPrincipalForSession 等读取方法中调用，避免推送行为自身续期。
     */
    public void refreshActivity(String sessionId) {
        Set<String> ids=sessionToTransports.getOrDefault(sessionId,Set.of());
        long now=System.nanoTime(); ids.forEach(id->transports.computeIfPresent(id,(key,state)->state.touch(now)));
    }
    public void refreshTransport(String transportId) {
        if(transportId==null)return;
        long now=System.nanoTime(); transports.computeIfPresent(transportId,(key,state)->state.touch(now));
    }

    /**
     * 检查指定 sessionId 是否有在线连接。
     */
    public boolean isSessionOnline(String sessionId) {
        Set<String> ids = sessionToTransports.get(sessionId);
        return ids != null && ids.stream().anyMatch(transports::containsKey);
    }

    /**
     * ★ 新增：获取所有活跃的应用层 sessionId。
     * 用于广播式推送（如 MCP 健康状态变更）。
     */
    public Set<String> getActiveSessionIds() {
        return sessionToTransports.entrySet().stream().filter(e -> e.getValue().stream().anyMatch(transports::containsKey))
                .map(Entry::getKey).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    // ───── 定时清理 ─────

    /**
     * 清理过期的 session 映射 — 防止 disconnect 事件丢失导致内存泄漏。
     * 每 5 分钟执行一次，移除超过 10 分钟未活跃的映射条目。
     * 同时清理全部四个映射表: sessionLastActive, sessionToPrincipal, principalToSession, transportToSession。
     */
    private void cleanupStaleEntries() {
        long now = System.nanoTime();
        int cleaned = 0;
        long staleNanos=TimeUnit.MILLISECONDS.toNanos(STALE_ENTRY_TTL_MS);
        for(TransportState state:Set.copyOf(transports.values())){
            if(now-state.lastSeenAtNanos()>staleNanos&&disconnectTransport(state.transportId())!=null)cleaned++;
        }
        sessionOfflineSince.entrySet().removeIf(e->
                now-e.getValue()>TimeUnit.MILLISECONDS.toNanos(OFFLINE_GRACE_MS)
                        && !isSessionOnline(e.getKey()));

        if (cleaned > 0) {
            log.info("Session cleanup completed: removed {} stale entries, activeSessions={}, activeTransports={}",
                    cleaned, sessionToTransports.size(), transports.size());
        }
    }

    TransportState disconnectTransport(String transportId){
        TransportState removed=transports.remove(transportId);
        if(removed!=null&&removed.appSessionId()!=null){
            removeSessionIndex(removed.appSessionId(),transportId);
            if(!isSessionOnline(removed.appSessionId()))sessionOfflineSince.put(removed.appSessionId(),System.nanoTime());
        }
        return removed;
    }
    private void removeSessionIndex(String sessionId,String transportId){
        Set<String> ids=sessionToTransports.get(sessionId);
        if(ids!=null){ids.remove(transportId);if(ids.isEmpty())sessionToTransports.remove(sessionId,ids);}
    }

    // ───── 持久化与恢复 ─────

    /**
     * 将当前绑定信息持久化到数据库 — 支持服务重启后恢复。
     */
    private void persistBinding(String principalName, String sessionId, long bindingEpoch) {
        try {
            jdbc.update(
                    "INSERT OR REPLACE INTO websocket_session_binding (principal_name, app_session_id, binding_epoch, last_activity_at) VALUES (?, ?, ?, datetime('now'))",
                    principalName, sessionId, bindingEpoch
            );
        } catch (Exception e) {
            log.warn("Failed to persist WebSocket binding: principal={}, session={}", principalName, sessionId, e);
        }
    }

    /**
     * 服务重启后，从数据库恢复上次的会话绑定 — 避免客户端心跳在bind-session前触发SESSION_NOT_BOUND。
     */
    private void tryRecoverBinding(String principalName, String transportId) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT app_session_id, binding_epoch FROM websocket_session_binding WHERE principal_name = ?",
                    principalName
            );
            if (!rows.isEmpty()) {
                Map<String, Object> row = rows.get(0);
                String recoveredSessionId = (String) row.get("app_session_id");
                long recoveredEpoch = ((Number) row.get("binding_epoch")).longValue();
                // 直接绑定到恢复的会话（等同于执行了一次bindSession，但epoch用恢复值+1避免STALE拒绝）
                long now = System.nanoTime();
                TransportState current = transports.get(transportId);
                if (current != null && !current.bindCompleted()) {
                    transports.put(transportId, current.bind(recoveredSessionId, now, recoveredEpoch));
                    sessionToTransports.computeIfAbsent(recoveredSessionId, ignored -> ConcurrentHashMap.newKeySet()).add(transportId);
                    sessionOfflineSince.remove(recoveredSessionId);
                    log.info("Auto-recovered WebSocket binding from persistence: principal={}, session={}, epoch={}",
                            principalName, recoveredSessionId, recoveredEpoch);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to recover WebSocket binding: principal={}", principalName, e);
        }
    }
}
