package com.aicodeassistant.coordinator;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Swarm 运行时状态管理 — 追踪 Swarm 实例及所有 Worker 的实时状态。
 * <p>
 * 线程安全：使用 ConcurrentHashMap 存储 Worker 状态，AtomicReference 管理阶段切换。
 * <p>
 * 每个 Swarm 实例维护一个 SwarmState 对象，包含：
 * - Swarm 生命周期阶段
 * - 所有 Worker 的状态快照
 * - 统计信息（已完成任务数、总 Token 消耗等）
 *
 * @see <a href="SPEC §11">Team/Swarm 多Agent协作</a>
 */
public class SwarmState {

    private final String swarmId;
    private final String teamName;
    private final AtomicReference<SwarmPhase> phase;
    private final ConcurrentHashMap<String, WorkerState> workers;
    private final Instant createdAt;

    /** 已完成任务计数 */
    private final AtomicInteger completedTaskCount = new AtomicInteger(0);
    /** 总提交任务计数 */
    private final AtomicInteger totalTaskCount = new AtomicInteger(0);

    public SwarmState(String swarmId, String teamName) {
        this.swarmId = swarmId;
        this.teamName = teamName;
        this.phase = new AtomicReference<>(SwarmPhase.INITIALIZING);
        this.workers = new ConcurrentHashMap<>();
        this.createdAt = Instant.now();
        // AtomicInteger fields initialized at declaration
    }

    // ═══ Getters ═══

    public String swarmId() { return swarmId; }
    public String teamName() { return teamName; }
    public SwarmPhase phase() { return phase.get(); }
    public Map<String, WorkerState> workers() { return workers; }
    public Instant createdAt() { return createdAt; }
    public int completedTaskCount() { return completedTaskCount.get(); }
    public int totalTaskCount() { return totalTaskCount.get(); }

    // ═══ Phase Transitions ═══

    /**
     * 转换 Swarm 阶段 — CAS 保证线程安全。
     *
     * @param expected 期望的当前阶段
     * @param target   目标阶段
     * @return true 如果切换成功
     */
    public boolean transitionPhase(SwarmPhase expected, SwarmPhase target) {
        return phase.compareAndSet(expected, target);
    }

    /** 强制设置阶段（用于错误恢复） */
    public void forcePhase(SwarmPhase target) {
        phase.set(target);
    }

    // ═══ Worker Management ═══

    /** 注册新 Worker */
    public void registerWorker(String workerId, String taskPrompt) {
        workers.put(workerId, new WorkerState(
                workerId, WorkerStatus.STARTING, 0, 0L,
                taskPrompt, List.of(), Instant.now()));
        totalTaskCount.incrementAndGet();
    }

    /** 更新 Worker 状态为工作中 */
    public void markWorkerWorking(String workerId, String currentTask) {
        WorkerState current = workers.get(workerId);
        if (current != null) {
            workers.put(workerId, new WorkerState(
                    workerId, WorkerStatus.WORKING, current.toolCallCount(),
                    current.tokenConsumed(), currentTask,
                    current.recentToolCalls(), current.startedAt()));
        }
    }

    /** 更新 Worker 工具调用计数 */
    public void updateWorkerToolCall(String workerId, String toolName) {
        WorkerState current = workers.get(workerId);
        if (current != null) {
            List<String> recentTools = new java.util.ArrayList<>(current.recentToolCalls());
            recentTools.add(toolName);
            if (recentTools.size() > 5) {
                recentTools = recentTools.subList(recentTools.size() - 5, recentTools.size());
            }
            workers.put(workerId, new WorkerState(
                    workerId, current.status(), current.toolCallCount() + 1,
                    current.tokenConsumed(), current.currentTask(),
                    List.copyOf(recentTools), current.startedAt()));
        }
    }

    /** 更新 Worker Token 消耗 */
    public void updateWorkerTokens(String workerId, long tokensUsed) {
        WorkerState current = workers.get(workerId);
        if (current != null) {
            workers.put(workerId, new WorkerState(
                    workerId, current.status(), current.toolCallCount(),
                    current.tokenConsumed() + tokensUsed, current.currentTask(),
                    current.recentToolCalls(), current.startedAt()));
        }
    }

    /** 标记 Worker 为空闲 */
    public void markWorkerIdle(String workerId) {
        WorkerState current = workers.get(workerId);
        if (current != null) {
            workers.put(workerId, new WorkerState(
                    workerId, WorkerStatus.IDLE, current.toolCallCount(),
                    current.tokenConsumed(), "idle",
                    current.recentToolCalls(), current.startedAt()));
            completedTaskCount.incrementAndGet();
        }
    }

    /** 标记 Worker 已终止 */
    public void markWorkerTerminated(String workerId) {
        WorkerState current = workers.get(workerId);
        if (current != null) {
            workers.put(workerId, new WorkerState(
                    workerId, WorkerStatus.TERMINATED, current.toolCallCount(),
                    current.tokenConsumed(), "terminated",
                    current.recentToolCalls(), current.startedAt()));
        }
    }

    /** 获取活跃 Worker 数量 */
    public int activeWorkerCount() {
        return (int) workers.values().stream()
                .filter(w -> w.status() == WorkerStatus.WORKING || w.status() == WorkerStatus.STARTING)
                .count();
    }

    /** 获取空闲 Worker 数量 */
    public int idleWorkerCount() {
        return (int) workers.values().stream()
                .filter(w -> w.status() == WorkerStatus.IDLE)
                .count();
    }

    /** 总 Token 消耗 */
    public long totalTokenConsumed() {
        return workers.values().stream().mapToLong(WorkerState::tokenConsumed).sum();
    }

    // ═══ Enums ═══

    /** Swarm 生命周期阶段 */
    public enum SwarmPhase {
        INITIALIZING,
        RUNNING,
        IDLE,
        SHUTTING_DOWN,
        TERMINATED
    }

    /** Worker 状态 */
    public enum WorkerStatus {
        STARTING,
        WORKING,
        IDLE,
        TERMINATED
    }

    // ═══ Worker State Record ═══

    /**
     * Worker 状态快照 — 不可变。
     *
     * @param workerId        Worker 唯一标识
     * @param status          当前状态
     * @param toolCallCount   工具调用次数
     * @param tokenConsumed   Token 消耗量
     * @param currentTask     当前任务描述
     * @param recentToolCalls 最近 5 个工具调用名称
     * @param startedAt       Worker 启动时间
     */
    public record WorkerState(
            String workerId,
            WorkerStatus status,
            int toolCallCount,
            long tokenConsumed,
            String currentTask,
            List<String> recentToolCalls,
            Instant startedAt
    ) {}
}
