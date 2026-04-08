package com.aicodeassistant.history;

import com.aicodeassistant.service.FileSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文件历史追踪 — 记录文件读取时间，检测外部修改，保存编辑前快照。
 * 对齐原版 fileHistory.ts
 */
@Service
public class FileHistoryService {

    private static final Logger log = LoggerFactory.getLogger(FileHistoryService.class);

    /** 文件路径 → 上次读取时间 */
    private final Map<String, Instant> readTimestamps = new ConcurrentHashMap<>();
    private final FileSnapshotRepository snapshotRepository;

    public FileHistoryService(FileSnapshotRepository snapshotRepository) {
        this.snapshotRepository = snapshotRepository;
    }

    public void trackFileRead(String absolutePath) {
        readTimestamps.put(absolutePath, Instant.now());
    }

    /**
     * 获取自上次读取后被外部修改的文件。
     */
    public List<String> getChangedFilesSince() {
        List<String> changed = new ArrayList<>();
        for (var entry : readTimestamps.entrySet()) {
            try {
                Path path = Path.of(entry.getKey());
                if (Files.exists(path)) {
                    FileTime lastModified = Files.getLastModifiedTime(path);
                    if (lastModified.toInstant().isAfter(entry.getValue())) {
                        changed.add(entry.getKey());
                    }
                }
            } catch (IOException e) {
                log.debug("Cannot check file time: {}", entry.getKey());
            }
        }
        return changed;
    }

    /**
     * 检查单个文件是否在上次读取后被修改。
     */
    public boolean isModifiedSinceLastRead(String absolutePath) {
        Instant lastRead = readTimestamps.get(absolutePath);
        if (lastRead == null) return false;
        try {
            Path path = Path.of(absolutePath);
            if (Files.exists(path)) {
                FileTime lastModified = Files.getLastModifiedTime(path);
                return lastModified.toInstant().isAfter(lastRead);
            }
        } catch (IOException e) {
            log.debug("Cannot check file time: {}", absolutePath);
        }
        return false;
    }

    public void clear() {
        readTimestamps.clear();
    }

    public int getTrackedFileCount() {
        return readTimestamps.size();
    }

    /**
     * 编辑前保存文件快照 — 在 FileWrite/FileEdit 执行前调用。
     * 仅在文件已存在时保存（新建文件无需快照）。
     */
    public void trackEdit(String filePath, String sessionId, String messageId) {
        try {
            Path path = Path.of(filePath);
            if (Files.exists(path) && Files.isRegularFile(path)) {
                // 文件过大不快照（>10MB）
                if (Files.size(path) > 10 * 1024 * 1024) {
                    log.debug("Skipping snapshot for large file (>10MB): {}", filePath);
                    return;
                }

                String content = Files.readString(path, java.nio.charset.StandardCharsets.UTF_8);
                FileSnapshotRepository.FileSnapshot snapshot =
                    new FileSnapshotRepository.FileSnapshot(
                        java.util.UUID.randomUUID().toString(),
                        sessionId,
                        messageId,
                        filePath,
                        content,
                        Instant.now().toString()
                    );
                snapshotRepository.save(snapshot);
                log.debug("Saved file snapshot: session={}, file={}, size={}",
                        sessionId, filePath, content.length());
            }
        } catch (IOException e) {
            log.warn("Failed to save file snapshot for {}: {}", filePath, e.getMessage());
        }

        // 同时更新读取时间追踪
        readTimestamps.put(filePath, Instant.now());
    }
}
