package com.aicodeassistant.tool.task;

import com.aicodeassistant.model.TaskStatus;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * TaskCoordinator — 任务编排服务。
 * <p>
 * 职责:
 * <ol>
 *   <li>管理所有后台任务的生命周期 (submit/cancel/query)</li>
 *   <li>通过 Virtual Thread 池执行任务（每个 Task 独立虚拟线程）</li>
 *   <li>三层中断传播 (Thread.interrupt → Cancellable → 递归子任务)</li>
 *   <li>任务进度通过 STOMP 实时推送</li>
 *   <li>超时看门狗：PER_TASK_TIMEOUT 后强制取消</li>
 * </ol>
 *
 * @see <a href="SPEC §4.1.3a">TaskCoordinator 任务编排服务</a>
 */
@Service
public class TaskCoordinator {

    private static final Logger log = LoggerFactory.getLogger(TaskCoordinator.class);

    /** 最大并发任务数 */
    static final int MAX_CONCURRENT_TASKS = 10;

    /** 单任务超时 */
    static final Duration PER_TASK_TIMEOUT = Duration.ofMinutes(30);

    /** TaskResult 最大大小 (1MB) */
    static final int MAX_OUTPUT_SIZE = 1024 * 1024;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentMap<String, TaskState> tasks = new ConcurrentHashMap<>();
    private final SimpMessagingTemplate messagingTemplate;
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "task-watchdog");
        t.setDaemon(true);
        return t;
    });

    public TaskCoordinator(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * 提交任务 — 在独立虚拟线程中异步执行。
     *
     * @param taskId      唯一任务 ID
     * @param sessionId   所属会话 ID（用于 STOMP 推送）
     * @param description 任务描述
     * @param runnable    任务执行体
     * @return TaskState 任务状态引用
     * @throws IllegalStateException 并发任务数超过 MAX_CONCURRENT_TASKS
     */
    public TaskState submit(String taskId, String sessionId, String description, Runnable runnable) {
        long activeCount = tasks.values().stream()
                .filter(t -> !t.getStatus().isTerminal())
                .count();

        if (activeCount >= MAX_CONCURRENT_TASKS) {
            throw new IllegalStateException(
                    "Max concurrent tasks reached: " + MAX_CONCURRENT_TASKS);
        }

        TaskState state = new TaskState(taskId, sessionId, TaskStatus.PENDING, description);
        tasks.put(taskId, state);

        Future<?> future = executor.submit(() -> {
            state.setStatus(TaskStatus.RUNNING);
            notifyTaskUpdate(state);
            try {
                runnable.run();
                if (!state.getStatus().isTerminal()) {
                    state.setStatus(TaskStatus.COMPLETED);
                }
            } catch (Exception e) {
                if (!state.getStatus().isTerminal()) {
                    state.setStatus(TaskStatus.FAILED);
                    state.setError(e.getMessage());
                }
                log.error("Task {} failed: {}", taskId, e.getMessage(), e);
            } finally {
                notifyTaskUpdate(state);
            }
        });

        state.setFuture(future);

        // 超时看门狗: PER_TASK_TIMEOUT 后强制取消
        watchdog.schedule(() -> {
            if (!future.isDone()) {
                log.warn("Task {} timed out after {}, force cancelling", taskId, PER_TASK_TIMEOUT);
                cancelTask(taskId);
            }
        }, PER_TASK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);

        notifyTaskUpdate(state);
        return state;
    }

    /**
     * 取消任务 — 三层中断传播。
     * <p>
     * Layer 1: future.cancel(true) → Thread.interrupt()<br>
     * Layer 2: task.activeTool.cancel() → 终止子进程 (Bash/REPL)<br>
     * Layer 3: 递归取消子任务
     */
    public boolean cancelTask(String taskId) {
        TaskState task = tasks.get(taskId);
        if (task == null || task.getStatus().isTerminal()) {
            return false;
        }

        // Layer 2: 取消当前正在执行的工具
        Cancellable activeTool = task.getActiveTool();
        if (activeTool != null) {
            try {
                activeTool.cancel();
            } catch (Exception e) {
                log.warn("Failed to cancel active tool for task {}: {}", taskId, e.getMessage());
            }
        }

        // Layer 1: 中断虚拟线程
        Future<?> future = task.getFuture();
        if (future != null) {
            future.cancel(true);
        }

        // Layer 3: 递归取消子任务
        for (String childId : task.getChildTaskIds()) {
            cancelTask(childId);
        }

        task.setStatus(TaskStatus.CANCELLED);
        notifyTaskUpdate(task);

        log.info("Task {} cancelled (children: {})", taskId, task.getChildTaskIds().size());
        return true;
    }

    /** 查询单个任务 */
    public Optional<TaskState> getTask(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    /** 按状态过滤任务列表 */
    public List<TaskState> listTasks(String sessionId, TaskStatus filterStatus) {
        return tasks.values().stream()
                .filter(t -> t.getSessionId().equals(sessionId))
                .filter(t -> filterStatus == null || t.getStatus() == filterStatus)
                .sorted(Comparator.comparing(TaskState::getCreatedAt).reversed())
                .toList();
    }

    /** 截断超大输出（1MB 限制） */
    public static String truncateOutput(String output) {
        if (output == null || output.length() <= MAX_OUTPUT_SIZE) {
            return output;
        }
        return output.substring(0, MAX_OUTPUT_SIZE) + "\n[Output truncated at 1MB limit]";
    }

    /** STOMP 推送任务状态变更 */
    private void notifyTaskUpdate(TaskState task) {
        try {
            messagingTemplate.convertAndSend(
                    "/topic/session/" + task.getSessionId(),
                    Map.of("type", "task_update",
                            "taskId", task.getTaskId(),
                            "status", task.getStatus().name(),
                            "updatedAt", Instant.now().toEpochMilli())
            );
        } catch (Exception e) {
            log.warn("Failed to send task update for {}: {}", task.getTaskId(), e.getMessage());
        }
    }

    @PreDestroy
    public void cleanup() {
        log.info("TaskCoordinator shutting down, cancelling {} tasks", tasks.size());
        tasks.values().forEach(t -> cancelTask(t.getTaskId()));
        executor.shutdownNow();
        watchdog.shutdownNow();
    }
}
