package com.aicodeassistant.coordinator;

import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.model.dto.AbortRequest;
import com.aicodeassistant.model.dto.AbortResponse;
import com.aicodeassistant.service.AnomalyEventRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
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

    private final SwarmService swarmService;
    private final FeatureFlagService featureFlags;
    private final LeaderPermissionBridge permissionBridge;
    private final AnomalyEventRepository anomalyEventRepository;

    /** teamName 白名单：字母/数字/下划线/中划线，长度 1-64；禁止路径分隔符与 .. 防止 scratchpad 路径穿越。 */
    private static final Pattern TEAM_NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    public SwarmController(SwarmService swarmService,
                            FeatureFlagService featureFlags,
                            LeaderPermissionBridge permissionBridge,
                            AnomalyEventRepository anomalyEventRepository) {
        this.swarmService = swarmService;
        this.featureFlags = featureFlags;
        this.permissionBridge = permissionBridge;
        this.anomalyEventRepository = anomalyEventRepository;
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
            @RequestBody AbortRequest request) {
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

        // 2. 向 worker 发送中止信号（设置状态为 TERMINATED）
        state.markWorkerTerminated(workerId);

        // 3. 持久化异常事件到 anomaly_events 表
        String eventId = "anomaly-" + UUID.randomUUID().toString().substring(0, 8);
        anomalyEventRepository.save(
                eventId, swarmId, workerId, "worker_abort", "high",
                "Worker aborted: " + (request.reason() != null ? request.reason() : "no reason"),
                Instant.now().toEpochMilli(), null
        );

        // 4. 推送 worker_progress 更新（via SwarmService internal push）
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
