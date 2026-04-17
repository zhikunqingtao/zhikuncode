package com.aicodeassistant.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 会话快照服务 — 管理会话快照的持久化（保存/加载/列出/删除）。
 * <p>
 * 快照以 JSON 文件存储在 ~/.zhiku/snapshots/ 目录下，
 * 文件名为 {sessionId}.json。
 *
 * @see SessionSnapshot
 * @see SessionSnapshotSummary
 */
@Service
public class SessionSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(SessionSnapshotService.class);

    private final Path snapshotDir;
    private final ObjectMapper objectMapper;

    public SessionSnapshotService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.snapshotDir = Path.of(System.getProperty("user.home"), ".zhiku", "snapshots");
        try {
            Files.createDirectories(snapshotDir);
            log.info("Snapshot directory ready: {}", snapshotDir);
        } catch (IOException e) {
            log.warn("Failed to create snapshot directory {}: {}. Snapshot feature may be unavailable.",
                    snapshotDir, e.getMessage());
        }
    }

    private void validateSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()
                || sessionId.contains("..") || sessionId.contains("/") || sessionId.contains("\\")) {
            throw new IllegalArgumentException("Invalid sessionId: must not contain path separators or traversal sequences");
        }
    }

    /**
     * 保存会话快照到 ~/.zhiku/snapshots/{sessionId}.json。
     *
     * @param sessionId 会话 ID
     * @param snapshot  快照数据
     */
    public void saveSnapshot(String sessionId, SessionSnapshot snapshot) {
        validateSessionId(sessionId);
        Path file = snapshotDir.resolve(sessionId + ".json");
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), snapshot);
            log.info("Session snapshot saved: {} (messages={}, model={})",
                    sessionId, snapshot.messages().size(), snapshot.model());
        } catch (IOException e) {
            log.error("Failed to save snapshot for session {}: {}", sessionId, e.getMessage(), e);
        }
    }

    /**
     * 加载会话快照。
     *
     * @param sessionId 会话 ID
     * @return 快照数据（如果存在）
     */
    public Optional<SessionSnapshot> loadSnapshot(String sessionId) {
        validateSessionId(sessionId);
        Path file = snapshotDir.resolve(sessionId + ".json");
        if (!Files.exists(file)) {
            log.debug("No snapshot found for session: {}", sessionId);
            return Optional.empty();
        }
        try {
            SessionSnapshot snapshot = objectMapper.readValue(file.toFile(), SessionSnapshot.class);
            log.info("Session snapshot loaded: {} (messages={}, model={})",
                    sessionId, snapshot.messages().size(), snapshot.model());
            return Optional.of(snapshot);
        } catch (IOException e) {
            log.error("Failed to load snapshot for session {}: {}", sessionId, e.getMessage(), e);
            return Optional.empty();
        }
    }

    /**
     * 列出所有快照摘要（按创建时间降序）。
     * <p>
     * 读取每个 JSON 文件的顶层字段生成摘要，不加载完整 messages 列表。
     *
     * @return 快照摘要列表
     */
    public List<SessionSnapshotSummary> listSnapshots() {
        if (!Files.isDirectory(snapshotDir)) {
            return List.of();
        }

        List<SessionSnapshotSummary> summaries = new ArrayList<>();
        try (Stream<Path> paths = Files.list(snapshotDir)) {
            paths.filter(p -> p.toString().endsWith(".json"))
                    .forEach(p -> {
                        try {
                            // 读取完整快照以获取摘要信息
                            SessionSnapshot snapshot = objectMapper.readValue(p.toFile(), SessionSnapshot.class);
                            summaries.add(new SessionSnapshotSummary(
                                    snapshot.sessionId(),
                                    snapshot.model(),
                                    snapshot.turnCount(),
                                    snapshot.messages() != null ? snapshot.messages().size() : 0,
                                    snapshot.createdAt()
                            ));
                        } catch (IOException e) {
                            log.warn("Failed to read snapshot file {}: {}", p.getFileName(), e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.error("Failed to list snapshot directory: {}", e.getMessage(), e);
        }

        // 按创建时间降序排序
        summaries.sort(Comparator.comparing(
                (SessionSnapshotSummary s) -> s.createdAt() != null ? s.createdAt() : Instant.EPOCH)
                .reversed());

        return summaries;
    }

    /**
     * 删除快照。
     *
     * @param sessionId 会话 ID
     * @return true 如果快照存在并被删除
     */
    public boolean deleteSnapshot(String sessionId) {
        validateSessionId(sessionId);
        Path file = snapshotDir.resolve(sessionId + ".json");
        try {
            boolean deleted = Files.deleteIfExists(file);
            if (deleted) {
                log.info("Session snapshot deleted: {}", sessionId);
            } else {
                log.debug("No snapshot to delete for session: {}", sessionId);
            }
            return deleted;
        } catch (IOException e) {
            log.error("Failed to delete snapshot for session {}: {}", sessionId, e.getMessage(), e);
            return false;
        }
    }
}
