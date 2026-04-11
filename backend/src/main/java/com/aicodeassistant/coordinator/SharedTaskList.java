package com.aicodeassistant.coordinator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 共享任务列表 — 多 Agent 间的任务分发与认领机制。
 * <p>
 * 基于 ConcurrentLinkedQueue 实现 FIFO 任务队列，
 * 支持 addTask/claimTask/completeTask 三步任务流转。
 *
 * @see <a href="SPEC §11">Team/Swarm 多Agent协作</a>
 */
@Component
public class SharedTaskList {

    private static final Logger log = LoggerFactory.getLogger(SharedTaskList.class);

    private final AtomicLong taskIdSequence = new AtomicLong(1);

    /** 待认领任务队列 */
    private final ConcurrentLinkedQueue<SharedTask> pendingTasks = new ConcurrentLinkedQueue<>();

    /** 所有任务跟踪 (taskId → task) */
    private final Map<String, SharedTask> allTasks = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 添加任务到共享列表。
     *
     * @param teamName    所属团队名称
     * @param description 任务描述
     * @param creatorId   创建者 Agent ID
     * @return 新建的任务
     */
    public SharedTask addTask(String teamName, String description, String creatorId) {
        String taskId = "task-" + taskIdSequence.getAndIncrement();
        SharedTask task = new SharedTask(taskId, teamName, description, creatorId,
                TaskStatus.PENDING, null, null, Instant.now(), null);
        pendingTasks.offer(task);
        allTasks.put(taskId, task);
        log.info("Task added: id={}, team={}, creator={}", taskId, teamName, creatorId);
        return task;
    }

    /**
     * 认领一个待处理任务（FIFO）。
     *
     * @param teamName 指定团队（只认领该团队的任务）
     * @param workerId 认领者 Agent ID
     * @return 被认领的任务，或 null（无可用任务）
     */
    public SharedTask claimTask(String teamName, String workerId) {
        Iterator<SharedTask> it = pendingTasks.iterator();
        while (it.hasNext()) {
            SharedTask task = it.next();
            if (task.teamName().equals(teamName) && task.status() == TaskStatus.PENDING) {
                it.remove();
                SharedTask claimed = task.withStatus(TaskStatus.IN_PROGRESS).withAssignee(workerId);
                allTasks.put(task.taskId(), claimed);
                log.info("Task claimed: id={}, worker={}", task.taskId(), workerId);
                return claimed;
            }
        }
        return null;
    }

    /**
     * 完成任务。
     *
     * @param taskId 任务 ID
     * @param result 任务结果
     */
    public void completeTask(String taskId, String result) {
        SharedTask task = allTasks.get(taskId);
        if (task == null) {
            log.warn("Task not found: {}", taskId);
            return;
        }
        SharedTask completed = task.withStatus(TaskStatus.COMPLETED).withResult(result);
        allTasks.put(taskId, completed);
        log.info("Task completed: id={}, assignee={}", taskId, task.assigneeId());
    }

    /**
     * 标记任务失败。
     */
    public void failTask(String taskId, String errorMessage) {
        SharedTask task = allTasks.get(taskId);
        if (task == null) return;
        allTasks.put(taskId, task.withStatus(TaskStatus.FAILED).withResult("ERROR: " + errorMessage));
        log.warn("Task failed: id={}, error={}", taskId, errorMessage);
    }

    /**
     * 获取指定团队的所有任务。
     */
    public List<SharedTask> getTasksByTeam(String teamName) {
        return allTasks.values().stream()
                .filter(t -> t.teamName().equals(teamName))
                .toList();
    }

    /**
     * 获取指定团队的待处理任务数。
     */
    public long getPendingCount(String teamName) {
        return pendingTasks.stream()
                .filter(t -> t.teamName().equals(teamName))
                .count();
    }

    /**
     * 获取任务详情。
     */
    public Optional<SharedTask> getTask(String taskId) {
        return Optional.ofNullable(allTasks.get(taskId));
    }

    /**
     * 清除指定团队的所有任务。
     */
    public void clearTeamTasks(String teamName) {
        pendingTasks.removeIf(t -> t.teamName().equals(teamName));
        allTasks.entrySet().removeIf(e -> e.getValue().teamName().equals(teamName));
    }

    /**
     * 清除所有任务。
     */
    public void clearAll() {
        pendingTasks.clear();
        allTasks.clear();
        log.info("All shared tasks cleared");
    }

    // ── DTO ──────────────────────────────────────────────────

    /** 任务状态 */
    public enum TaskStatus { PENDING, IN_PROGRESS, COMPLETED, FAILED }

    /** 共享任务 */
    public record SharedTask(
            String taskId,
            String teamName,
            String description,
            String creatorId,
            TaskStatus status,
            String assigneeId,
            String result,
            Instant createdAt,
            Instant completedAt
    ) {
        public SharedTask withStatus(TaskStatus newStatus) {
            return new SharedTask(taskId, teamName, description, creatorId,
                    newStatus, assigneeId, result, createdAt,
                    newStatus == TaskStatus.COMPLETED || newStatus == TaskStatus.FAILED
                            ? Instant.now() : completedAt);
        }

        public SharedTask withAssignee(String newAssignee) {
            return new SharedTask(taskId, teamName, description, creatorId,
                    status, newAssignee, result, createdAt, completedAt);
        }

        public SharedTask withResult(String newResult) {
            return new SharedTask(taskId, teamName, description, creatorId,
                    status, assigneeId, newResult, createdAt, completedAt);
        }
    }
}
