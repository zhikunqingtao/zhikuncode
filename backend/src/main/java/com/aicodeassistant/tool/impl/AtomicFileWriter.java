package com.aicodeassistant.tool.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * 原子文件写入器。
 * <p>
 * 确保写入的原子性和可恢复性：
 * <ol>
 *   <li>备份原文件（{@code .backup} 目录）</li>
 *   <li>写入临时文件（{@code .tmp} 后缀）</li>
 *   <li>{@code ATOMIC_MOVE} 替换目标文件</li>
 *   <li>成功后通知 {@link FileVersionTracker} 记录版本</li>
 *   <li>异常时回滚：从备份还原</li>
 *   <li>清理过期备份（LRU，保留最新 {@value #MAX_BACKUPS} 个）</li>
 * </ol>
 */
@Component
public class AtomicFileWriter {

    private static final Logger log = LoggerFactory.getLogger(AtomicFileWriter.class);
    private static final int MAX_BACKUPS = 10;

    private final FileVersionTracker fileVersionTracker;

    public AtomicFileWriter(FileVersionTracker fileVersionTracker) {
        this.fileVersionTracker = fileVersionTracker;
    }

    /** 写入结果 */
    public record WriteResult(boolean success, String newHash, String error) {}

    /**
     * 原子写入流程：备份 → 写临时文件 → ATOMIC_MOVE 替换 → 记录版本。
     *
     * @param targetPath 目标文件路径
     * @param content    要写入的完整内容
     * @param agentId    执行写入的 agent/session 标识
     * @return 写入结果
     */
    public WriteResult atomicWrite(Path targetPath, String content, String agentId) {
        Path backupPath = null;
        Path tmpPath = null;

        try {
            // 确保父目录存在
            if (targetPath.getParent() != null) {
                Files.createDirectories(targetPath.getParent());
            }

            // 1. 备份原文件（如果存在）
            if (Files.exists(targetPath)) {
                Path backupDir = targetPath.getParent().resolve(".backup");
                Files.createDirectories(backupDir);
                String backupName = targetPath.getFileName().toString()
                        + "." + Instant.now().toEpochMilli();
                backupPath = backupDir.resolve(backupName);
                Files.copy(targetPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
                log.debug("Backup created: {}", backupPath);

                // 清理过期备份（LRU 策略）
                cleanupBackups(backupDir, targetPath.getFileName().toString());
            }

            // 2. 将新内容写入临时文件（同目录下，.tmp 后缀）
            tmpPath = targetPath.getParent().resolve(
                    targetPath.getFileName().toString() + ".tmp");
            Files.writeString(tmpPath, content, StandardCharsets.UTF_8);

            // 3. ATOMIC_MOVE 替换目标文件
            Files.move(tmpPath, targetPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            tmpPath = null; // 移动成功，不再需要清理

            // 4. 记录版本到 FileVersionTracker
            String newHash = fileVersionTracker.computeHash(content);
            fileVersionTracker.recordWrite(targetPath.toString(), newHash, agentId);

            log.debug("Atomic write successful: {}", targetPath);
            return new WriteResult(true, newHash, null);

        } catch (Exception e) {
            log.error("Atomic write failed for {}: {}", targetPath, e.getMessage(), e);

            // 回滚：将备份文件还原
            if (backupPath != null && Files.exists(backupPath)) {
                try {
                    Files.move(backupPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    log.info("Rollback successful: restored {} from backup", targetPath);
                } catch (IOException rollbackEx) {
                    log.error("Rollback also failed for {}: {}",
                            targetPath, rollbackEx.getMessage());
                }
            }

            // 清理残留临时文件
            if (tmpPath != null) {
                try {
                    Files.deleteIfExists(tmpPath);
                } catch (IOException cleanupEx) {
                    log.warn("Failed to cleanup temp file: {}", tmpPath);
                }
            }

            return new WriteResult(false, null, "Atomic write failed: " + e.getMessage());
        }
    }

    /**
     * 清理过期备份文件，保留最新 {@value #MAX_BACKUPS} 个。
     */
    private void cleanupBackups(Path backupDir, String originalFileName) {
        try (DirectoryStream<Path> stream =
                     Files.newDirectoryStream(backupDir, originalFileName + ".*")) {

            List<Path> backups = StreamSupport.stream(stream.spliterator(), false)
                    .sorted(Comparator.comparingLong(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis();
                        } catch (IOException e) {
                            return 0L;
                        }
                    }))
                    .collect(Collectors.toList());

            // 删除最老的，保留最新 MAX_BACKUPS 个
            while (backups.size() > MAX_BACKUPS) {
                Path oldest = backups.remove(0);
                Files.deleteIfExists(oldest);
                log.debug("Cleaned up old backup: {}", oldest);
            }
        } catch (IOException e) {
            log.warn("Failed to cleanup backups in {}: {}", backupDir, e.getMessage());
        }
    }
}
