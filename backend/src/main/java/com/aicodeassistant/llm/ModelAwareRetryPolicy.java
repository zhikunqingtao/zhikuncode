package com.aicodeassistant.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 模型感知重试策略 — 根据不同 LLM Provider 特性调整重试参数。
 * <p>
 * 每个模型有不同的速率限制和重试特性：
 * <ul>
 *   <li>Claude: 保守重试，尊重 retry-after header</li>
 *   <li>Qwen: 速率限制宽松，可更激进重试</li>
 *   <li>DeepSeek: 中等策略，尊重 retry-after</li>
 * </ul>
 * <p>
 * 【新增】Phase 2 错误恢复框架组件。
 */
@Component
public class ModelAwareRetryPolicy {

    private static final Logger log = LoggerFactory.getLogger(ModelAwareRetryPolicy.class);

    // ==================== 重试配置 ====================

    /**
     * 模型特定重试配置。
     *
     * @param maxRetries       最大重试次数
     * @param baseDelay        基础延迟
     * @param respectRetryAfter 是否尊重服务端 retry-after header
     */
    public record RetryConfig(
            int maxRetries,
            Duration baseDelay,
            boolean respectRetryAfter
    ) {}

    /** 默认配置（未知模型） */
    private static final RetryConfig DEFAULT_CONFIG = new RetryConfig(
            10, Duration.ofMillis(500), true);

    /** 模型特定重试配置映射 */
    private static final Map<String, RetryConfig> MODEL_CONFIGS = Map.of(
            "claude", new RetryConfig(5, Duration.ofSeconds(60), true),
            "qwen", new RetryConfig(8, Duration.ofSeconds(10), false),
            "deepseek", new RetryConfig(6, Duration.ofSeconds(20), true)
    );

    /** 最大延迟上限（D3 确认：30s） */
    private static final long MAX_DELAY_MS = 30_000;

    /** Jitter 比例（D3 确认：25%） */
    private static final double JITTER_FACTOR = 0.25;

    // ==================== 核心 API ====================

    /**
     * 获取模型的重试配置 — 模糊匹配模型 ID 前缀。
     *
     * @param modelId 模型标识（如 "claude-sonnet-4-6", "qwen3.7-max", "deepseek-v4-pro"）
     * @return 匹配的重试配置
     */
    public RetryConfig getRetryConfig(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return DEFAULT_CONFIG;
        }

        String modelLower = modelId.toLowerCase();
        for (Map.Entry<String, RetryConfig> entry : MODEL_CONFIGS.entrySet()) {
            if (modelLower.startsWith(entry.getKey()) || modelLower.contains(entry.getKey())) {
                return entry.getValue();
            }
        }

        log.debug("No specific retry config for model '{}', using default", modelId);
        return DEFAULT_CONFIG;
    }

    /**
     * 计算下一次重试延迟 — 指数退避 + 25% jitter。
     * <p>
     * 公式：delay = min(baseDelay × 2^attempt, 30s) + jitter(25%)
     *
     * @param modelId 模型标识
     * @param attempt 当前尝试次数（从 0 开始）
     * @return 计算的延迟时长
     */
    public Duration calculateDelay(String modelId, int attempt) {
        RetryConfig config = getRetryConfig(modelId);
        long baseMs = config.baseDelay().toMillis();

        // 指数退避
        long delay = (long) (baseMs * Math.pow(2, attempt));
        delay = Math.min(delay, MAX_DELAY_MS);

        // 25% jitter
        long jitter = (long) (delay * JITTER_FACTOR * ThreadLocalRandom.current().nextDouble());
        long totalDelay = delay + jitter;

        log.debug("Retry delay for model '{}' attempt {}: {}ms (base={}ms, jitter={}ms)",
                modelId, attempt, totalDelay, delay, jitter);

        return Duration.ofMillis(totalDelay);
    }

    /**
     * 判断是否应使用服务端 retry-after。
     *
     * @param modelId     模型标识
     * @param retryAfterMs 服务端返回的 retry-after 毫秒数
     * @return true 表示应使用服务端指定的延迟
     */
    public boolean shouldRespectRetryAfter(String modelId, long retryAfterMs) {
        if (retryAfterMs <= 0) return false;
        RetryConfig config = getRetryConfig(modelId);
        return config.respectRetryAfter();
    }
}
