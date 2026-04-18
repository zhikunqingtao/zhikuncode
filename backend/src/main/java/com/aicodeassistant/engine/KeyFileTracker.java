package com.aicodeassistant.engine;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * KeyFileTracker — 追踪对话中频繁引用的文件。
 * <p>
 * 基于 Caffeine 缓存 + AtomicInteger 计数，按 session 隔离。
 * 压缩后重注入时，按引用频率排序获取 Top-N 关键文件。
 * <p>
 * 工具埋点位置：FileReadTool、FileEditTool、GrepTool
 */
@Service
public class KeyFileTracker {

    private static final Logger log = LoggerFactory.getLogger(KeyFileTracker.class);

    // Caffeine 缓存：sessionId → Map<filePath, referenceCount>
    // session 过期 2 小时自动清除，最多跟踪 200 个 session
    private final Cache<String, ConcurrentHashMap<String, AtomicInteger>> sessionFileRefs =
            Caffeine.newBuilder()
                    .maximumSize(200)
                    .expireAfterAccess(Duration.ofHours(2))
                    .build();

    // 去重集合：防止同一轮对话中对同一文件重复计数
    private final Cache<String, Set<String>> turnDedup =
            Caffeine.newBuilder()
                    .maximumSize(1000)
                    .expireAfterWrite(Duration.ofMinutes(30))
                    .build();

    /**
     * 记录文件引用（在 FileReadTool、FileEditTool、GrepTool 执行时调用）。
     * 同一轮对话中对同一文件只计数一次。
     *
     * @param sessionId 会话 ID
     * @param filePath  文件绝对路径
     * @param turnId    当前轮次 ID（用于去重，实际使用 ToolUseContext.toolUseId()）
     */
    public void trackFileReference(String sessionId, String filePath, String turnId) {
        if (sessionId == null || filePath == null) return;

        // 去重检查：(sessionId + turnId, filePath) 组合
        String dedupKey = sessionId + ":" + (turnId != null ? turnId : "unknown");
        Set<String> trackedPaths = turnDedup.get(dedupKey,
                k -> ConcurrentHashMap.newKeySet());
        if (!trackedPaths.add(filePath)) {
            return; // 本轮已计数，跳过
        }

        sessionFileRefs.get(sessionId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(filePath, k -> new AtomicInteger(0))
                .incrementAndGet();

        log.debug("KeyFileTracker: recorded access sessionId={}, file={}", sessionId, filePath);
    }

    /**
     * 获取 Top-N 关键文件（按引用次数降序）。
     *
     * @param sessionId 会话 ID
     * @param maxCount  最大返回文件数
     * @return 按引用频率降序排列的文件路径列表
     */
    public List<String> getKeyFiles(String sessionId, int maxCount) {
        var refs = sessionFileRefs.getIfPresent(sessionId);
        if (refs == null || refs.isEmpty()) return List.of();
        return refs.entrySet().stream()
                .sorted(Map.Entry.<String, AtomicInteger>comparingByValue(
                        Comparator.comparingInt(AtomicInteger::get)).reversed())
                .limit(maxCount)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * session 结束时主动清除追踪数据。
     *
     * @param sessionId 会话 ID
     */
    public void clearSession(String sessionId) {
        sessionFileRefs.invalidate(sessionId);
        log.debug("KeyFileTracker: cleared session {}", sessionId);
    }
}
