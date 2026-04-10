package com.aicodeassistant.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型性能层级管理 — 冷却/降级/恢复策略。
 * <p>
 * 模型无关设计 — 不绑定任何特定 Provider：
 * - 千问: qwen-max → qwen-plus → qwen-turbo
 * - Anthropic: claude-opus → claude-sonnet → claude-haiku
 * - OpenAI: gpt-4o → gpt-4o-mini
 * <p>
 * 替代原版 FastMode（Anthropic 专有模型变体概念），
 * 抽象为通用的多级降级梯度。
 *
 * @see ApiRetryService
 * @see <a href="SPEC §3.1.4a">FastMode 机制</a>
 */
@Service
public class ModelTierService {

    private static final Logger log = LoggerFactory.getLogger(ModelTierService.class);

    // ==================== 冷却常量 ====================
    /** 默认冷却时长 */
    private static final Duration DEFAULT_COOLDOWN = Duration.ofMinutes(30);
    /** 短期重试阈值 — 低于此值不触发冷却 */
    private static final Duration SHORT_RETRY_THRESHOLD = Duration.ofSeconds(20);
    /** 最短冷却时长 */
    private static final Duration MIN_COOLDOWN = Duration.ofMinutes(10);
    /** 健康探测间隔 */
    private static final Duration HEALTH_PROBE_INTERVAL = Duration.ofMinutes(5);
    /** 连续成功次数恢复阈值 */
    private static final int RECOVERY_SUCCESS_THRESHOLD = 3;

    // ==================== 状态 ====================
    /** 模型 → 冷却状态 */
    private final Map<String, CooldownState> cooldownMap = new ConcurrentHashMap<>();

    /**
     * 冷却状态记录。
     */
    private record CooldownState(
            Instant cooldownUntil,
            String reason,
            int consecutiveSuccesses  // 恢复探测的连续成功数
    ) {}

    // ==================== 核心 API ====================

    /**
     * 获取当前应使用的模型 — 考虑冷却状态选择最佳可用层级。
     *
     * @param preferredModel 用户首选模型
     * @param tierChain      降级链（有序，index 0 = 最优）
     * @return 当前最佳可用模型
     */
    public String resolveModel(String preferredModel, List<String> tierChain) {
        if (tierChain == null || tierChain.isEmpty()) return preferredModel;

        // 首选模型不在冷却期 → 直接返回
        if (!isInCooldown(preferredModel)) return preferredModel;

        // 遍历降级链找第一个不在冷却期的
        for (String tier : tierChain) {
            if (!isInCooldown(tier)) {
                log.info("Model tier resolved: {} → {} (cooldown)", preferredModel, tier);
                return tier;
            }
        }

        // 所有模型都在冷却期 → 返回降级链最后一个（通常是最便宜的）
        String lastResort = tierChain.get(tierChain.size() - 1);
        log.warn("All models in cooldown, using last resort: {}", lastResort);
        return lastResort;
    }

    /**
     * 触发冷却 — API 返回过载/容量错误时调用。
     *
     * @param model       触发错误的模型
     * @param retryAfterMs 服务端返回的 retry-after 毫秒数（0 表示无）
     * @param reason      冷却原因
     */
    public void triggerCooldown(String model, long retryAfterMs, String reason) {
        // 短期重试不触发冷却
        if (retryAfterMs > 0 && retryAfterMs < SHORT_RETRY_THRESHOLD.toMillis()) {
            log.debug("Short retry-after ({}ms), skipping cooldown for {}", retryAfterMs, model);
            return;
        }

        long cooldownMs = retryAfterMs > 0
                ? Math.max(retryAfterMs, MIN_COOLDOWN.toMillis())
                : DEFAULT_COOLDOWN.toMillis();

        cooldownMap.put(model, new CooldownState(
                Instant.now().plusMillis(cooldownMs), reason, 0));
        log.info("Model cooldown: {} for {}ms, reason: {}", model, cooldownMs, reason);
    }

    /**
     * 报告成功调用 — 用于健康探测恢复。
     */
    public void reportSuccess(String model) {
        CooldownState state = cooldownMap.get(model);
        if (state == null) return;

        // 冷却期已过，开始计数恢复
        if (Instant.now().isAfter(state.cooldownUntil())) {
            int newCount = state.consecutiveSuccesses() + 1;
            if (newCount >= RECOVERY_SUCCESS_THRESHOLD) {
                cooldownMap.remove(model);
                log.info("Model recovered: {} (after {} successes)", model, newCount);
            } else {
                cooldownMap.put(model, new CooldownState(
                        state.cooldownUntil(), state.reason(), newCount));
            }
        }
    }

    /**
     * 检查模型是否在冷却期。
     */
    public boolean isInCooldown(String model) {
        CooldownState state = cooldownMap.get(model);
        if (state == null) return false;
        if (Instant.now().isAfter(state.cooldownUntil())) {
            // 冷却期结束但尚未恢复 — 允许探测
            return false;
        }
        return true;
    }

    /**
     * 获取冷却信息（用于 UI 展示）。
     */
    public Map<String, Object> getCooldownInfo(String model) {
        CooldownState state = cooldownMap.get(model);
        if (state == null) return Map.of("status", "available");
        boolean cooling = Instant.now().isBefore(state.cooldownUntil());
        return Map.of(
                "status", cooling ? "cooldown" : "recovering",
                "until", state.cooldownUntil().toString(),
                "reason", state.reason(),
                "recoveryProgress", state.consecutiveSuccesses() + "/" + RECOVERY_SUCCESS_THRESHOLD
        );
    }
}
