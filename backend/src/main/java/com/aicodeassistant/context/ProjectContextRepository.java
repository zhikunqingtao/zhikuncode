package com.aicodeassistant.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 项目上下文持久化仓储 — 操作 data.db 的 project_context 表。
 *
 * @see <a href="SPEC §7.2">SQLite Schema</a>
 */
@Repository
public class ProjectContextRepository {

    private static final Logger log = LoggerFactory.getLogger(ProjectContextRepository.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ProjectContextRepository(@Qualifier("projectJdbcTemplate") JdbcTemplate jdbcTemplate,
                                     ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 保存或更新项目上下文快照。
     */
    public void save(String workingDirHash, ProjectContextService.ProjectContextSnapshot snapshot,
                     String gitHeadSha) {
        String snapshotJson;
        try {
            snapshotJson = objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize ProjectContextSnapshot", e);
        }

        String now = Instant.now().toString();

        // UPSERT — SQLite ON CONFLICT
        int updated = jdbcTemplate.update(
                """
                UPDATE project_context
                SET snapshot_json = ?, git_head_sha = ?, updated_at = ?
                WHERE working_dir_hash = ?
                """,
                snapshotJson, gitHeadSha, now, workingDirHash
        );

        if (updated == 0) {
            jdbcTemplate.update(
                    """
                    INSERT INTO project_context (id, working_dir_hash, snapshot_json, git_head_sha, updated_at)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID().toString(), workingDirHash, snapshotJson, gitHeadSha, now
            );
        }
    }

    /**
     * 按工作目录哈希查询缓存的项目上下文。
     */
    public Optional<CachedContext> findByWorkingDirHash(String workingDirHash) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT snapshot_json, git_head_sha, updated_at FROM project_context WHERE working_dir_hash = ?",
                workingDirHash
        );

        if (rows.isEmpty()) return Optional.empty();

        Map<String, Object> row = rows.getFirst();
        try {
            ProjectContextService.ProjectContextSnapshot snapshot = objectMapper.readValue(
                    (String) row.get("snapshot_json"),
                    ProjectContextService.ProjectContextSnapshot.class);
            return Optional.of(new CachedContext(
                    snapshot,
                    (String) row.get("git_head_sha"),
                    (String) row.get("updated_at")
            ));
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize cached project context: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 删除指定工作目录的缓存。
     */
    public void deleteByWorkingDirHash(String workingDirHash) {
        jdbcTemplate.update("DELETE FROM project_context WHERE working_dir_hash = ?", workingDirHash);
    }

    /**
     * 缓存的项目上下文包装。
     */
    public record CachedContext(
            ProjectContextService.ProjectContextSnapshot snapshot,
            String gitHeadSha,
            String updatedAt
    ) {}
}
