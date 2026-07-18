package com.aicodeassistant.tool.impl;

import com.aicodeassistant.security.PathSecurityService;
import com.aicodeassistant.security.ManagedWorkspacePathResolver;
import com.aicodeassistant.security.ManagedPathLockManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 原子文件写入器。
 * <p>
 * 确保写入的原子性和可恢复性：
 * <ol>
 *   <li>验证 expected old state</li>
 *   <li>在目标目录写入并 fsync 随机临时文件</li>
 *   <li>仅使用 {@code ATOMIC_MOVE} 替换目标文件</li>
 *   <li>重读并校验 SHA-256</li>
 *   <li>成功后 best-effort 通知 {@link FileVersionTracker} 记录版本</li>
 * </ol>
 */
@Component
public class AtomicFileWriter {

    private static final Logger log = LoggerFactory.getLogger(AtomicFileWriter.class);
    private final FileVersionTracker fileVersionTracker;
    private final PathSecurityService pathSecurityService;
    private final ManagedWorkspacePathResolver managedPaths;
    private final ManagedPathLockManager pathLocks;

    public AtomicFileWriter(FileVersionTracker fileVersionTracker) {
        this(fileVersionTracker, null, null, new ManagedPathLockManager());
    }

    public AtomicFileWriter(FileVersionTracker fileVersionTracker, PathSecurityService pathSecurityService) {
        this(fileVersionTracker, pathSecurityService,
                pathSecurityService == null ? null : new ManagedWorkspacePathResolver(),
                new ManagedPathLockManager());
    }

    public AtomicFileWriter(FileVersionTracker fileVersionTracker, PathSecurityService pathSecurityService,
                            ManagedWorkspacePathResolver managedPaths) {
        this(fileVersionTracker, pathSecurityService, managedPaths, new ManagedPathLockManager());
    }

    @Autowired
    public AtomicFileWriter(FileVersionTracker fileVersionTracker, PathSecurityService pathSecurityService,
                            ManagedWorkspacePathResolver managedPaths,
                            ManagedPathLockManager pathLocks) {
        this.fileVersionTracker = fileVersionTracker;
        this.pathSecurityService = pathSecurityService;
        this.managedPaths = managedPaths;
        this.pathLocks = pathLocks;
    }

    /** 写入结果 */
    public record WriteResult(boolean success, String newHash, String error,
                              boolean historyRecorded, boolean directorySyncConfirmed,
                              String guaranteeLevel, WriteEffect effect) {
        public WriteResult(boolean success, String newHash, String error) {
            this(success, newHash, error, false, false, "ATOMIC",
                    success ? WriteEffect.APPLIED : WriteEffect.NOT_STARTED);
        }
    }
    public enum WriteEffect { NOT_STARTED, APPLIED, UNKNOWN }
    public sealed interface ExpectedOldState permits ExpectedOldState.Absent, ExpectedOldState.Sha256 {
        record Absent() implements ExpectedOldState {}
        record Sha256(String value) implements ExpectedOldState {
            public Sha256 {
                if (value == null || !value.matches("[0-9a-fA-F]{64}"))
                    throw new IllegalArgumentException("Expected SHA-256 must contain 64 hexadecimal characters");
                value = value.toLowerCase();
            }
        }
        static ExpectedOldState absent() { return new Absent(); }
        static ExpectedOldState sha256(String value) { return new Sha256(value); }
    }
    /**
     * 原子写入流程：expected-state → 临时文件 → ATOMIC_MOVE → 重读校验 → 记录历史。
     *
     * @param targetPath 目标文件路径
     * @param content    要写入的完整内容
     * @param agentId    执行写入的 agent/session 标识
     * @return 写入结果
     */
    /** 受管文件工具使用的“路径锁 + 旧状态预期值”写入入口。 */
    public WriteResult atomicWrite(Path targetPath, String content, String agentId,
                                   String workingDirectory, String expectedOldHash) {
        ExpectedOldState expected = expectedOldHash == null
                ? ExpectedOldState.absent() : ExpectedOldState.sha256(expectedOldHash);
        return write(targetPath, content.getBytes(StandardCharsets.UTF_8), agentId, expected, workingDirectory);
    }

    /** 受管写入入口：必须提供预期旧状态，null 绝不表示无条件覆盖。 */
    public WriteResult write(Path targetPath, byte[] content, String actorId,
                             ExpectedOldState expected, String workingDirectory) {
        if (expected == null) return new WriteResult(false, null, "EXPECTED_OLD_STATE_REQUIRED");
        Path normalized;
        try {
            normalized = pathSecurityService == null
                    ? targetPath.toAbsolutePath().normalize()
                    : managedPaths.resolveProspective(targetPath, workingDirectory);
        } catch (IOException | IllegalArgumentException unsafePath) {
            return new WriteResult(false, null, "PRE_MOVE_SECURITY_DENIED: " + unsafePath.getMessage());
        }
        try {
            return pathLocks.withLock(normalized,
                    () -> atomicWriteLocked(normalized, content, actorId, workingDirectory, expected));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception impossible) {
            return new WriteResult(false, null, "ATOMIC_WRITE_LOCK_FAILED: " + impossible.getMessage());
        }
    }

    private WriteResult atomicWriteLocked(Path targetPath, byte[] content, String agentId,
                                          String workingDirectory, ExpectedOldState expected) {
        Path tmpPath = null;
        boolean moved = false;
        java.util.List<Path> createdDirectories = java.util.List.of();

        try {
            if (pathSecurityService != null) {
                var pre = pathSecurityService.checkWritePermission(targetPath.toString(), workingDirectory);
                if (!pre.isAllowed()) return new WriteResult(false, null, "PRE_MOVE_SECURITY_DENIED: " + pre.message());
                var materialized = managedPaths.materializeParents(targetPath, workingDirectory);
                targetPath = materialized.path();
                createdDirectories = materialized.createdDirectories();
                managedPaths.assertUnchanged(targetPath, workingDirectory);
            }
            // 确保父目录存在
            if (targetPath.getParent() != null) {
                if (pathSecurityService == null) Files.createDirectories(targetPath.getParent());
                else if (!Files.isDirectory(targetPath.getParent(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    return new WriteResult(false, null, "PRE_MOVE_SECURITY_DENIED: parent directory is invalid");
            }

            boolean exists=Files.exists(targetPath,java.nio.file.LinkOption.NOFOLLOW_LINKS);
            if(expected instanceof ExpectedOldState.Absent && exists)
                return new WriteResult(false,null,"FILE_CONFLICT_EXPECTED_ABSENT");
            if(expected instanceof ExpectedOldState.Sha256 sha) {
                if(!exists)return new WriteResult(false,null,"FILE_CONFLICT_EXPECTED_EXISTING");
                String actualOldHash=sha256(targetPath);
                if(!sha.value().equals(actualOldHash))return new WriteResult(false,null,"OLD_HASH_CONFLICT");
            }

            // 在同目录临时文件中写入替换内容。用户历史只在改变权威状态的移动后记录，
            // 历史记录成功与否不决定文件写入是否成功。
            tmpPath = Files.createTempFile(targetPath.getParent(),
                    "." + targetPath.getFileName(), ".tmp");
            try(FileChannel channel=FileChannel.open(tmpPath,StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)){
                java.nio.ByteBuffer buffer=java.nio.ByteBuffer.wrap(content);
                while(buffer.hasRemaining())channel.write(buffer);
                channel.force(true);
            }

            // 在改变权威状态的移动前立即复检，捕获首次检查与 I/O 之间的父目录或符号链接替换。
            if (pathSecurityService != null) {
                managedPaths.assertUnchanged(targetPath, workingDirectory);
                var beforeMove = pathSecurityService.checkWritePermission(targetPath.toString(), workingDirectory);
                if (!beforeMove.isAllowed()) throw new IOException("PRE_MOVE_SECURITY_DENIED: " + beforeMove.message());
            }

            // 使用 ATOMIC_MOVE 替换目标；故意不提供非原子降级路径。
            Files.move(tmpPath, targetPath,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            tmpPath = null; // 移动成功，不再需要清理
            moved = true;

            if (pathSecurityService != null) {
                managedPaths.assertUnchanged(targetPath, workingDirectory);
                var afterMove = pathSecurityService.checkWritePermission(targetPath.toString(), workingDirectory);
                if (!afterMove.isAllowed()) throw new IOException("POST_MOVE_SECURITY_DENIED: " + afterMove.message());
            }

            String newHash = sha256(content);
            String actualHash=sha256(targetPath);
            if(!newHash.equals(actualHash))throw new IOException("WRITE_VERIFY_FAILED");
            boolean directorySynced = syncDirectoryBestEffort(targetPath.getParent());
            boolean historyRecorded = true;
            try {
                fileVersionTracker.recordWrite(targetPath.toString(), newHash, agentId);
            } catch (RuntimeException historyFailure) {
                historyRecorded = false;
                log.warn("File write succeeded but history recording failed: path={}, actor={}",
                        targetPath, agentId, historyFailure);
            }

            log.debug("Atomic write successful: {}", targetPath);
            return new WriteResult(true, newHash, null, historyRecorded, directorySynced,
                    "ATOMIC", WriteEffect.APPLIED);

        } catch (Exception e) {
            log.error("Atomic write failed for {}: {}", targetPath, e.getMessage(), e);

            // 清理残留临时文件
            if (tmpPath != null) {
                try {
                    Files.deleteIfExists(tmpPath);
                } catch (IOException cleanupEx) {
                    log.warn("Failed to cleanup atomic-write temp file: path={}", tmpPath, cleanupEx);
                }
            }
            if (!moved && managedPaths != null) managedPaths.cleanupEmptyDirectories(createdDirectories);

            if (moved) {
                String actualHash = null;
                WriteEffect effect = WriteEffect.UNKNOWN;
                try {
                    actualHash = sha256(targetPath);
                    if (actualHash.equals(sha256(content))) effect = WriteEffect.APPLIED;
                } catch (Exception verificationFailure) {
                    log.warn("Could not determine post-move write effect: path={}",
                            targetPath, verificationFailure);
                }
                return new WriteResult(false, actualHash,
                        "POST_MOVE_FAILURE: " + e.getMessage(), false, false,
                        "ATOMIC_MOVE_COMPLETED", effect);
            }
            return new WriteResult(false, null, "Atomic write failed: " + e.getMessage());
        }
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) >= 0;) {
                if (read > 0) digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static boolean syncDirectoryBestEffort(Path directory) {
        try(FileChannel channel=FileChannel.open(directory,StandardOpenOption.READ)){channel.force(true);return true;}
        catch(Exception unsupported){log.debug("Directory fsync unavailable for {}: {}",directory,unsupported.getMessage());return false;}
    }

}
