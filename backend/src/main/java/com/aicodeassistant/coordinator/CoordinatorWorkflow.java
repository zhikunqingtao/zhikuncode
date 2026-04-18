package com.aicodeassistant.coordinator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 四阶段工作流引擎核心类。
 * <p>
 * 管理工作流的完整生命周期：
 * Research → Synthesis → Implementation → Verification（严格顺序）。
 * <p>
 * 每次阶段转换记录时间戳和结果摘要。
 * 阶段转换不可跳过，必须按顺序推进。
 *
 * @see WorkflowPhase
 * @see CoordinatorWorkflowEngine
 */
public class CoordinatorWorkflow {

    private static final Logger log = LoggerFactory.getLogger(CoordinatorWorkflow.class);

    /** 工作流状态枚举 */
    public enum WorkflowStatus {
        NOT_STARTED, RUNNING, COMPLETED, FAILED, CANCELLED
    }

    /** 阶段历史记录 */
    public record PhaseRecord(
            WorkflowPhase phase,
            Instant startTime,
            Instant endTime,
            String resultSummary
    ) {}

    private final String workflowId;
    private final String objective;
    private final AtomicReference<WorkflowPhase> currentPhase;
    private final List<PhaseRecord> phaseHistory;
    private final Instant startTime;
    private volatile WorkflowStatus status;
    private volatile Instant endTime;

    /**
     * 创建工作流实例（尚未启动）。
     *
     * @param workflowId 工作流唯一 ID
     * @param objective  工作流目标描述
     */
    public CoordinatorWorkflow(String workflowId, String objective) {
        this.workflowId = workflowId;
        this.objective = objective;
        this.currentPhase = new AtomicReference<>(null);
        this.phaseHistory = Collections.synchronizedList(new ArrayList<>());
        this.startTime = Instant.now();
        this.status = WorkflowStatus.NOT_STARTED;
    }

    // ═══════════════════════════════════════════════════════════════
    // 核心方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 启动工作流 — 初始化并进入 Research 阶段。
     *
     * @return Research 阶段实例
     * @throws IllegalStateException 如果工作流已启动
     */
    public WorkflowPhase.Research startWorkflow() {
        if (status != WorkflowStatus.NOT_STARTED) {
            throw new IllegalStateException("Workflow already started: " + workflowId);
        }

        WorkflowPhase.Research research = new WorkflowPhase.Research(
                objective, new ArrayList<>());
        currentPhase.set(research);
        status = WorkflowStatus.RUNNING;

        phaseHistory.add(new PhaseRecord(research, Instant.now(), null, null));

        log.info("Workflow {} started — entering Research phase (objective: {})",
                workflowId, truncate(objective, 80));

        return research;
    }

    /**
     * 推进到下一阶段。
     * <p>
     * 阶段转换严格顺序：Research → Synthesis → Implementation → Verification。
     * 完成 Verification 后工作流标记为 COMPLETED。
     *
     * @param resultSummary 当前阶段的结果摘要
     * @return 下一阶段，若已完成则返回 null
     * @throws IllegalStateException 如果工作流未运行或阶段无效
     */
    public WorkflowPhase advancePhase(String resultSummary) {
        if (status != WorkflowStatus.RUNNING) {
            throw new IllegalStateException("Workflow not running: " + workflowId + " (status=" + status + ")");
        }

        WorkflowPhase current = currentPhase.get();
        if (current == null) {
            throw new IllegalStateException("No current phase in workflow: " + workflowId);
        }

        // 记录当前阶段完成
        closePhaseSummary(current, resultSummary);

        // 推进到下一阶段
        WorkflowPhase next = resolveNextPhase(current);
        if (next == null) {
            // Verification 完成 → 工作流结束
            status = WorkflowStatus.COMPLETED;
            endTime = Instant.now();
            currentPhase.set(null);
            log.info("Workflow {} completed (duration={}s)",
                    workflowId, java.time.Duration.between(startTime, endTime).getSeconds());
            return null;
        }

        currentPhase.set(next);
        phaseHistory.add(new PhaseRecord(next, Instant.now(), null, null));

        log.info("Workflow {} advanced: {} → {} (summary: {})",
                workflowId, current.name(), next.name(), truncate(resultSummary, 100));

        return next;
    }

    /**
     * 获取当前阶段。
     *
     * @return 当前 WorkflowPhase，若未启动或已完成则返回 null
     */
    public WorkflowPhase getCurrentPhase() {
        return currentPhase.get();
    }

    /**
     * 获取阶段历史记录（不可变副本）。
     */
    public List<PhaseRecord> getPhaseHistory() {
        return List.copyOf(phaseHistory);
    }

    /**
     * 工作流是否已完成所有阶段。
     */
    public boolean isComplete() {
        return status == WorkflowStatus.COMPLETED;
    }

    /**
     * 标记工作流失败。
     *
     * @param reason 失败原因
     */
    public void markFailed(String reason) {
        WorkflowPhase current = currentPhase.get();
        if (current != null) {
            closePhaseSummary(current, "FAILED: " + reason);
        }
        status = WorkflowStatus.FAILED;
        endTime = Instant.now();
        log.error("Workflow {} failed at phase {}: {}",
                workflowId, current != null ? current.name() : "N/A", reason);
    }

    /**
     * 取消工作流。
     */
    public void cancel() {
        WorkflowPhase current = currentPhase.get();
        if (current != null) {
            closePhaseSummary(current, "CANCELLED");
        }
        status = WorkflowStatus.CANCELLED;
        endTime = Instant.now();
        log.info("Workflow {} cancelled at phase {}",
                workflowId, current != null ? current.name() : "N/A");
    }

    // ═══════════════════════════════════════════════════════════════
    // Getters
    // ═══════════════════════════════════════════════════════════════

    public String getWorkflowId() { return workflowId; }
    public String getObjective() { return objective; }
    public WorkflowStatus getStatus() { return status; }
    public Instant getStartTime() { return startTime; }
    public Instant getEndTime() { return endTime; }

    /**
     * 获取当前阶段索引 (0-3)，未启动或已完成返回 -1。
     */
    public int getCurrentPhaseIndex() {
        WorkflowPhase p = currentPhase.get();
        return p != null ? p.phaseIndex() : -1;
    }

    // ═══════════════════════════════════════════════════════════════
    // 内部方法
    // ═══════════════════════════════════════════════════════════════

    /**
     * 根据当前阶段解析下一阶段。
     * 严格顺序：Research → Synthesis → Implementation → Verification → null
     */
    private WorkflowPhase resolveNextPhase(WorkflowPhase current) {
        return switch (current) {
            case WorkflowPhase.Research r ->
                    new WorkflowPhase.Synthesis("", new ArrayList<>());
            case WorkflowPhase.Synthesis s ->
                    new WorkflowPhase.Implementation("", new ArrayList<>());
            case WorkflowPhase.Implementation i ->
                    new WorkflowPhase.Verification(new ArrayList<>(), "");
            case WorkflowPhase.Verification v ->
                    null; // 工作流完成
        };
    }

    /**
     * 关闭阶段历史记录（填充 endTime 和 resultSummary）。
     */
    private void closePhaseSummary(WorkflowPhase phase, String summary) {
        for (int i = phaseHistory.size() - 1; i >= 0; i--) {
            PhaseRecord record = phaseHistory.get(i);
            if (record.phase().name().equals(phase.name()) && record.endTime() == null) {
                phaseHistory.set(i, new PhaseRecord(
                        record.phase(), record.startTime(), Instant.now(), summary));
                break;
            }
        }
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
