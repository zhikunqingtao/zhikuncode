package com.aicodeassistant.context;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * SystemPromptSectionCache — 系统提示分段缓存服务。
 * <p>
 * 将系统提示拆分为多个独立段（identity/tools/environment/memory/contextDocs/claudeMd），
 * 每段独立缓存，仅变更的段重新计算，未变更段复用缓存。
 * <p>
 * 缓存策略:
 * - 使用 Caffeine Cache with TTL (默认 30 分钟)
 * - 基于内容 hash 判断段是否变更
 * - 支持按 sessionId 隔离缓存
 * - /clear 或 /compact 时可按 session 清除缓存
 *
 * @see com.aicodeassistant.prompt.SystemPromptSection
 * @see com.aicodeassistant.prompt.SystemPromptSegment
 */
@Service
public class SystemPromptSectionCache {

    private static final Logger log = LoggerFactory.getLogger(SystemPromptSectionCache.class);

    /** 全局段缓存 (不依赖 session 的段: identity, tools) */
    private final Cache<String, CachedSection> globalCache;

    /** 会话级段缓存 (sessionId → (sectionName → CachedSection)) */
    private final Map<String, Cache<String, CachedSection>> sessionCaches = new ConcurrentHashMap<>();

    /** 缓存统计 */
    private long hitCount = 0;
    private long missCount = 0;

    public SystemPromptSectionCache() {
        this.globalCache = Caffeine.newBuilder()
                .maximumSize(50)
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .build();
    }

    // ═══════════════════════════════════════════
    // 公开 API
    // ═══════════════════════════════════════════

    /**
     * 获取或计算全局段（不依赖 session）。
     *
     * @param sectionName 段名称 (如 "identity", "tools")
     * @param contentHash 内容 hash（用于判断是否变更）
     * @param compute     计算函数
     * @return 段内容
     */
    public String getOrComputeGlobal(String sectionName, int contentHash, java.util.function.Supplier<String> compute) {
        CachedSection cached = globalCache.getIfPresent(sectionName);
        if (cached != null && cached.contentHash == contentHash) {
            hitCount++;
            return cached.content;
        }
        String content = compute.get();
        globalCache.put(sectionName, new CachedSection(content, contentHash));
        missCount++;
        return content;
    }

    /**
     * 获取或计算会话级段。
     *
     * @param sessionId   会话 ID
     * @param sectionName 段名称 (如 "memory", "env_info")
     * @param contentHash 内容 hash
     * @param compute     计算函数
     * @return 段内容
     */
    public String getOrComputeSession(String sessionId, String sectionName, int contentHash,
                                       java.util.function.Supplier<String> compute) {
        Cache<String, CachedSection> cache = sessionCaches.computeIfAbsent(sessionId, k ->
                Caffeine.newBuilder()
                        .maximumSize(20)
                        .expireAfterWrite(30, TimeUnit.MINUTES)
                        .build());

        CachedSection cached = cache.getIfPresent(sectionName);
        if (cached != null && cached.contentHash == contentHash) {
            hitCount++;
            return cached.content;
        }
        String content = compute.get();
        cache.put(sectionName, new CachedSection(content, contentHash));
        missCount++;
        return content;
    }

    /**
     * 清除指定会话的缓存（/clear 或 /compact 时调用）。
     */
    public void clearSession(String sessionId) {
        Cache<String, CachedSection> removed = sessionCaches.remove(sessionId);
        if (removed != null) {
            removed.invalidateAll();
            log.debug("Cleared system prompt cache for session: {}", sessionId);
        }
    }

    /**
     * 清除所有缓存。
     */
    public void clearAll() {
        globalCache.invalidateAll();
        sessionCaches.values().forEach(Cache::invalidateAll);
        sessionCaches.clear();
        hitCount = 0;
        missCount = 0;
        log.info("All system prompt section caches cleared");
    }

    /**
     * 获取缓存命中率。
     */
    public double getHitRate() {
        long total = hitCount + missCount;
        return total == 0 ? 0.0 : (double) hitCount / total;
    }

    /**
     * 获取缓存统计信息。
     */
    public Map<String, Object> getStats() {
        return Map.of(
                "hitCount", hitCount,
                "missCount", missCount,
                "hitRate", String.format("%.2f%%", getHitRate() * 100),
                "globalSize", globalCache.estimatedSize(),
                "sessionCount", sessionCaches.size()
        );
    }

    // ═══════════════════════════════════════════
    // 内部
    // ═══════════════════════════════════════════

    private record CachedSection(String content, int contentHash) {}
}
