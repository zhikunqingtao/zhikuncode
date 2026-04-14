package com.aicodeassistant.history;

import com.aicodeassistant.service.FileSnapshotRepository;
import com.aicodeassistant.service.FileSnapshotRepository.FileSnapshot;
import com.aicodeassistant.service.FileStateCache;
import com.aicodeassistant.session.SessionManager;
import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 文件历史追踪 — 记录文件读取时间，检测外部修改，保存编辑前快照，支持文件回退。
 * 对齐原版 fileHistory.ts
 */
@Service
public class FileHistoryService {

    private static final Logger log = LoggerFactory.getLogger(FileHistoryService.class);

    /** 文件路径 → 上次读取时间 */
    private final Map<String, Instant> readTimestamps = new ConcurrentHashMap<>();
    private final FileSnapshotRepository snapshotRepository;
    private final SessionManager sessionManager;

    // ==================== 事务管理 (F3) ====================

    /** 活跃事务: sessionId → 当前事务上下文 */
    private final ConcurrentHashMap<String, ActiveTransaction> activeTransactions = new ConcurrentHashMap<>();
    /** 已提交事务历史: sessionId → 最近事务列表 */
    private final ConcurrentHashMap<String, List<TransactionRecord>> committedTransactions = new ConcurrentHashMap<>();

    private record ActiveTransaction(String sessionId, String messageId,
                                     Instant startTime, int startSeqNum,
                                     List<String> changedFiles) {
        ActiveTransaction(String sessionId, String messageId, int startSeqNum) {
            this(sessionId, messageId, Instant.now(), startSeqNum,
                    Collections.synchronizedList(new ArrayList<>()));
        }
    }

    public FileHistoryService(FileSnapshotRepository snapshotRepository,
                              SessionManager sessionManager) {
        this.snapshotRepository = snapshotRepository;
        this.sessionManager = sessionManager;
    }

    /**
     * 开始事务 — 在 QueryEngine.queryLoop() 每轮 LLM 回复开始前调用。
     * 记录事务起点，后续 trackEdit() 调用自动关联到此事务。
     */
    public void beginTransaction(String sessionId, String messageId, int currentSeqNum) {
        activeTransactions.put(sessionId,
                new ActiveTransaction(sessionId, messageId, currentSeqNum));
        log.debug("Transaction started: session={}, messageId={}, seqNum={}",
                sessionId, messageId, currentSeqNum);
    }

    /**
     * 提交事务 — 在 QueryEngine.queryLoop() 工具执行完成后调用。
     * 将活跃事务转为已提交记录，保存所有变更文件路径。
     */
    public void commitTransaction(String sessionId) {
        ActiveTransaction active = activeTransactions.remove(sessionId);
        if (active == null) {
            log.debug("No active transaction to commit for session={}", sessionId);
            return;
        }

        if (!active.changedFiles().isEmpty()) {
            TransactionRecord record = new TransactionRecord(
                    active.messageId(),
                    List.copyOf(active.changedFiles()),
                    active.startTime(),
                    active.startSeqNum());

            committedTransactions.computeIfAbsent(sessionId,
                    k -> Collections.synchronizedList(new ArrayList<>()))
                    .add(record);
            log.info("Transaction committed: session={}, messageId={}, changedFiles={}",
                    sessionId, active.messageId(), active.changedFiles().size());
        } else {
            log.debug("Transaction committed (no file changes): session={}, messageId={}",
                    sessionId, active.messageId());
        }
    }

    /**
     * 获取最近一条有文件变更的事务记录。
     *
     * @return 最近事务，或 empty
     */
    public Optional<TransactionRecord> getLastTransaction(String sessionId) {
        List<TransactionRecord> records = committedTransactions.get(sessionId);
        if (records == null || records.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(records.getLast());
    }

    /**
     * 移除最近一条事务记录（undo 执行后调用）。
     */
    public void removeLastTransaction(String sessionId) {
        List<TransactionRecord> records = committedTransactions.get(sessionId);
        if (records != null && !records.isEmpty()) {
            records.removeLast();
        }
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
     *
     * @param filePath  文件路径
     * @param sessionId 会话 ID
     * @param messageId 消息 ID
     * @param operation 操作类型（如 "write"、"edit"、"rewind"）
     */
    public void trackEdit(String filePath, String sessionId, String messageId, String operation) {
        try {
            Path path = Path.of(filePath);
            if (Files.exists(path) && Files.isRegularFile(path)) {
                // 文件过大不快照（>10MB）
                if (Files.size(path) > 10 * 1024 * 1024) {
                    log.debug("Skipping snapshot for large file (>10MB): {}", filePath);
                    return;
                }

                String content = Files.readString(path, StandardCharsets.UTF_8);
                FileSnapshot snapshot = new FileSnapshot(
                        UUID.randomUUID().toString(),
                        sessionId,
                        messageId,
                        filePath,
                        content,
                        operation,
                        Instant.now().toString()
                );
                snapshotRepository.save(snapshot);
                log.debug("Saved file snapshot: session={}, file={}, size={}",
                        sessionId, filePath, content.length());

                // F3: 自动关联到活跃事务
                ActiveTransaction active = activeTransactions.get(sessionId);
                if (active != null && !active.changedFiles().contains(filePath)) {
                    active.changedFiles().add(filePath);
                }
            }
        } catch (IOException e) {
            log.warn("Failed to save file snapshot for {}: {}", filePath, e.getMessage());
        }

        // 同时更新读取时间追踪
        readTimestamps.put(filePath, Instant.now());
    }

    // ==================== Rewind 回退功能 ====================

    /**
     * 按会话列出快照 — 按 messageId 分组聚合。
     *
     * @return Map<messageId, List<SnapshotInfo>> 每条消息关联的快照文件列表
     */
    public Map<String, List<SnapshotInfo>> listSnapshotsBySession(String sessionId) {
        List<FileSnapshot> snapshots = snapshotRepository.findBySessionId(sessionId);
        return snapshots.stream()
                .collect(Collectors.groupingBy(
                        FileSnapshot::messageId,
                        LinkedHashMap::new,
                        Collectors.mapping(
                                s -> new SnapshotInfo(s.filePath(), s.createdAt()),
                                Collectors.toList()
                        )
                ));
    }

    /**
     * 回退文件到指定消息快照点。
     * <p>
     * 安全措施：恢复前对当前文件做一次 trackEdit() 二次快照，确保恢复操作本身可再次回退。
     *
     * @param sessionId  会话 ID
     * @param messageId  目标消息 ID（回退到此消息时的文件状态）
     * @param filePaths  要回退的文件路径列表（null 或空表示全部回退）
     * @return 回退结果
     */
    public RewindResult rewindFiles(String sessionId, String messageId, List<String> filePaths) {
        List<FileSnapshot> snapshots = snapshotRepository.findByMessageId(messageId);
        if (snapshots.isEmpty()) {
            return new RewindResult(false, List.of(), List.of(),
                    List.of("No snapshots found for messageId: " + messageId));
        }

        // 过滤指定文件（null/empty 表示全部）
        if (filePaths != null && !filePaths.isEmpty()) {
            Set<String> filterSet = new HashSet<>(filePaths);
            snapshots = snapshots.stream()
                    .filter(s -> filterSet.contains(s.filePath()))
                    .toList();
        }

        List<String> restoredFiles = new ArrayList<>();
        List<String> skippedFiles = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        FileStateCache fileStateCache = sessionManager.getFileStateCache(sessionId);
        // 用唯一 rewind messageId 标记二次快照
        String rewindMessageId = "rewind_" + UUID.randomUUID().toString().substring(0, 8);

        for (FileSnapshot snapshot : snapshots) {
            try {
                Path targetPath = Path.of(snapshot.filePath());

                // 安全措施：恢复前对当前文件做二次快照
                if (Files.exists(targetPath) && Files.isRegularFile(targetPath)) {
                    trackEdit(snapshot.filePath(), sessionId, rewindMessageId, "rewind");
                }

                // 原子写入：临时文件 + move
                Path tmpFile = targetPath.resolveSibling(targetPath.getFileName() + ".rewind.tmp");
                Files.createDirectories(targetPath.getParent());
                Files.writeString(tmpFile, snapshot.content(), StandardCharsets.UTF_8);
                Files.move(tmpFile, targetPath,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);

                // 更新会话级 FileStateCache
                fileStateCache.markModified(snapshot.filePath());
                restoredFiles.add(snapshot.filePath());
                log.info("Rewind restored: session={}, file={}", sessionId, snapshot.filePath());

            } catch (IOException e) {
                errors.add(snapshot.filePath() + ": " + e.getMessage());
                log.warn("Rewind failed for {}: {}", snapshot.filePath(), e.getMessage());
            }
        }

        return new RewindResult(!errors.isEmpty() ? false : true,
                restoredFiles, skippedFiles, errors);
    }

    /**
     * 计算两个消息点之间的文件差异统计。
     */
    public DiffStats computeDiffStats(String sessionId, String fromMessageId, String toMessageId) {
        List<FileSnapshot> fromSnapshots = snapshotRepository.findByMessageId(fromMessageId);
        List<FileSnapshot> toSnapshots = snapshotRepository.findByMessageId(toMessageId);

        Map<String, String> fromMap = fromSnapshots.stream()
                .collect(Collectors.toMap(FileSnapshot::filePath, FileSnapshot::content,
                        (a, b) -> b)); // 保留最后一个
        Map<String, String> toMap = toSnapshots.stream()
                .collect(Collectors.toMap(FileSnapshot::filePath, FileSnapshot::content,
                        (a, b) -> b));

        Set<String> allFiles = new HashSet<>();
        allFiles.addAll(fromMap.keySet());
        allFiles.addAll(toMap.keySet());

        int filesAdded = 0, filesModified = 0, filesDeleted = 0;
        List<String> changedFiles = new ArrayList<>();

        for (String file : allFiles) {
            boolean inFrom = fromMap.containsKey(file);
            boolean inTo = toMap.containsKey(file);

            if (!inFrom && inTo) {
                filesAdded++;
                changedFiles.add(file);
            } else if (inFrom && !inTo) {
                filesDeleted++;
                changedFiles.add(file);
            } else if (inFrom && inTo) {
                String fromContent = fromMap.get(file);
                String toContent = toMap.get(file);
                if (!fromContent.equals(toContent)) {
                    filesModified++;
                    changedFiles.add(file);
                }
            }
        }

        return new DiffStats(filesAdded, filesModified, filesDeleted, changedFiles);
    }

    // ==================== DTO Records ====================

    public record SnapshotInfo(String filePath, String timestamp) {}

    public record RewindResult(boolean success, List<String> restoredFiles,
                               List<String> skippedFiles, List<String> errors) {}

    public record DiffStats(int filesAdded, int filesModified, int filesDeleted,
                            List<String> changedFiles) {}

    /** F3 事务记录 — 记录一轮对话中所有文件变更 */
    public record TransactionRecord(String messageId, List<String> changedFiles,
                                    Instant timestamp, int startSeqNum) {}
}
