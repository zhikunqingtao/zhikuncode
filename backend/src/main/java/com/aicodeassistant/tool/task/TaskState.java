package com.aicodeassistant.tool.task;

import com.aicodeassistant.model.TaskStatus;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;

/**
 * 任务状态记录 — 持有后台任务的完整生命周期状态。
 * <p>
 * 线程安全: 所有可变字段使用 volatile，列表使用 CopyOnWriteArrayList。
 *
 * @see <a href="SPEC §4.1.3a">TaskCoordinator</a>
 */
public class TaskState {

    private final String taskId;
    private final String sessionId;
    private final String description;
    private final Instant createdAt = Instant.now();
    private volatile TaskStatus status;
    private volatile String error;
    private volatile String output;
    private volatile Future<?> future;
    private volatile Cancellable activeTool;
    private final List<String> childTaskIds = new CopyOnWriteArrayList<>();
    private final List<String> childPids = new CopyOnWriteArrayList<>();

    public TaskState(String taskId, String sessionId, TaskStatus status) {
        this(taskId, sessionId, status, null);
    }

    public TaskState(String taskId, String sessionId, TaskStatus status, String description) {
        this.taskId = taskId;
        this.sessionId = sessionId;
        this.status = status;
        this.description = description;
    }

    // ===== Getters =====

    public String getTaskId() { return taskId; }
    public String getSessionId() { return sessionId; }
    public String getDescription() { return description; }
    public Instant getCreatedAt() { return createdAt; }
    public TaskStatus getStatus() { return status; }
    public String getError() { return error; }
    public String getOutput() { return output; }
    public Future<?> getFuture() { return future; }
    public Cancellable getActiveTool() { return activeTool; }
    public List<String> getChildTaskIds() { return childTaskIds; }
    public List<String> getChildPids() { return childPids; }

    // ===== Setters =====

    public void setStatus(TaskStatus status) { this.status = status; }
    public void setError(String error) { this.error = error; }
    public void setOutput(String output) { this.output = output; }
    public void setFuture(Future<?> future) { this.future = future; }
    public void setActiveTool(Cancellable activeTool) { this.activeTool = activeTool; }
}
