package com.aicodeassistant.bridge;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 桥接服务器 — IDE↔Server 双向通信核心。
 * <p>
 * 支持双路径架构（REPL Bridge / Remote Bridge）和双协议（V1 / V2）。
 * 管理 IDE 环境注册、会话创建、消息处理、权限请求转发。
 * <p>
 * 核心职责:
 * <ul>
 *   <li>环境注册与注销（registerEnvironment / unregisterEnvironment）</li>
 *   <li>会话创建与关闭（createSession / closeSession）</li>
 *   <li>消息路由与处理（handleMessage）</li>
 *   <li>权限请求转发到 IDE（requestPermission）</li>
 *   <li>多会话管理（activeSessions 容量控制）</li>
 * </ul>
 *
 * @see <a href="SPEC §4.5.7">BridgeServer 实现</a>
 * @see <a href="SPEC §4.5.8a">桥接系统完整协议规范</a>
 */
public class BridgeServer {

    private static final Logger log = LoggerFactory.getLogger(BridgeServer.class);

    /** 默认会话超时: 24 小时 */
    public static final long DEFAULT_SESSION_TIMEOUT_MS = 24 * 60 * 60 * 1000L;

    /** 默认最大并行会话数 */
    public static final int DEFAULT_MAX_SESSIONS = 1;

    // ==================== 状态管理 ====================

    private volatile BridgeState state = BridgeState.READY;
    private final BridgeUtils.EpochManager epochManager = new BridgeUtils.EpochManager();
    private final BridgeUtils.BoundedUUIDSet messageDedup = new BridgeUtils.BoundedUUIDSet();
    private final BridgePollConfig pollConfig;
    private final int maxSessions;

    /** 活跃会话 */
    private final ConcurrentHashMap<String, SessionHandle> activeSessions = new ConcurrentHashMap<>();

    /** 注册的环境 */
    private final ConcurrentHashMap<String, EnvironmentInfo> environments = new ConcurrentHashMap<>();

    /** 权限请求待处理队列 */
    private final ConcurrentHashMap<String, CompletableFuture<PermissionDecision>> pendingPermissions =
            new ConcurrentHashMap<>();

    /** 消息监听器 */
    private final List<Consumer<BridgeMessage>> messageListeners =
            new CopyOnWriteArrayList<>();

    public BridgeServer() {
        this(BridgePollConfig.defaults(), DEFAULT_MAX_SESSIONS);
    }

    public BridgeServer(BridgePollConfig pollConfig, int maxSessions) {
        this.pollConfig = pollConfig;
        this.maxSessions = maxSessions;
    }

    // ==================== 环境管理 ====================

    /**
     * 注册 IDE 环境 — 返回环境 ID 和密钥。
     *
     * @param config 环境配置
     * @return 桥接环境信息
     */
    public BridgeEnvironment registerEnvironment(Map<String, Object> config) {
        String envId = config.containsKey("environmentId")
                ? config.get("environmentId").toString()
                : UUID.randomUUID().toString();
        String envSecret = BridgeUtils.WorkSecretUtil.generate();

        EnvironmentInfo info = new EnvironmentInfo(
                envId, envSecret, config, Instant.now());
        environments.put(envId, info);
        state = BridgeState.CONNECTED;

        log.info("Environment registered: {}", envId);
        return new BridgeEnvironment(envId, envSecret);
    }

    /** 注销环境 */
    public void unregisterEnvironment(String envId) {
        EnvironmentInfo removed = environments.remove(envId);
        if (removed != null) {
            // 关闭该环境下的所有会话
            activeSessions.entrySet().removeIf(entry -> {
                if (envId.equals(entry.getValue().environmentId())) {
                    log.info("Closing session {} due to environment unregister", entry.getKey());
                    return true;
                }
                return false;
            });
            log.info("Environment unregistered: {}", envId);
        }
        if (environments.isEmpty()) {
            state = BridgeState.READY;
        }
    }

    /** 获取注册的环境数量 */
    public int environmentCount() {
        return environments.size();
    }

    // ==================== 会话管理 ====================

    /**
     * 创建会话。
     *
     * @param request 创建会话请求
     * @return 会话句柄
     * @throws IllegalStateException 如果已达最大会话数
     */
    public SessionHandle createSession(CreateSessionRequest request) {
        if (activeSessions.size() >= maxSessions) {
            throw new IllegalStateException(
                    "Max sessions reached: " + maxSessions);
        }

        String sessionId = request.sessionId() != null
                ? request.sessionId()
                : "session_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String envId = request.environmentId();

        SessionHandle handle = new SessionHandle(
                sessionId, envId, Instant.now(),
                new CompletableFuture<>());
        activeSessions.put(sessionId, handle);

        log.info("Session created: {} (env={})", sessionId, envId);

        // 发送 bridge_ready 消息
        BridgeMessage readyMsg = BridgeMessage.ready(
                sessionId, "1.0.0",
                List.of("tool_result", "permission_request", "session_update"));
        broadcastMessage(readyMsg);

        return handle;
    }

    /** 关闭会话 */
    public void closeSession(String sessionId) {
        SessionHandle handle = activeSessions.remove(sessionId);
        if (handle != null) {
            handle.done().complete(SessionDoneStatus.COMPLETED);
            // 取消该会话的待处理权限请求
            pendingPermissions.entrySet().removeIf(entry -> {
                if (entry.getKey().startsWith(sessionId + ":")) {
                    entry.getValue().complete(PermissionDecision.DENY);
                    return true;
                }
                return false;
            });
            log.info("Session closed: {}", sessionId);
        }
    }

    /** 获取会话句柄 */
    public Optional<SessionHandle> getSession(String sessionId) {
        return Optional.ofNullable(activeSessions.get(sessionId));
    }

    /** 当前活跃会话数 */
    public int activeSessionCount() {
        return activeSessions.size();
    }

    /** 是否已达容量上限 */
    public boolean isAtCapacity() {
        return activeSessions.size() >= maxSessions;
    }

    // ==================== 消息处理 ====================

    /**
     * 处理 IDE→Server 消息。
     *
     * @param sessionId 会话 ID
     * @param message   桥接消息
     */
    public void handleMessage(String sessionId, BridgeMessage message) {
        // 消息去重
        if (message.id() != null && !messageDedup.add(message.id())) {
            log.debug("Duplicate message ignored: {}", message.id());
            return;
        }

        // epoch 验证
        if (message.epoch() > 0 && !epochManager.isCurrentEpoch(message.epoch())) {
            log.debug("Stale epoch message ignored: epoch={}, current={}",
                    message.epoch(), epochManager.currentEpoch());
            return;
        }

        log.debug("Handling message: type={}, session={}", message.type(), sessionId);

        switch (message.type()) {
            case "bridge_init" -> handleInit(sessionId, message);
            case "bridge_auth" -> handleAuth(sessionId, message);
            case "bridge_subscribe" -> handleSubscribe(sessionId, message);
            case "bridge_command" -> handleCommand(sessionId, message);
            case "bridge_file_open" -> handleFileOpen(sessionId, message);
            case "bridge_diff_apply" -> handleDiffApply(sessionId, message);
            case "bridge_ping" -> handlePing(sessionId);
            default -> log.warn("Unknown message type: {}", message.type());
        }

        // 通知监听器
        for (Consumer<BridgeMessage> listener : messageListeners) {
            try {
                listener.accept(message);
            } catch (Exception e) {
                log.error("Message listener error", e);
            }
        }
    }

    /** 注册消息监听器 */
    public void addMessageListener(Consumer<BridgeMessage> listener) {
        messageListeners.add(listener);
    }

    /** 移除消息监听器 */
    public void removeMessageListener(Consumer<BridgeMessage> listener) {
        messageListeners.remove(listener);
    }

    // ==================== 权限请求 ====================

    /**
     * 发送权限请求到 IDE。
     *
     * @param sessionId 会话 ID
     * @param toolName  工具名称
     * @param toolInput 工具输入
     * @return 权限决策 Future
     */
    public CompletableFuture<PermissionDecision> requestPermission(
            String sessionId, String toolName, Map<String, Object> toolInput) {
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<PermissionDecision> future = new CompletableFuture<>();
        pendingPermissions.put(sessionId + ":" + requestId, future);

        // 发送权限请求消息到 IDE
        BridgeMessage permMsg = BridgeMessage.of("bridge_permission_request", Map.of(
                "toolUseId", requestId,
                "toolName", toolName,
                "riskLevel", "normal"));
        broadcastMessage(permMsg);

        // 设置超时（5 分钟）
        future.orTimeout(5, TimeUnit.MINUTES)
                .whenComplete((result, error) -> {
                    pendingPermissions.remove(sessionId + ":" + requestId);
                    if (error != null) {
                        log.warn("Permission request timed out: tool={}, session={}",
                                toolName, sessionId);
                    }
                });

        return future;
    }

    /**
     * 响应权限请求。
     *
     * @param sessionId 会话 ID
     * @param requestId 请求 ID
     * @param decision  决策
     */
    public void respondToPermission(String sessionId, String requestId, PermissionDecision decision) {
        CompletableFuture<PermissionDecision> future =
                pendingPermissions.remove(sessionId + ":" + requestId);
        if (future != null) {
            future.complete(decision);
            log.info("Permission responded: {} → {}", requestId, decision);
        }
    }

    // ==================== 状态查询 ====================

    /** 获取当前桥接状态 */
    public BridgeState getState() { return state; }

    /** 获取当前 epoch */
    public long getCurrentEpoch() { return epochManager.currentEpoch(); }

    /** 递增 epoch（重连时调用） */
    public long incrementEpoch() { return epochManager.incrementEpoch(); }

    /** 获取轮询配置 */
    public BridgePollConfig getPollConfig() { return pollConfig; }

    // ==================== 消息处理方法 ====================

    private void handleInit(String sessionId, BridgeMessage message) {
        log.info("Bridge init: session={}, payload={}", sessionId, message.payload());
        // 验证 extensionId → 已在 createSession 中发送 bridge_ready
    }

    private void handleAuth(String sessionId, BridgeMessage message) {
        Object issuer = message.payload().get("issuer");
        log.info("Bridge auth: session={}, issuer={}", sessionId, issuer);
        // 验证 issuer — REPL: "claude-code-ide-extension", Remote: "claude-code-remote"
    }

    private void handleSubscribe(String sessionId, BridgeMessage message) {
        Object topics = message.payload().get("topics");
        log.info("Bridge subscribe: session={}, topics={}", sessionId, topics);
    }

    private void handleCommand(String sessionId, BridgeMessage message) {
        String command = message.payload().get("command") != null
                ? message.payload().get("command").toString() : null;
        log.info("Bridge command: session={}, command={}", sessionId, command);
    }

    private void handleFileOpen(String sessionId, BridgeMessage message) {
        String filePath = message.payload().get("filePath") != null
                ? message.payload().get("filePath").toString() : null;
        log.info("Bridge file open: session={}, file={}", sessionId, filePath);
    }

    private void handleDiffApply(String sessionId, BridgeMessage message) {
        String filePath = message.payload().get("filePath") != null
                ? message.payload().get("filePath").toString() : null;
        log.info("Bridge diff apply: session={}, file={}", sessionId, filePath);
    }

    private void handlePing(String sessionId) {
        BridgeMessage pong = BridgeMessage.pong();
        broadcastMessage(pong);
        log.debug("Bridge pong sent for session={}", sessionId);
    }

    private void broadcastMessage(BridgeMessage message) {
        for (Consumer<BridgeMessage> listener : messageListeners) {
            try {
                listener.accept(message);
            } catch (Exception e) {
                log.error("Broadcast error", e);
            }
        }
    }

    // ==================== 内部类型 ====================

    /** 桥接环境 */
    public record BridgeEnvironment(String environmentId, String environmentSecret) {}

    /** 环境信息 */
    record EnvironmentInfo(
            String envId, String envSecret,
            Map<String, Object> config, Instant registeredAt) {}

    /** 创建会话请求 */
    public record CreateSessionRequest(
            String sessionId,
            String environmentId,
            BridgeProtocolVersion protocol) {

        public static CreateSessionRequest of(String envId) {
            return new CreateSessionRequest(null, envId, BridgeProtocolVersion.V1_HYBRID);
        }

        public static CreateSessionRequest of(String envId, BridgeProtocolVersion protocol) {
            return new CreateSessionRequest(null, envId, protocol);
        }
    }

    /** 会话句柄 */
    public record SessionHandle(
            String sessionId,
            String environmentId,
            Instant createdAt,
            CompletableFuture<SessionDoneStatus> done) {}

    /** 会话结束状态 */
    public enum SessionDoneStatus { COMPLETED, FAILED, INTERRUPTED }

    /** 权限决策 */
    public enum PermissionDecision { ALLOW, DENY, ALLOW_ALWAYS }
}
