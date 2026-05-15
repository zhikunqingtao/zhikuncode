package com.aicodeassistant.engine.tracking;

import com.aicodeassistant.engine.strategy.TerminationStrategy.ToolCallRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工具调用追踪器 — 记录工具调用历史并追踪连续错误状态。
 * <p>
 * 功能：
 * - 记录每次工具调用结果（成功/失败）
 * - 追踪连续错误计数（成功时重置）
 * - 提供滑动窗口检查（最近 N 次是否全失败）
 * - 检测相同错误是否连续重复
 * <p>
 * 线程安全：使用 synchronized 保护 history 列表，AtomicInteger 保护计数器。
 * <p>
 * 注意：每个查询会话应持有独立实例，在 QueryEngine.queryLoop 内部通过 new 创建。
 * 不作为 Spring Bean，避免单例共享导致跨会话状态串扰。
 */
public class ToolCallTracker {

    private static final Logger log = LoggerFactory.getLogger(ToolCallTracker.class);

    private final List<ToolCallRecord> history = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger consecutiveErrors = new AtomicInteger(0);

    /**
     * 记录一次工具调用结果。
     *
     * @param toolName     工具名称
     * @param success      是否成功
     * @param errorMessage 错误信息（成功时传 null）
     */
    public void record(String toolName, boolean success, String errorMessage) {
        history.add(new ToolCallRecord(toolName, success, errorMessage, Instant.now()));
        if (success) {
            consecutiveErrors.set(0);
        } else {
            int count = consecutiveErrors.incrementAndGet();
            log.debug("ToolCallTracker: consecutive errors = {} (tool={})", count, toolName);
        }
    }

    /**
     * 获取连续错误计数。
     */
    public int getConsecutiveErrors() {
        return consecutiveErrors.get();
    }

    /**
     * 获取最近 N 条记录。
     *
     * @param n 记录数量
     * @return 最近的 N 条记录（不足 N 条时返回全部）
     */
    public List<ToolCallRecord> getRecentRecords(int n) {
        synchronized (history) {
            if (history.isEmpty()) return List.of();
            int start = Math.max(0, history.size() - n);
            return new ArrayList<>(history.subList(start, history.size()));
        }
    }

    /**
     * 检查滑动窗口 — 最近 N 次调用是否全部失败。
     *
     * @param windowSize 窗口大小
     * @return true 如果最近 windowSize 次调用全部失败
     */
    public boolean isWindowAllFailed(int windowSize) {
        synchronized (history) {
            if (history.size() < windowSize) return false;
            List<ToolCallRecord> window = history.subList(
                    history.size() - windowSize, history.size());
            return window.stream().noneMatch(ToolCallRecord::success);
        }
    }

    /**
     * 检查相同错误是否连续出现 N 次。
     *
     * @param threshold 阈值
     * @return true 如果最近连续 threshold 次错误信息相同
     */
    public boolean isSameErrorRepeated(int threshold) {
        synchronized (history) {
            if (history.size() < threshold) return false;
            List<ToolCallRecord> tail = history.subList(
                    history.size() - threshold, history.size());
            // 全部失败且错误信息相同
            if (tail.stream().anyMatch(ToolCallRecord::success)) return false;
            String firstError = tail.getFirst().errorMessage();
            if (firstError == null) return false;
            return tail.stream().allMatch(r -> firstError.equals(r.errorMessage()));
        }
    }

    /**
     * 获取总调用记录数。
     */
    public int getTotalRecords() {
        return history.size();
    }

    /**
     * 重置追踪器 — 清除所有历史记录和计数器。
     * 在新会话开始时调用。
     */
    public void reset() {
        history.clear();
        consecutiveErrors.set(0);
        log.debug("ToolCallTracker reset");
    }
}
