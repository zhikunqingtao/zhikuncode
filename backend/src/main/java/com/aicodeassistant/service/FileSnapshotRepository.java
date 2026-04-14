package com.aicodeassistant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 文件快照仓库 — 管理文件修改快照的持久化。
 * <p>
 * 对应 data.db 的 file_snapshots 表。
 *
 * @see <a href="SPEC §3.5">文件历史</a>
 */
@Repository
public class FileSnapshotRepository {

    private static final Logger log = LoggerFactory.getLogger(FileSnapshotRepository.class);

    private final JdbcTemplate projectJdbcTemplate;

    public FileSnapshotRepository(@Qualifier("projectJdbcTemplate") JdbcTemplate projectJdbcTemplate) {
        this.projectJdbcTemplate = projectJdbcTemplate;
    }

    /** 保存文件快照 */
    public void save(FileSnapshot snapshot) {
        projectJdbcTemplate.update(
                "INSERT INTO file_snapshots (id, session_id, message_id, file_path, content, operation, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                snapshot.id(), snapshot.sessionId(), snapshot.messageId(),
                snapshot.filePath(), snapshot.content(), snapshot.operation(), snapshot.createdAt());
        log.debug("Saved file snapshot: session={}, file={}, operation={}", snapshot.sessionId(), snapshot.filePath(), snapshot.operation());
    }

    /** 按会话 ID 查找所有快照 */
    public List<FileSnapshot> findBySessionId(String sessionId) {
        return projectJdbcTemplate.query(
                "SELECT id, session_id, message_id, file_path, content, operation, created_at FROM file_snapshots WHERE session_id = ? ORDER BY created_at",
                (rs, rowNum) -> new FileSnapshot(
                        rs.getString("id"),
                        rs.getString("session_id"),
                        rs.getString("message_id"),
                        rs.getString("file_path"),
                        rs.getString("content"),
                        rs.getString("operation"),
                        rs.getString("created_at")),
                sessionId);
    }

    /** 按消息 ID 查找快照 */
    public List<FileSnapshot> findByMessageId(String messageId) {
        return projectJdbcTemplate.query(
                "SELECT id, session_id, message_id, file_path, content, operation, created_at FROM file_snapshots WHERE message_id = ? ORDER BY created_at",
                (rs, rowNum) -> new FileSnapshot(
                        rs.getString("id"),
                        rs.getString("session_id"),
                        rs.getString("message_id"),
                        rs.getString("file_path"),
                        rs.getString("content"),
                        rs.getString("operation"),
                        rs.getString("created_at")),
                messageId);
    }

    /** 文件快照记录 */
    public record FileSnapshot(String id, String sessionId, String messageId,
                                String filePath, String content, String operation,
                                String createdAt) {}
}
