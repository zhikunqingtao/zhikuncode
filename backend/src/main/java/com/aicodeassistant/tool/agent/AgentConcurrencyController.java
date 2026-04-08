package com.aicodeassistant.tool.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 代理并发控制器 — 全局并发代理数硬限制。
 * <p>
 * 设计动机:
 *   源码中每个 AgentTool.call() 都创建新的 QueryEngine 实例，
 *   在多代理协作 (Swarm) 或嵌套代理场景下，无限制派生会导致:
 *   - 内存压力: 每个代理维护独立的上下文/消息历史
 *   - API 并发压力: 每个代理独立发起 LLM API 调用
 *   - Token 消耗失控: 并行代理同时消耗 token 配额
 *
 * @see <a href="SPEC §4.1.1c">并发代理硬限制</a>
 */
@Component
public class AgentConcurrencyController {

    private static final Logger log = LoggerFactory.getLogger(AgentConcurrencyController.class);

    // 全局并发限制 — 参考 batch.ts MAX_AGENTS=30
    static final int MAX_CONCURRENT_AGENTS = 30;

    // 单会话并发限制 — 交互式场景更保守
    static final int MAX_CONCURRENT_AGENTS_PER_SESSION = 10;

    // 嵌套深度限制 — 防止无限递归
    static final int MAX_AGENT_NESTING_DEPTH = 3;

    private final AtomicInteger activeAgentCount = new AtomicInteger(0);
    private final Semaphore globalSemaphore = new Semaphore(MAX_CONCURRENT_AGENTS);

    // v1.49.0 新增 (F5-03): 会话级并发计数器 — 确保 MAX_CONCURRENT_AGENTS_PER_SESSION 生效
    private final ConcurrentHashMap<String, AtomicInteger> sessionAgentCounts = new ConcurrentHashMap<>();

    /**
     * 尝试获取代理执行槽位。
     *
     * @param agentId      代理唯一标识
     * @param nestingDepth 当前嵌套深度
     * @param sessionId    会话 ID — 用于会话级并发检查
     * @return AutoCloseable 槽位，在 try-with-resources 中自动释放
     * @throws AgentLimitExceededException 超出并发限制时抛出
     */
    public AgentSlot acquireSlot(String agentId, int nestingDepth, String sessionId) {
        // 1. 嵌套深度检查
        if (nestingDepth > MAX_AGENT_NESTING_DEPTH) {
            throw new AgentLimitExceededException(
                    "Agent nesting depth %d exceeds max %d".formatted(
                            nestingDepth, MAX_AGENT_NESTING_DEPTH));
        }
        // 2. 全局并发检查
        if (!globalSemaphore.tryAcquire()) {
            throw new AgentLimitExceededException(
                    "Concurrent agent limit reached (%d)".formatted(
                            MAX_CONCURRENT_AGENTS));
        }
        // 3. 会话级并发检查
        AtomicInteger sessionCount = sessionAgentCounts
                .computeIfAbsent(sessionId, k -> new AtomicInteger(0));
        int currentSessionCount = sessionCount.incrementAndGet();
        if (currentSessionCount > MAX_CONCURRENT_AGENTS_PER_SESSION) {
            sessionCount.decrementAndGet();
            globalSemaphore.release();  // 回退全局 Semaphore
            throw new AgentLimitExceededException(
                    "Session %s concurrent agent limit reached (%d/%d)".formatted(
                            sessionId, currentSessionCount - 1, MAX_CONCURRENT_AGENTS_PER_SESSION));
        }
        int current = activeAgentCount.incrementAndGet();
        log.debug("Agent slot acquired: {} (active: {}, session: {})", agentId, current, currentSessionCount);
        return new AgentSlot(agentId, globalSemaphore, activeAgentCount, sessionId, sessionAgentCounts);
    }

    /** 获取当前活跃代理数 */
    public int getActiveCount() {
        return activeAgentCount.get();
    }

    /** 获取指定会话的活跃代理数 */
    public int getSessionActiveCount(String sessionId) {
        AtomicInteger count = sessionAgentCounts.get(sessionId);
        return count != null ? count.get() : 0;
    }

    /**
     * 自动释放槽位的 RAII 包装 — try-with-resources 自动释放。
     * <p>
     * v1.49.0: 新增 sessionId + sessionAgentCounts 释放。
     */
    public record AgentSlot(
            String agentId,
            Semaphore semaphore,
            AtomicInteger counter,
            String sessionId,
            ConcurrentHashMap<String, AtomicInteger> sessionCounts
    ) implements AutoCloseable {
        @Override
        public void close() {
            counter.decrementAndGet();
            semaphore.release();
            // 释放会话级计数
            AtomicInteger sessionCount = sessionCounts.get(sessionId);
            if (sessionCount != null) {
                sessionCount.decrementAndGet();
            }
        }
    }
}
