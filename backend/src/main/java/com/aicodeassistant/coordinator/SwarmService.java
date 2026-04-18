package com.aicodeassistant.coordinator;

import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.coordinator.SwarmState.SwarmPhase;
import com.aicodeassistant.coordinator.SwarmState.WorkerState;
import com.aicodeassistant.coordinator.SwarmWorkerRunner.WorkerResult;
import com.aicodeassistant.tool.ToolUseContext;
import com.aicodeassistant.websocket.ServerMessage;
import com.aicodeassistant.websocket.WebSocketController;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Swarm 服务 — Agent Swarms 多代理并行协作核心。
 * <p>
 * 管理 Swarm 实例的完整生命周期：创建 → 添加 Worker → 执行 → 通信 → 关闭。
 * <ul>
 *   <li>Worker 使用 Java 21 Virtual Thread 并发执行</li>
 *   <li>邮箱通信使用 {@link TeamMailbox} (ConcurrentLinkedQueue)</li>
 *   <li>权限冒泡通过 {@link LeaderPermissionBridge} 异步等待</li>
 *   <li>状态变更通过 WebSocket 推送到前端</li>
 * </ul>
 * <p>
 * 所有公开方法入口均调用 {@link #ensureSwarmEnabled()} 进行 feature flag 门控检查。
 *
 * @see <a href="SPEC §11">Team/Swarm 多Agent协作</a>
 */
@Service
public class SwarmService {

    private static final Logger log = LoggerFactory.getLogger(SwarmService.class);

    private final FeatureFlagService featureFlags;
    private final TeamManager teamManager;
    private final TeamMailbox teamMailbox;
    private final SwarmWorkerRunner workerRunner;
    private final LeaderPermissionBridge permissionBridge;
    private final WebSocketController webSocketController;

    /**
     * 活跃 Swarm 实例缓存 — 使用 Caffeine 带 TTL 防止内存泄漏。
     * 异常终止的 Swarm 4 小时后自动清理。
     */
    private final Cache<String, SwarmState> activeSwarms = Caffeine.newBuilder()
            .maximumSize(50)
            .expireAfterWrite(Duration.ofHours(4))
            .removalListener((String key, SwarmState value, RemovalCause cause) -> {
                if (cause.wasEvicted()) {
                    log.warn("Swarm {} evicted from activeSwarms (cause: {})", key, cause);
                }
            })
            .build();

    /** Swarm ID → 父会话 ID 映射（用于 WebSocket 推送） */
    private final ConcurrentHashMap<String, String> swarmSessionMap = new ConcurrentHashMap<>();

    /** Swarm ID → Worker Futures（用于 shutdown 等待） */
    private final ConcurrentHashMap<String, List<CompletableFuture<WorkerResult>>> workerFutures =
            new ConcurrentHashMap<>();

    public SwarmService(FeatureFlagService featureFlags,
                        TeamManager teamManager,
                        TeamMailbox teamMailbox,
                        SwarmWorkerRunner workerRunner,
                        LeaderPermissionBridge permissionBridge,
                        @Lazy WebSocketController webSocketController) {
        this.featureFlags = featureFlags;
        this.teamManager = teamManager;
        this.teamMailbox = teamMailbox;
        this.workerRunner = workerRunner;
        this.permissionBridge = permissionBridge;
        this.webSocketController = webSocketController;
    }

    // ═══════════════════════════════════════════════════════════════
    // 1. 创建 Swarm
    // ═══════════════════════════════════════════════════════════════

    /**
     * 创建 Swarm 实例。
     *
     * @param config        Swarm 配置
     * @param sessionId     父会话 ID（用于 WebSocket 推送）
     * @return SwarmState 初始状态
     */
    public SwarmState createSwarm(SwarmConfig config, String sessionId) {
        ensureSwarmEnabled();

        String swarmId = "swarm-" + UUID.randomUUID().toString().substring(0, 8);

        // 创建 TeamManager 条目
        teamManager.createTeam(config.teamName(), config.maxWorkers(), swarmId);

        // 初始化 Scratchpad 目录
        if (config.scratchpadDir() != null) {
            try {
                Files.createDirectories(config.scratchpadDir());
            } catch (IOException e) {
                log.warn("Failed to create scratchpad dir: {}", config.scratchpadDir(), e);
            }
        }

        // 注册 SwarmState
        SwarmState state = new SwarmState(swarmId, config.teamName());
        activeSwarms.put(swarmId, state);
        swarmSessionMap.put(swarmId, sessionId);
        workerFutures.put(swarmId, Collections.synchronizedList(new ArrayList<>()));

        log.info("Swarm created: {} (team={}, maxWorkers={}, session={})",
                swarmId, config.teamName(), config.maxWorkers(), sessionId);

        // 推送状态更新到前端
        pushSwarmStateUpdate(swarmId, state, sessionId);

        return state;
    }

    // ═══════════════════════════════════════════════════════════════
    // 2. 添加 Worker
    // ═══════════════════════════════════════════════════════════════

    /**
     * 向 Swarm 添加 Worker 并立即开始执行任务。
     *
     * @param swarmId       Swarm ID
     * @param taskPrompt    Worker 任务提示
     * @param config        Swarm 配置
     * @param parentContext 父查询上下文
     * @return Worker ID
     * @throws IllegalStateException 如果 Swarm 不存在或已达到最大 Worker 数量
     */
    public String addWorker(String swarmId, String taskPrompt,
                             SwarmConfig config, ToolUseContext parentContext) {
        ensureSwarmEnabled();

        SwarmState state = getSwarmOrThrow(swarmId);

        if (state.workers().size() >= config.maxWorkers()) {
            throw new IllegalStateException("Max workers reached: " + config.maxWorkers());
        }

        String workerId = swarmId + "-worker-" + state.workers().size();

        // 注册 Worker 到状态表
        state.registerWorker(workerId, taskPrompt);

        // 切换到 RUNNING 阶段
        state.transitionPhase(SwarmPhase.INITIALIZING, SwarmPhase.RUNNING);

        // 通过 SwarmWorkerRunner 启动 Worker
        CompletableFuture<WorkerResult> future = workerRunner.startWorker(
                workerId, taskPrompt, config, parentContext, state);

        // Worker 完成回调
        String sessionId = swarmSessionMap.get(swarmId);
        future.thenAccept(result -> onWorkerComplete(swarmId, workerId, result, sessionId));

        // 记录 Future
        List<CompletableFuture<WorkerResult>> futures = workerFutures.get(swarmId);
        if (futures != null) {
            futures.add(future);
        }

        log.info("Worker added: {} (swarm={}, task='{}')", workerId, swarmId,
                taskPrompt.length() > 60 ? taskPrompt.substring(0, 60) + "..." : taskPrompt);

        // 推送 Worker 进度到前端
        pushWorkerProgress(swarmId, state.workers().get(workerId), sessionId);
        pushSwarmStateUpdate(swarmId, state, sessionId);

        return workerId;
    }

    /**
     * 批量提交任务到 Swarm。
     *
     * @param swarmId   Swarm ID
     * @param tasks     任务列表
     * @param config    Swarm 配置
     * @param parentContext 父查询上下文
     * @return Worker ID 列表
     */
    public List<String> submitTasks(String swarmId, List<String> tasks,
                                     SwarmConfig config, ToolUseContext parentContext) {
        ensureSwarmEnabled();

        List<String> workerIds = new ArrayList<>();
        for (String task : tasks) {
            try {
                String workerId = addWorker(swarmId, task, config, parentContext);
                workerIds.add(workerId);
            } catch (IllegalStateException e) {
                log.warn("Could not add worker for task (max reached): {}", e.getMessage());
                break;
            }
        }
        return workerIds;
    }

    // ═══════════════════════════════════════════════════════════════
    // 3. 通信方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 发送消息到指定 Worker。
     */
    public void sendToWorker(String swarmId, String workerId, String message) {
        ensureSwarmEnabled();
        teamMailbox.writeToMailbox(workerId, swarmId + "-leader", message);
    }

    /**
     * 广播消息到 Swarm 中所有 Worker。
     */
    public void broadcastToWorkers(String swarmId, String message) {
        ensureSwarmEnabled();
        teamMailbox.broadcast(swarmId, swarmId + "-leader", message);
    }

    // ═══════════════════════════════════════════════════════════════
    // 4. 状态查询
    // ═══════════════════════════════════════════════════════════════

    /**
     * 获取 Swarm 状态。
     *
     * @param swarmId Swarm ID
     * @return SwarmState 或 null
     */
    public SwarmState getSwarmState(String swarmId) {
        ensureSwarmEnabled();
        return activeSwarms.getIfPresent(swarmId);
    }

    /**
     * 列出所有活跃 Swarm。
     */
    public Map<String, SwarmState> listActiveSwarms() {
        ensureSwarmEnabled();
        return activeSwarms.asMap();
    }

    // ═══════════════════════════════════════════════════════════════
    // 5. 关闭 Swarm
    // ═══════════════════════════════════════════════════════════════

    /**
     * 优雅关闭 Swarm — 通知 Worker 停止，等待完成后清理资源。
     *
     * @param swarmId Swarm ID
     */
    public void shutdownSwarm(String swarmId) {
        ensureSwarmEnabled();

        SwarmState state = activeSwarms.getIfPresent(swarmId);
        if (state == null) {
            log.warn("Swarm not found for shutdown: {}", swarmId);
            return;
        }

        // 切换到 SHUTTING_DOWN 阶段
        state.forcePhase(SwarmPhase.SHUTTING_DOWN);
        String sessionId = swarmSessionMap.get(swarmId);
        pushSwarmStateUpdate(swarmId, state, sessionId);

        // 广播 shutdown 请求给所有 Worker
        broadcastToWorkers(swarmId, "{\"type\": \"shutdown_request\"}");

        // 异步等待所有 Worker 完成后清理
        CompletableFuture.runAsync(() -> {
            try {
                // 等待最多 30 秒
                List<CompletableFuture<WorkerResult>> futures = workerFutures.get(swarmId);
                if (futures != null && !futures.isEmpty()) {
                    CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                            .orTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                            .join();
                }
            } catch (Exception e) {
                log.warn("Timeout waiting for workers to shutdown in swarm {}", swarmId);
            } finally {
                // 清理资源
                cleanupSwarm(swarmId);
            }
        });

        log.info("Swarm shutdown initiated: {}", swarmId);
    }

    /**
     * 强制停止 Swarm — 立即清理，不等待 Worker。
     */
    public void forceStopSwarm(String swarmId) {
        ensureSwarmEnabled();
        cleanupSwarm(swarmId);
        log.info("Swarm force stopped: {}", swarmId);
    }

    // ═══════════════════════════════════════════════════════════════
    // 内部方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * Worker 完成回调 — 更新状态并推送到前端。
     */
    private void onWorkerComplete(String swarmId, String workerId,
                                   WorkerResult result, String sessionId) {
        SwarmState state = activeSwarms.getIfPresent(swarmId);
        if (state == null) return;

        log.info("Worker completed: {} (swarm={}, turns={}, tokens={})",
                workerId, swarmId, result.turnCount(), result.tokensConsumed());

        // 推送 Worker 进度更新
        WorkerState ws = state.workers().get(workerId);
        if (ws != null) {
            pushWorkerProgress(swarmId, ws, sessionId);
        }

        // 检查是否所有 Worker 都已完成
        boolean allDone = state.workers().values().stream()
                .allMatch(w -> w.status() == SwarmState.WorkerStatus.IDLE
                        || w.status() == SwarmState.WorkerStatus.TERMINATED);

        if (allDone && state.phase() == SwarmPhase.RUNNING) {
            state.transitionPhase(SwarmPhase.RUNNING, SwarmPhase.IDLE);
            log.info("Swarm {} all workers completed, entering IDLE phase", swarmId);
        }

        // 推送 Swarm 状态更新
        pushSwarmStateUpdate(swarmId, state, sessionId);
    }

    /**
     * 清理 Swarm 资源。
     */
    private void cleanupSwarm(String swarmId) {
        SwarmState state = activeSwarms.getIfPresent(swarmId);
        if (state != null) {
            state.forcePhase(SwarmPhase.TERMINATED);
            // 标记所有未完成 Worker 为终止
            state.workers().forEach((id, ws) -> {
                if (ws.status() != SwarmState.WorkerStatus.IDLE
                        && ws.status() != SwarmState.WorkerStatus.TERMINATED) {
                    state.markWorkerTerminated(id);
                }
            });

            // 推送最终状态
            String sessionId = swarmSessionMap.get(swarmId);
            pushSwarmStateUpdate(swarmId, state, sessionId);
        }

        activeSwarms.invalidate(swarmId);
        swarmSessionMap.remove(swarmId);
        workerFutures.remove(swarmId);

        // 清理相关邮箱
        if (state != null) {
            state.workers().keySet().forEach(teamMailbox::clearMailbox);
        }
        teamMailbox.clearMailbox(swarmId + "-leader");

        // 清理权限请求
        permissionBridge.clearAll();

        // 销毁团队
        if (state != null) {
            teamManager.destroyTeam(state.teamName());
        }

        log.info("Swarm cleaned up: {}", swarmId);
    }

    /**
     * 门控检查 — 统一入口，所有公开方法首先调用。
     *
     * @throws IllegalStateException 当 ENABLE_AGENT_SWARMS 未启用时
     */
    private void ensureSwarmEnabled() {
        if (!featureFlags.isEnabled("ENABLE_AGENT_SWARMS")) {
            throw new IllegalStateException(
                    "Agent Swarms feature is disabled. Enable 'ENABLE_AGENT_SWARMS' flag to use.");
        }
    }

    /**
     * 获取 SwarmState 或抛异常。
     */
    private SwarmState getSwarmOrThrow(String swarmId) {
        SwarmState state = activeSwarms.getIfPresent(swarmId);
        if (state == null) {
            throw new IllegalStateException("Swarm not found: " + swarmId);
        }
        return state;
    }

    // ═══════════════════════════════════════════════════════════════
    // WebSocket 推送方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 推送 Swarm 状态更新到前端。
     */
    private void pushSwarmStateUpdate(String swarmId, SwarmState state, String sessionId) {
        if (sessionId == null) return;

        Map<String, ServerMessage.SwarmStateUpdate.WorkerSnapshot> workerSnapshots = new LinkedHashMap<>();
        state.workers().forEach((id, ws) -> workerSnapshots.put(id,
                new ServerMessage.SwarmStateUpdate.WorkerSnapshot(
                        ws.workerId(), ws.status().name(), ws.currentTask(),
                        ws.toolCallCount(), ws.tokenConsumed())));

        try {
            webSocketController.pushToUser(sessionId, "swarm_state_update",
                    new ServerMessage.SwarmStateUpdate(
                            swarmId,
                            state.phase().name(),
                            state.activeWorkerCount(),
                            state.workers().size(),
                            state.completedTaskCount(),
                            state.totalTaskCount(),
                            workerSnapshots));
        } catch (Exception e) {
            log.debug("Failed to push swarm state update: {}", e.getMessage());
        }
    }

    /**
     * 推送 Worker 进度到前端。
     */
    private void pushWorkerProgress(String swarmId, WorkerState ws, String sessionId) {
        if (sessionId == null || ws == null) return;

        try {
            webSocketController.pushToUser(sessionId, "worker_progress",
                    new ServerMessage.WorkerProgress(
                            swarmId,
                            ws.workerId(),
                            ws.status().name(),
                            ws.currentTask(),
                            ws.toolCallCount(),
                            ws.tokenConsumed(),
                            ws.recentToolCalls()));
        } catch (Exception e) {
            log.debug("Failed to push worker progress: {}", e.getMessage());
        }
    }
}
