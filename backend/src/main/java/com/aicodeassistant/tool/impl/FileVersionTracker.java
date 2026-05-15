package com.aicodeassistant.tool.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SHA-256 文件版本追踪器。
 * <p>
 * 管理文件的 hash 版本，用于写入前冲突检测。
 * 与 {@link com.aicodeassistant.service.FileStateCache} 协同工作（增强层，不替代）。
 */
@Component
public class FileVersionTracker {

    private static final Logger log = LoggerFactory.getLogger(FileVersionTracker.class);

    private static final int MAX_ENTRIES = 10_000;
    private static final double EVICTION_RATIO = 0.2;

    /** 文件版本记录 */
    public record FileVersion(String contentHash, long lastModified, String lastEditor) {}

    /** 冲突检查结果 */
    public record ConflictCheckResult(boolean hasConflict, String currentHash,
                                      String expectedHash, String lastEditor) {}

    private final ConcurrentHashMap<String, FileVersion> versions = new ConcurrentHashMap<>();

    // ─── Hash 计算 ───────────────────────────────────────────

    /** 计算字符串内容的 SHA-256 hash */
    public String computeHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be available in all JDK implementations
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /** 计算文件的 SHA-256 hash（从磁盘读取） */
    public String computeHash(Path filePath) throws IOException {
        String content = Files.readString(filePath, StandardCharsets.UTF_8);
        return computeHash(content);
    }

    // ─── 冲突检测 ────────────────────────────────────────────

    /**
     * 写入前冲突检查：双重验证。
     * <ol>
     *   <li>比较 expectedHash 与当前文件实际 hash</li>
     *   <li>比较 map 中存储的 hash 与当前文件实际 hash</li>
     * </ol>
     *
     * @param filePath     文件路径
     * @param expectedHash 调用方期望的 hash（读取时计算），可为 null
     * @return 冲突检查结果
     */
    public ConflictCheckResult checkBeforeWrite(String filePath, String expectedHash) {
        String normalized = Path.of(filePath).normalize().toString();
        FileVersion stored = versions.get(normalized);

        // 无记录且无期望 hash → 跳过检查（首次写入）
        if (stored == null && expectedHash == null) {
            return new ConflictCheckResult(false, null, null, null);
        }

        // 计算文件当前 hash
        String currentHash;
        try {
            Path path = Path.of(filePath);
            if (!Files.exists(path)) {
                // 文件不存在（可能是新建），无冲突
                return new ConflictCheckResult(false, null, expectedHash, null);
            }
            currentHash = computeHash(path);
        } catch (IOException e) {
            log.warn("Cannot compute hash for conflict check: {}", filePath, e);
            return new ConflictCheckResult(false, null, expectedHash, null);
        }

        // 双重验证：优先使用 expectedHash，其次使用 stored hash
        String effectiveExpectedHash = expectedHash != null
                ? expectedHash
                : (stored != null ? stored.contentHash() : null);

        if (effectiveExpectedHash != null && !effectiveExpectedHash.equals(currentHash)) {
            log.warn("Conflict detected for {}: expected={}, current={}, lastEditor={}",
                    normalized,
                    effectiveExpectedHash.substring(0, Math.min(16, effectiveExpectedHash.length())),
                    currentHash.substring(0, Math.min(16, currentHash.length())),
                    stored != null ? stored.lastEditor() : "unknown");
            return new ConflictCheckResult(true, currentHash, effectiveExpectedHash,
                    stored != null ? stored.lastEditor() : null);
        }

        return new ConflictCheckResult(false, currentHash, effectiveExpectedHash, null);
    }

    // ─── 版本记录 ────────────────────────────────────────────

    /** 记录一次成功的写入 */
    public void recordWrite(String filePath, String newHash, String agentId) {
        String normalized = Path.of(filePath).normalize().toString();
        versions.put(normalized, new FileVersion(newHash, System.currentTimeMillis(), agentId));
        log.debug("Recorded write: {} hash={} editor={}", normalized,
                newHash.substring(0, Math.min(16, newHash.length())), agentId);
        evictIfNeeded();
    }

    /** 当文件被 Read 时记录初始 hash */
    public void recordRead(String filePath) {
        String normalized = Path.of(filePath).normalize().toString();
        try {
            Path path = Path.of(filePath);
            if (Files.exists(path)) {
                String hash = computeHash(path);
                versions.put(normalized, new FileVersion(hash, System.currentTimeMillis(), null));
                log.debug("Recorded read: {} hash={}", normalized,
                        hash.substring(0, Math.min(16, hash.length())));
            }
        } catch (IOException e) {
            log.warn("Cannot compute hash for read recording: {}", filePath, e);
        }
        evictIfNeeded();
    }

    // ─── LRU 驱逐 ─────────────────────────────────────────────

    private void evictIfNeeded() {
        if (versions.size() > MAX_ENTRIES) {
            int toRemove = (int) (MAX_ENTRIES * EVICTION_RATIO);
            versions.entrySet().stream()
                    .sorted(Comparator.comparingLong(e -> e.getValue().lastModified()))
                    .limit(toRemove)
                    .map(Map.Entry::getKey)
                    .toList()
                    .forEach(versions::remove);
            log.info("Evicted {} oldest entries, current size: {}", toRemove, versions.size());
        }
    }
}
