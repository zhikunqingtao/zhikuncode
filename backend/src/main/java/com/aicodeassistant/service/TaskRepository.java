package com.aicodeassistant.service;

import com.aicodeassistant.model.Task;
import com.aicodeassistant.model.TaskStatus;
import com.aicodeassistant.model.TaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 任务仓库 — 管理代理任务的持久化。
 * <p>
 * 对应 data.db 的 tasks 表。
 *
 * @see <a href="SPEC §5.2">任务模型</a>
 */
@Repository
public class TaskRepository {

    private static final Logger log = LoggerFactory.getLogger(TaskRepository.class);

    private final JdbcTemplate projectJdbcTemplate;

    public TaskRepository(@Qualifier("projectJdbcTemplate") JdbcTemplate projectJdbcTemplate) {
        this.projectJdbcTemplate = projectJdbcTemplate;
    }

    /** 保存任务 */
    public void save(Task task) {
        projectJdbcTemplate.update(
                "INSERT OR REPLACE INTO tasks (id, session_id, type, status, description, output, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                task.id(), task.sessionId(), task.type().name(),
                task.status().name(), task.description(), task.output(),
                task.createdAt() != null ? task.createdAt().toString() : null,
                task.updatedAt() != null ? task.updatedAt().toString() : null);
    }

    /** 按会话 ID 查找任务 */
    public List<Task> findBySessionId(String sessionId) {
        return projectJdbcTemplate.query(
                "SELECT id, session_id, type, status, description, output, created_at, updated_at FROM tasks WHERE session_id = ? ORDER BY created_at",
                (rs, rowNum) -> new Task(
                        rs.getString("id"),
                        rs.getString("description"),
                        TaskType.valueOf(rs.getString("type")),
                        TaskStatus.valueOf(rs.getString("status")),
                        rs.getString("output"),
                        null, 0.0,
                        rs.getString("session_id"),
                        null, 0, false,
                        parseInstant(rs.getString("created_at")),
                        parseInstant(rs.getString("updated_at")),
                        null),
                sessionId);
    }

    /** 按 ID 查找任务 */
    public Optional<Task> findById(String taskId) {
        List<Task> results = projectJdbcTemplate.query(
                "SELECT id, session_id, type, status, description, output, created_at, updated_at FROM tasks WHERE id = ?",
                (rs, rowNum) -> new Task(
                        rs.getString("id"),
                        rs.getString("description"),
                        TaskType.valueOf(rs.getString("type")),
                        TaskStatus.valueOf(rs.getString("status")),
                        rs.getString("output"),
                        null, 0.0,
                        rs.getString("session_id"),
                        null, 0, false,
                        parseInstant(rs.getString("created_at")),
                        parseInstant(rs.getString("updated_at")),
                        null),
                taskId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    /** 更新任务状态 */
    public void updateStatus(String taskId, TaskStatus status) {
        projectJdbcTemplate.update(
                "UPDATE tasks SET status = ?, updated_at = ? WHERE id = ?",
                status.name(), Instant.now().toString(), taskId);
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            return null;
        }
    }
}
