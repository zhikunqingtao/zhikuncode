package com.aicodeassistant.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * API Key 轮换管理器 — 支持多 Key 轮换和限流冷却。
 * <p>
 * 功能:
 * <ul>
 *   <li>Round-robin 轮换多个 API Key</li>
 *   <li>429 限流检测 → 标记冷却期自动跳过</li>
 *   <li>所有 Key 冷却时返回最先解冻的 Key</li>
 * </ul>
 *
 * @see <a href="SPEC §3.1.1b">多 API Key 轮换机制</a>
 */
@Component
public class ApiKeyRotationManager {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyRotationManager.class);

    /** 默认冷却时间 (60 秒) */
    private static final Duration DEFAULT_COOLDOWN = Duration.ofSeconds(60);

    private final List<String> apiKeys;
    private final AtomicInteger currentIndex = new AtomicInteger(0);
    private final ConcurrentHashMap<String, Instant> cooldownUntil = new ConcurrentHashMap<>();

    /**
     * 构造器 — 从配置读取 API Key 列表。
     * <p>
     * 支持单 Key 和多 Key:
     * - 单 Key: `llm.openai.api-key=sk-xxx`
     * - 多 Key: `llm.api-keys=sk-xxx1,sk-xxx2,sk-xxx3`
     * 如果 api-keys 未配置，回退到单个 api-key。
     */
    public ApiKeyRotationManager(
            @Value("${llm.api-keys:}") List<String> apiKeys,
            @Value("${llm.openai.api-key:}") String singleApiKey) {
        // 过滤空值
        List<String> keys = apiKeys.stream()
                .filter(k -> k != null && !k.isBlank())
                .toList();
        if (keys.isEmpty() && singleApiKey != null && !singleApiKey.isBlank()) {
            keys = List.of(singleApiKey);
        }
        this.apiKeys = keys;
        log.info("ApiKeyRotationManager initialized with {} key(s)", this.apiKeys.size());
    }

    /**
     * 获取下一个可用的 API Key (Round-robin + 冷却检查)。
     *
     * @return 可用的 API Key
     * @throws IllegalStateException 如果没有配置任何 Key
     */
    public String getNextKey() {
        if (apiKeys.isEmpty()) {
            throw new IllegalStateException("No API keys configured");
        }
        if (apiKeys.size() == 1) {
            return apiKeys.getFirst();
        }

        Instant now = Instant.now();
        int size = apiKeys.size();

        // 尝试所有 key，跳过冷却中的
        for (int attempt = 0; attempt < size; attempt++) {
            int idx = currentIndex.getAndUpdate(i -> (i + 1) % size);
            String key = apiKeys.get(idx);

            Instant cooldown = cooldownUntil.get(key);
            if (cooldown == null || now.isAfter(cooldown)) {
                return key;
            }
        }

        // 所有 key 都在冷却 → 返回最先解冻的
        log.warn("All API keys are in cooldown, returning earliest available");
        return apiKeys.stream()
                .min((a, b) -> {
                    Instant ca = cooldownUntil.getOrDefault(a, Instant.MIN);
                    Instant cb = cooldownUntil.getOrDefault(b, Instant.MIN);
                    return ca.compareTo(cb);
                })
                .orElse(apiKeys.getFirst());
    }

    /**
     * 获取当前 Key (不轮换索引)。
     */
    public String getCurrentKey() {
        if (apiKeys.isEmpty()) {
            throw new IllegalStateException("No API keys configured");
        }
        int idx = currentIndex.get() % apiKeys.size();
        return apiKeys.get(idx);
    }

    /**
     * 标记 Key 被限流 — 设置冷却期。
     *
     * @param key      被限流的 Key
     * @param cooldown 冷却时长
     */
    public void markRateLimited(String key, Duration cooldown) {
        Duration effectiveCooldown = cooldown != null ? cooldown : DEFAULT_COOLDOWN;
        Instant until = Instant.now().plus(effectiveCooldown);
        cooldownUntil.put(key, until);
        log.warn("API key rate-limited, cooldown until {}: key={}...{}",
                until, key.substring(0, Math.min(8, key.length())), "***");
    }

    /**
     * 标记 Key 被限流 — 使用默认冷却期。
     */
    public void markRateLimited(String key) {
        markRateLimited(key, DEFAULT_COOLDOWN);
    }

    /**
     * 清除指定 Key 的冷却状态。
     */
    public void clearCooldown(String key) {
        cooldownUntil.remove(key);
    }

    /**
     * 获取配置的 Key 数量。
     */
    public int getKeyCount() {
        return apiKeys.size();
    }

    /**
     * 检查是否有可用 Key (不在冷却中)。
     */
    public boolean hasAvailableKey() {
        if (apiKeys.isEmpty()) return false;
        Instant now = Instant.now();
        return apiKeys.stream().anyMatch(key -> {
            Instant cooldown = cooldownUntil.get(key);
            return cooldown == null || now.isAfter(cooldown);
        });
    }
}
