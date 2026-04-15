package com.aicodeassistant.coordinator;

import com.aicodeassistant.config.FeatureFlagService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * INC-2 fix: Swarm API 端点 — Agent Swarms 多代理协作。
 * <p>
 * 端点入口添加 FeatureFlagService 前置检查，
 * ENABLE_AGENT_SWARMS=false 时返回 403。
 */
@RestController
@RequestMapping("/api/swarm")
public class SwarmController {

    private final SwarmService swarmService;
    private final FeatureFlagService featureFlags;

    public SwarmController(SwarmService swarmService, FeatureFlagService featureFlags) {
        this.swarmService = swarmService;
        this.featureFlags = featureFlags;
    }

    @PostMapping
    public ResponseEntity<?> createSwarm(@RequestBody Map<String, Object> request) {
        if (!featureFlags.isEnabled("ENABLE_AGENT_SWARMS")) {
            return ResponseEntity.status(403)
                    .body(Map.of("error", "Agent Swarms feature is disabled"));
        }
        return ResponseEntity.ok(swarmService.createSwarm(request));
    }

    @PostMapping("/{swarmId}/execute")
    public ResponseEntity<?> executeSwarm(@PathVariable String swarmId) {
        if (!featureFlags.isEnabled("ENABLE_AGENT_SWARMS")) {
            return ResponseEntity.status(403)
                    .body(Map.of("error", "Agent Swarms feature is disabled"));
        }
        return ResponseEntity.ok(swarmService.executeSwarm(swarmId));
    }
}
