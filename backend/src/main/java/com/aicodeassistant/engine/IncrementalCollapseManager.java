package com.aicodeassistant.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 增量折叠管理器 — 管理会话级的上下文折叠状态。
 * 
 * <p>当会话的对话轮次达到阈值（默认每10轮），触发增量折叠，
 * 将历史消息压缩为摘要段，避免每次全量压缩带来的性能开销。</p>
 * 
 * <p>线程安全设计：使用ConcurrentHashMap + CopyOnWriteArrayList保证并发访问安全。</p>
 * 
 * <p>通过Feature Flag控制启停：context.cascade.incremental-collapse.enabled=true时激活。</p>
 */
@Service
@ConditionalOnProperty(name = "context.cascade.incremental-collapse.enabled", havingValue = "true")
public class IncrementalCollapseManager {

    private static final Logger log = LoggerFactory.getLogger(IncrementalCollapseManager.class);

    /** 每N轮触发一次增量折叠 */
    private static final int COLLAPSE_SEGMENT_TURNS = 10;

    /** 会话超时时间（毫秒）— 30分钟无活动后清理 */
    private static final long SESSION_TIMEOUT_MS = 30 * 60 * 1000L;

    /** 会话状态存储 */
    private final ConcurrentHashMap<String, CollapseState> sessionStates = new ConcurrentHashMap<>();

    /**
     * 折叠段元数据记录
     */
    public record CollapsedSegment(
            int turnStart,
            int turnEnd,
            String summaryText,
            int originalTokens,
            int summaryTokens,
            List<String> originalMessageIds
    ) {}

    /**
     * 会话级折叠状态（线程安全）
     * 
     * <p>使用累计turn计数器跨请求追踪会话总轮次，解决per-request turn count
     * 每次请求重置导致折叠永远无法触发的问题。</p>
     */
    static class CollapseState {
        private final CopyOnWriteArrayList<CollapsedSegment> segments = new CopyOnWriteArrayList<>();
        private final AtomicInteger cumulativeTurns = new AtomicInteger(0);
        private volatile int lastCollapsedAtCumulativeTurn = 0;
        private volatile long lastAccessTime = System.currentTimeMillis();

        void updateAccessTime() {
            this.lastAccessTime = System.currentTimeMillis();
        }

        long getLastAccessTime() {
            return lastAccessTime;
        }

        int getCumulativeTurns() {
            return cumulativeTurns.get();
        }

        int addTurns(int turns) {
            return cumulativeTurns.addAndGet(turns);
        }

        int getLastCollapsedAtCumulativeTurn() {
            return lastCollapsedAtCumulativeTurn;
        }

        void setLastCollapsedAtCumulativeTurn(int turn) {
            this.lastCollapsedAtCumulativeTurn = turn;
        }

        List<CollapsedSegment> getSegments() {
            return segments;
        }

        void addSegment(CollapsedSegment segment) {
            segments.add(segment);
        }
    }

    /**
     * 判断指定会话是否应触发增量折叠。
     *
     * <p>将每次请求贡献的turn数累加到会话级计数器，当累计值与上次折叠点的差值
     * 达到阈值时触发折叠。这解决了per-request turn count每次重置导致永远无法触发的bug。</p>
     *
     * @param sessionId 会话ID
     * @param currentTurn 本次请求贡献的turn数（由QueryEngine传入的per-request turn count）
     * @return true 如果应触发折叠
     */
    public boolean shouldCollapse(String sessionId, int currentTurn) {
        CollapseState state = sessionStates.computeIfAbsent(sessionId, k -> new CollapseState());
        state.updateAccessTime();

        int totalTurns = state.addTurns(currentTurn);
        int lastCollapsedAt = state.getLastCollapsedAtCumulativeTurn();
        return (totalTurns - lastCollapsedAt) >= COLLAPSE_SEGMENT_TURNS;
    }

    /**
     * 记录一次折叠操作的结果。
     *
     * @param sessionId 会话ID
     * @param segment 折叠段元数据
     */
    public void recordCollapse(String sessionId, CollapsedSegment segment) {
        CollapseState state = sessionStates.computeIfAbsent(sessionId, k -> new CollapseState());
        state.updateAccessTime();
        state.addSegment(segment);
        state.setLastCollapsedAtCumulativeTurn(state.getCumulativeTurns());
        log.info("Recorded collapse for session {}: turns {}-{}, {} tokens → {} tokens (cumulative: {})",
                sessionId, segment.turnStart(), segment.turnEnd(),
                segment.originalTokens(), segment.summaryTokens(), state.getCumulativeTurns());
    }

    /**
     * 获取指定会话的所有折叠段（只读视图）。
     *
     * @param sessionId 会话ID
     * @return 折叠段列表，若会话不存在返回空列表
     */
    public List<CollapsedSegment> getSegments(String sessionId) {
        CollapseState state = sessionStates.get(sessionId);
        if (state == null) return List.of();
        state.updateAccessTime();
        return List.copyOf(state.getSegments());
    }

    /**
     * 定时清理过期会话状态，防止内存泄漏。
     * 每5分钟执行一次，清理30分钟无活动的会话。
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        int removed = 0;
        var iterator = sessionStates.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if ((now - entry.getValue().getLastAccessTime()) > SESSION_TIMEOUT_MS) {
                iterator.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.info("Cleaned up {} expired collapse session(s), remaining: {}",
                    removed, sessionStates.size());
        }
    }

    /**
     * 获取当前活跃会话数量（用于监控）。
     */
    public int getActiveSessionCount() {
        return sessionStates.size();
    }
}
