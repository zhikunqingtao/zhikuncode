package com.aicodeassistant.coordinator;

import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.model.dto.AbortRequest;
import com.aicodeassistant.model.dto.AbortResponse;
import com.aicodeassistant.service.AnomalyEventRepository;
import com.aicodeassistant.websocket.WebSocketSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.security.Principal;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Swarm API 端点 — Agent Swarms 多代理协作。
 * <p>
 * 端点入口添加 FeatureFlagService 前置检查，
 * ENABLE_AGENT_SWARMS=false 时返回 403。
 */
@RestController
@RequestMapping("/api/swarm")
public class SwarmController {

    private static final Logger log = LoggerFactory.getLogger(SwarmController.class);

    private final SwarmService swarmService;
    private final FeatureFlagService featureFlags;
    private final LeaderPermissionBridge permissionBridge;
    private final AnomalyEventRepository anomalyEventRepository;
    private final WebSocketSessionManager webSocketSessionManager;

    /** teamName 白名单：字母/数字/下划线/中划线，长度 1-64；禁止路径分隔符与 .. 防止 scratchpad 路径穿越。 */
    private static final Pattern TEAM_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    public SwarmController(SwarmService swarmService,
                            FeatureFlagService featureFlags,
                            LeaderPermissionBridge permissionBridge,
                            AnomalyEventRepository anomalyEventRepository,
                            WebSocketSessionManager webSocketSessionManager) {
        this.swarmService = swarmService;
        this.featureFlags = featureFlags;
        this.permissionBridge = permissionBridge;
        this.anomalyEventRepository = anomalyEventRepository;
        this.webSocketSessionManager = webSocketSessionManager;
    }

    /**
     * 创建 Swarm 实例。
     * POST /api/swarm
     * Body: { "teamName": "...", "maxWorkers": 5, "sessionId": "..." }
     */
    @PostMapping
    public ResponseEntity<?> createSwarm(@RequestBody Map<String, Object> request) {
        if (!featureFlags.isEnabled("ENABLE_AGENT_SWARMS")) {
            return ResponseEntity.status(403)
                    .body(Map.of("error", "Agent Swarms feature is disabled"));
        }

        String teamName = (String) request.getOrDefault("teamName", "swarm-team-" + UUID.randomUUID().toString().substring(0, 6));
        if (teamName == null || !TEAM_NAME_PATTERN.matcher(teamName).matches()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid teamName",
                    "reason", "teamName must match ^[A-Za-z0-9_-]{1,64}$ (path traversal prevention)"
            ));
        }
        int maxWorkers = request.containsKey("maxWorkers")
                ? ((Number) request.get("maxWorkers")).intValue()
                : SwarmConfig.DEFAULT_MAX_WORKERS;
        String sessionId = (String) request.getOrDefault("sessionId", "default");

        Path scratchpadDir = Path.of(System.getProperty("user.dir"), ".zhikun", "scratchpad", teamName);
        SwarmConfig config = SwarmConfig.withWorkers(teamName, maxWorkers, scratchpadDir);

        SwarmState state = swarmService.createSwarm(config, sessionId);
        return ResponseEntity.ok(Map.of(
                "swarmId", state.swarmId(),
                "teamName", state.teamName(),
                "phase", state.phase().name(),
                "maxWorkers", maxWorkers
        ));
    }

    /**
     * 获取 Swarm 状态。
     * GET /api/swarm/{swarmId}
     */
    @GetMapping("/{swarmId}")
    public ResponseEntity<?> getSwarmState(@PathVariable String swarmId) {
        if (!featureFlags.isEnabled("ENABLE_AGENT_SWARMS")) {
            return ResponseEntity.status(403)
                    .body(Map.of("error", "Agent Swarms feature is disabled"));
        }

        SwarmState state = swarmService.getSwarmState(swarmId);
        if (state == null) {
            return ResponseEntity.notFound().build();
        }

        Map<String, Object> workersInfo = new LinkedHashMap<>();
        state.workers().forEach((id, ws) -> workersInfo.put(id, Map.of(
                "status", ws.status().name(),
                "currentTask", ws.currentTask() != null ? ws.currentTask() : "",
                "toolCallCount", ws.toolCallCount(),
                "tokenConsumed", ws.tokenConsumed()
        )));

        return ResponseEntity.ok(Map.of(
                "swarmId", state.swarmId(),
                "teamName", state.teamName(),
                "phase", state.phase().name(),
                "activeWorkers", state.activeWorkerCount(),
                "totalWorkers", state.workers().size(),
                "completedTasks", state.completedTaskCount(),
                "totalTasks", state.totalTaskCount(),
                "workers", workersInfo
        ));
    }

    /**
     * 关闭 Swarm。
     * POST /api/swarm/{swarmId}/shutdown
     */
    @PostMapping("/{swarmId}/shutdown")
    public ResponseEntity<?> shutdownSwarm(@PathVariable String swarmId) {
        if (!featureFlags.isEnabled("ENABLE_AGENT_SWARMS")) {
            return ResponseEntity.status(403)
                    .body(Map.of("error", "Agent Swarms feature is disabled"));
        }

        swarmService.shutdownSwarm(swarmId);
        return ResponseEntity.ok(Map.of("status", "shutdown_initiated", "swarmId", swarmId));
    }

    /**
     * 强制停止 Swarm。
     * POST /api/swarm/{swarmId}/force-stop
     */
    @PostMapping("/{swarmId}/force-stop")
    public ResponseEntity<?> forceStopSwarm(@PathVariable String swarmId) {
        if (!featureFlags.isEnabled("ENABLE_AGENT_SWARMS")) {
            return ResponseEntity.status(403)
                    .body(Map.of("error", "Agent Swarms feature is disabled"));
        }

        swarmService.forceStopSwarm(swarmId);
        return ResponseEntity.ok(Map.of("status", "force_stopped", "swarmId", swarmId));
    }

    /**
     * 列出所有活跃 Swarm。
     * GET /api/swarm
     */
    @GetMapping
    public ResponseEntity<?> listSwarms() {
        if (!featureFlags.isEnabled("ENABLE_AGENT_SWARMS")) {
            return ResponseEntity.status(403)
                    .body(Map.of("error", "Agent Swarms feature is disabled"));
        }

        List<Map<String, Object>> swarms = new ArrayList<>();
        swarmService.listActiveSwarms().forEach((id, state) -> swarms.add(Map.of(
                "swarmId", state.swarmId(),
                "teamName", state.teamName(),
                "phase", state.phase().name(),
                "activeWorkers", state.activeWorkerCount(),
                "totalWorkers", state.workers().size()
        )));

        return ResponseEntity.ok(Map.of("swarms", swarms));
    }

    /**
     * 中止指定 Worker。
     * POST /api/swarm/{swarmId}/worker/{workerId}/abort
     */
    @PostMapping("/{swarmId}/worker/{workerId}/abort")
    public ResponseEntity<?> abortWorker(
            @PathVariable String swarmId,
            @PathVariable String workerId,
            @RequestBody AbortRequest request,
            Principal principal) {
        if (!featureFlags.isEnabled("ENABLE_AGENT_SWARMS")) {
            return ResponseEntity.status(403)
                    .body(Map.of("error", "Agent Swarms feature is disabled"));
        }

        // 1. 验证 swarm 和 worker 存在
        SwarmState state = swarmService.getSwarmState(swarmId);
        if (state == null) {
            return ResponseEntity.notFound().build();
        }
        SwarmState.WorkerState workerState = state.workers().get(workerId);
        if (workerState == null) {
            return ResponseEntity.notFound().build();
        }

        // 2. 三层防御身份验证：仅 Swarm 创建者可 abort Worker
        String ownerSessionId = swarmService.getSessionIdForSwarm(swarmId);
        if (ownerSessionId != null) {
            boolean verified = false;
            String verifyPath = "none";

            // 第1层：请求体 sessionId 验证（主路径，同维度UUID比较）
            String reqSessionId = request.sessionId();
            if (reqSessionId != null && !reqSessionId.isBlank()) {
                if (ownerSessionId.equals(reqSessionId)) {
                    verified = true;
                    verifyPath = "layer1-request-body";
                } else {
                    log.warn("Abort denied [layer1]: sessionId mismatch for swarm={}", swarmId);
                    log.debug("Abort denied detail [layer1]: reqSessionId=...{}, ownerSessionId=...{} for swarm={}",
                            reqSessionId != null && reqSessionId.length() > 6 ? reqSessionId.substring(reqSessionId.length() - 6) : reqSessionId,
                            ownerSessionId != null && ownerSessionId.length() > 6 ? ownerSessionId.substring(ownerSessionId.length() - 6) : ownerSessionId,
                            swarmId);
                    return ResponseEntity.status(403)
                            .body(Map.of("error", "Unauthorized: only the swarm owner can abort workers"));
                }
            }

            // 第2层：Principal 交叉验证（多用户增强）
            if (principal != null) {
                String principalName = principal.getName();
                String mappedSessionId = webSocketSessionManager.getSessionForPrincipal(principalName);
                if (mappedSessionId != null) {
                    if (ownerSessionId.equals(mappedSessionId)) {
                        verified = true;
                        verifyPath = verified ? verifyPath + "+layer2-principal" : "layer2-principal";
                    } else {
                        log.warn("Abort denied [layer2]: principal session binding mismatch for principal={}, swarm={}",
                                principalName, swarmId);
                        log.debug("Abort denied detail [layer2]: mappedSessionId=...{}, ownerSessionId=...{} for principal={}, swarm={}",
                                mappedSessionId != null && mappedSessionId.length() > 6 ? mappedSessionId.substring(mappedSessionId.length() - 6) : mappedSessionId,
                                ownerSessionId != null && ownerSessionId.length() > 6 ? ownerSessionId.substring(ownerSessionId.length() - 6) : ownerSessionId,
                                principalName, swarmId);
                        return ResponseEntity.status(403)
                                .body(Map.of("error", "Unauthorized: only the swarm owner can abort workers"));
                    }
                } else {
                    // 映射不存在（极端情况），仅依赖第1层验证
                    log.debug("Layer2 skip: no session mapping for principal={}, relying on layer1", principalName);
                }
            }

            // 第3层：安全兜底 — 如果 ownerSessionId 非 null 但请求体没有 sessionId 且 Principal 也是 null → 拒绝
            if (!verified) {
                log.warn("Abort denied [layer3-fallback]: no sessionId in request body and principal is null for swarm={}", swarmId);
                return ResponseEntity.status(403)
                        .body(Map.of("error", "Unauthorized: unable to verify ownership (no credentials provided)"));
            }

            log.info("Abort authorized: swarm={}, worker={}, verifyPath={}", swarmId, workerId, verifyPath);
        } else {
            // ownerSessionId 为 null 意味着 swarm 未绑定创建者（兼容旧数据），允许通过
            log.debug("Abort allowed: swarm={} has no owner session binding", swarmId);
        }

        // 3. 向 worker 发送中止信号（设置状态为 TERMINATED）
        state.markWorkerTerminated(workerId);

        // 4. 持久化异常事件到 anomaly_events 表
        String eventId = "anomaly-" + UUID.randomUUID().toString().substring(0, 8);
        anomalyEventRepository.save(
                eventId, swarmId, workerId, "worker_abort", "high",
                "Worker aborted by " + (principal != null ? principal.getName() : "unknown") + ": " + (request.reason() != null ? request.reason() : "no reason"),
                Instant.now().toEpochMilli(), null
        );

        // 5. 推送 worker_progress 更新
        // The state change will be picked up by next state push cycle

        return ResponseEntity.ok(new AbortResponse(
                workerId, "aborted",
                "Worker aborted: " + (request.reason() != null ? request.reason() : "no reason")));
    }

    /**
     * 处理权限冒泡决策（前端 → 后端）。
     * POST /api/swarm/permission/{requestId}
     * Body: { "approved": true/false }
     */
    @PostMapping("/permission/{requestId}")
    public ResponseEntity<?> resolvePermission(
            @PathVariable String requestId,
            @RequestBody Map<String, Object> body) {
        if (!featureFlags.isEnabled("ENABLE_AGENT_SWARMS")) {
            return ResponseEntity.status(403)
                    .body(Map.of("error", "Agent Swarms feature is disabled"));
        }

        boolean approved = Boolean.TRUE.equals(body.get("approved"));
        permissionBridge.resolvePermission(requestId, approved);
        return ResponseEntity.ok(Map.of("requestId", requestId, "decision", approved ? "allow" : "deny"));
    }
}
