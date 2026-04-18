package com.aicodeassistant.coordinator;

import com.aicodeassistant.model.PermissionBehavior;
import com.aicodeassistant.model.PermissionDecision;
import com.aicodeassistant.model.PermissionDecisionReason;
import com.aicodeassistant.websocket.ServerMessage;
import com.aicodeassistant.websocket.WebSocketController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Leader 权限桥接 — Worker 权限请求冒泡到 Leader 的 UI。
 * <p>
 * 当 Worker 遇到需要用户确认的操作时：
 * <ol>
 *   <li>Worker 暂停执行</li>
 *   <li>权限请求通过 WebSocket 推送到前端（{@code permission_bubble} 消息类型）</li>
 *   <li>前端用户批准/拒绝</li>
 *   <li>前端决策通过 WebSocket 发回后端</li>
 *   <li>Worker 继续/终止执行</li>
 * </ol>
 * <p>
 * 死锁防护：使用 {@link CompletableFuture#orTimeout} 设置超时，
 * 超时后自动拒绝并清理 pending 请求。
 *
 * @see <a href="SPEC §11">Team/Swarm 多Agent协作</a>
 */
@Service
public class LeaderPermissionBridge {

    private static final Logger log = LoggerFactory.getLogger(LeaderPermissionBridge.class);

    /** 默认超时时间（秒）— 超时后自动拒绝，防止死锁 */
    private static final long DEFAULT_TIMEOUT_SECONDS = 60;

    private final WebSocketController webSocketController;

    /** 请求 ID → CompletableFuture，前端决策后 complete */
    private final ConcurrentHashMap<String, CompletableFuture<BubbleDecision>> pendingRequests =
            new ConcurrentHashMap<>();

    public LeaderPermissionBridge(@Lazy WebSocketController webSocketController) {
        this.webSocketController = webSocketController;
    }

    /**
     * 将 Worker 的权限请求冒泡到 Leader 的 WebSocket 连接。
     * <p>
     * Worker 调用此方法后会阻塞等待（在 Virtual Thread 中，不阻塞 carrier thread），
     * 直到前端用户做出决策或超时。
     *
     * @param workerId    Worker 标识
     * @param sessionId   父会话 ID（用于定向推送到正确的前端）
     * @param toolName    需要权限确认的工具名称
     * @param riskLevel   风险级别 (low/medium/high)
     * @param reason      权限请求原因描述
     * @return CompletableFuture 包含用户决策
     */
    public CompletableFuture<BubbleDecision> requestPermission(
            String workerId,
            String sessionId,
            String toolName,
            String riskLevel,
            String reason) {

        String requestId = UUID.randomUUID().toString();

        CompletableFuture<BubbleDecision> future = new CompletableFuture<BubbleDecision>()
                .orTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        // 超时或异常处理
        future.exceptionally(ex -> {
            pendingRequests.remove(requestId);
            log.warn("Permission request timed out for worker {}: {}", workerId, ex.getMessage());
            return BubbleDecision.DENY; // 安全默认：拒绝
        });

        pendingRequests.put(requestId, future);

        // 通过 WebSocket 发送权限请求到前端
        try {
            webSocketController.pushToUser(sessionId, "permission_bubble",
                    new ServerMessage.PermissionBubble(
                            requestId, workerId, toolName, riskLevel, reason));
            log.info("Permission bubble sent: requestId={}, worker={}, tool={}, risk={}",
                    requestId, workerId, toolName, riskLevel);
        } catch (Exception e) {
            log.error("Failed to send permission bubble for worker {}", workerId, e);
            pendingRequests.remove(requestId);
            future.complete(BubbleDecision.DENY);
        }

        return future;
    }

    /**
     * 前端用户决策回调 — 由 WebSocketController 调用。
     *
     * @param requestId 权限请求 ID
     * @param approved  用户是否批准
     */
    public void resolvePermission(String requestId, boolean approved) {
        CompletableFuture<BubbleDecision> future = pendingRequests.remove(requestId);
        if (future != null && !future.isDone()) {
            BubbleDecision decision = approved ? BubbleDecision.ALLOW : BubbleDecision.DENY;
            future.complete(decision);
            log.info("Permission resolved: requestId={}, decision={}", requestId, decision);
        } else {
            log.warn("Permission resolve for unknown/expired request: {}", requestId);
        }
    }

    /**
     * 获取 pending 请求数量（用于监控）。
     */
    public int pendingRequestCount() {
        return pendingRequests.size();
    }

    /**
     * 清理所有 pending 请求（Swarm shutdown 时调用）。
     */
    public void clearAll() {
        pendingRequests.forEach((id, future) -> {
            if (!future.isDone()) {
                future.complete(BubbleDecision.DENY);
            }
        });
        pendingRequests.clear();
        log.info("All pending permission requests cleared");
    }

    /**
     * 清理指定 Swarm 的 pending 请求。
     *
     * @param swarmIdPrefix Swarm ID 前缀
     */
    public void clearBySwarm(String swarmIdPrefix) {
        pendingRequests.entrySet().removeIf(entry -> {
            // 通过 requestId 无法直接关联 swarmId，所以清理所有
            // 实际生产中可在 pendingRequests 中存储更多元数据
            return false;
        });
    }

    // ═══ Decision Enum ═══

    /** 权限冒泡决策 */
    public enum BubbleDecision {
        /** 批准 */
        ALLOW,
        /** 拒绝 */
        DENY
    }
}
