package com.aicodeassistant.coordinator;

import com.aicodeassistant.config.FeatureFlagService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.*;

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

    public SwarmController(SwarmService swarmService,
                            FeatureFlagService featureFlags,
                            LeaderPermissionBridge permissionBridge) {
        this.swarmService = swarmService;
        this.featureFlags = featureFlags;
        this.permissionBridge = permissionBridge;
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
        int maxWorkers = request.containsKey("maxWorkers")
                ? ((Number) request.get("maxWorkers")).intValue()
                : SwarmConfig.DEFAULT_MAX_WORKERS;
        String sessionId = (String) request.getOrDefault("sessionId", "default");

        Path scratchpadDir = Path.of(System.getProperty("user.dir"), ".claude", "scratchpad", teamName);
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
