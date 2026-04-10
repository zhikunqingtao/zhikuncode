package com.aicodeassistant.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文件状态缓存 — 对齐原版 FileStateCache。
 * LRU + 大小限制，防止无限内存增长。
 * 所有路径 key 自动 normalize（对齐原版 normalize(key)）。
 *
 * @see <a href="SPEC §11.5.9">FileStateCache 规格</a>
 */
public class FileStateCache {

    public record FileState(
            String content,
            long timestamp,
            Integer offset,       // null = 完整读取
            Integer limit,        // null = 无限制
            boolean isPartialView // true = 自动注入截断（CLAUDE.md 等）
    ) {}

    private static final int MAX_ENTRIES = 100;
    private static final long MAX_SIZE_BYTES = 25 * 1024 * 1024; // 25MB

    private final LinkedHashMap<String, FileState> cache;
    private long totalSizeBytes = 0;

    public FileStateCache() {
        this.cache = new LinkedHashMap<>(MAX_ENTRIES, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, FileState> eldest) {
                if (size() > MAX_ENTRIES || totalSizeBytes > MAX_SIZE_BYTES) {
                    totalSizeBytes -= eldest.getValue().content().getBytes().length;
                    return true;
                }
                return false;
            }
        };
    }

    public synchronized void markRead(String path, String content,
                                       Integer offset, Integer limit, boolean isPartial) {
        String normalized = Path.of(path).normalize().toString();
        FileState old = cache.get(normalized);
        if (old != null) totalSizeBytes -= old.content().getBytes().length;
        FileState state = new FileState(content, System.currentTimeMillis(),
                offset, limit, isPartial);
        cache.put(normalized, state);
        totalSizeBytes += content.getBytes().length;
    }

    public synchronized void markModified(String path) {
        String normalized = Path.of(path).normalize().toString();
        FileState existing = cache.get(normalized);
        if (existing != null) {
            cache.put(normalized, new FileState(
                    existing.content(), System.currentTimeMillis(),
                    existing.offset(), existing.limit(), true)); // isPartial=true 强制重读
        }
    }

    public synchronized boolean hasBeenRead(String path) {
        return cache.containsKey(Path.of(path).normalize().toString());
    }

    public synchronized boolean isStale(String path) {
        String normalized = Path.of(path).normalize().toString();
        FileState state = cache.get(normalized);
        if (state == null) return true;
        try {
            long mtime = Files.getLastModifiedTime(Path.of(path)).toMillis();
            return mtime > state.timestamp();
        } catch (IOException e) {
            return true;
        }
    }

    /** 子代理创建时复制父代理文件状态 — 对齐原版 cloneFileStateCache() */
    public synchronized FileStateCache cloneCache() {
        FileStateCache cloned = new FileStateCache();
        this.cache.forEach((k, v) -> cloned.cache.put(k, v));
        cloned.totalSizeBytes = this.totalSizeBytes;
        return cloned;
    }

    /** 子代理完成后合并回父代理 — 对齐原版 mergeFileStateCaches() */
    public synchronized void merge(FileStateCache other) {
        other.cache.forEach((path, state) -> {
            FileState existing = this.cache.get(path);
            if (existing == null || state.timestamp() > existing.timestamp()) {
                if (existing != null) totalSizeBytes -= existing.content().getBytes().length;
                this.cache.put(path, state);
                totalSizeBytes += state.content().getBytes().length;
            }
        });
    }

    /** 压缩导出 — 对齐原版 cacheToObject() */
    public synchronized Map<String, FileState> toMap() {
        return Map.copyOf(cache);
    }
}
