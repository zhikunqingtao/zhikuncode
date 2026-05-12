package com.aicodeassistant.coordinator;

import com.aicodeassistant.model.dto.ToolCallRecord;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Worker 状态追踪器 — Phase 2 工具调用历史维护。
 * <p>
 * 每个 Worker 独立维护最近 10 条 ToolCallRecord，用于：
 * <ul>
 *   <li>异常检测（重复调用、循环模式识别）</li>
 *   <li>Worker 进度展示（最近工具调用列表）</li>
 *   <li>诊断日志（异常事件上下文快照）</li>
 * </ul>
 * <p>
 * 线程安全：使用 ConcurrentHashMap + synchronized Deque。
 */
@Component
public class WorkerStateTracker {

    private static final int MAX_RECENT_TOOL_CALLS = 10;

    /** workerId → 最近 10 条 ToolCallRecord */
    private final Map<String, Deque<ToolCallRecord>> recentToolCalls = new ConcurrentHashMap<>();

    /**
     * 记录一次工具调用。
     *
     * @param workerId Worker 唯一标识
     * @param record   工具调用记录
     */
    public void recordToolCall(String workerId, ToolCallRecord record) {
        recentToolCalls.computeIfAbsent(workerId, k -> new ArrayDeque<>(MAX_RECENT_TOOL_CALLS));
        Deque<ToolCallRecord> queue = recentToolCalls.get(workerId);
        synchronized (queue) {
            if (queue.size() >= MAX_RECENT_TOOL_CALLS) queue.pollFirst();
            queue.addLast(record);
        }
    }

    /**
     * 获取指定 Worker 最近的工具调用记录。
     *
     * @param workerId Worker 唯一标识
     * @return 不可变的调用记录列表（最多 10 条）
     */
    public List<ToolCallRecord> getRecentToolCalls(String workerId) {
        Deque<ToolCallRecord> queue = recentToolCalls.get(workerId);
        if (queue == null) return List.of();
        synchronized (queue) {
            return List.copyOf(queue);
        }
    }

    /**
     * 清除指定 Worker 的所有追踪数据。
     * 在 Worker 终止或 Swarm 关闭时调用。
     *
     * @param workerId Worker 唯一标识
     */
    public void clearWorker(String workerId) {
        recentToolCalls.remove(workerId);
    }

    /**
     * 清除所有追踪数据（Swarm 强制关闭时使用）。
     */
    public void clearAll() {
        recentToolCalls.clear();
    }

    /**
     * 检测是否存在重复调用模式。
     * <p>
     * 规则：最近 N 次调用中，相同 toolName + paramsHash 出现超过阈值次数。
     *
     * @param workerId  Worker ID
     * @param threshold 重复次数阈值
     * @return true 如果检测到重复模式
     */
    public boolean detectRepetition(String workerId, int threshold) {
        List<ToolCallRecord> records = getRecentToolCalls(workerId);
        if (records.size() < threshold) return false;

        Map<String, Integer> fingerprints = new HashMap<>();
        for (ToolCallRecord r : records) {
            String key = r.toolName() + ":" + r.paramsHash();
            int count = fingerprints.merge(key, 1, Integer::sum);
            if (count >= threshold) return true;
        }
        return false;
    }

    /**
     * 获取当前被追踪的 Worker 数量。
     */
    public int trackedWorkerCount() {
        return recentToolCalls.size();
    }

    /**
     * 获取指定 Worker 的调用计数。
     */
    public int getToolCallCount(String workerId) {
        Deque<ToolCallRecord> queue = recentToolCalls.get(workerId);
        if (queue == null) return 0;
        synchronized (queue) {
            return queue.size();
        }
    }
}
