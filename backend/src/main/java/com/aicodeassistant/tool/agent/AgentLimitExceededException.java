package com.aicodeassistant.tool.agent;

/**
 * 代理并发限制异常 — 超出全局/会话/嵌套深度限制时抛出。
 * <p>
 * 由 {@link AgentConcurrencyController#acquireSlot} 在以下情况抛出:
 * <ul>
 *   <li>全局并发代理数超过 MAX_CONCURRENT_AGENTS (30)</li>
 *   <li>单会话并发代理数超过 MAX_CONCURRENT_AGENTS_PER_SESSION (10)</li>
 *   <li>代理嵌套深度超过 MAX_AGENT_NESTING_DEPTH (3)</li>
 * </ul>
 *
 * @see AgentConcurrencyController
 * @see <a href="SPEC §4.1.1c">并发代理硬限制</a>
 */
public class AgentLimitExceededException extends RuntimeException {

    public AgentLimitExceededException(String message) {
        super(message);
    }

    public AgentLimitExceededException(String message, Throwable cause) {
        super(message, cause);
    }
}
