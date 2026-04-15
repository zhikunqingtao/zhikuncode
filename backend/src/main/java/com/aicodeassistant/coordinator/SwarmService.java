package com.aicodeassistant.coordinator;

import com.aicodeassistant.config.FeatureFlagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * INC-2 fix: Swarm 服务 — Agent Swarms 多代理协作。
 * <p>
 * 所有公开方法入口均调用 {@link #ensureSwarmEnabled()} 进行 feature flag 门控检查。
 * 当 ENABLE_AGENT_SWARMS=false 时，所有方法抛出 {@link IllegalStateException}。
 * <p>
 * 注意: Swarm 功能的具体实现待后续迭代补全，
 * 本文件提供标准门控模式，确保功能未就绪时无法被调用。
 */
@Service
public class SwarmService {

    private static final Logger log = LoggerFactory.getLogger(SwarmService.class);

    private final FeatureFlagService featureFlags;

    public SwarmService(FeatureFlagService featureFlags) {
        this.featureFlags = featureFlags;
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
     * 创建 Swarm — 待实现。
     * 门控: ENABLE_AGENT_SWARMS 必须为 true。
     */
    public Object createSwarm(Object request) {
        ensureSwarmEnabled();
        log.info("Creating swarm (placeholder implementation)");
        // TODO: Swarm 创建逻辑（待后续迭代实现）
        throw new UnsupportedOperationException("Swarm creation not yet implemented");
    }

    /**
     * 执行 Swarm — 待实现。
     * 门控: ENABLE_AGENT_SWARMS 必须为 true。
     */
    public Object executeSwarm(String swarmId) {
        ensureSwarmEnabled();
        log.info("Executing swarm: {} (placeholder implementation)", swarmId);
        // TODO: Swarm 执行逻辑（待后续迭代实现）
        throw new UnsupportedOperationException("Swarm execution not yet implemented");
    }
}
