package com.aicodeassistant.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * API 熔断器 — 防止持续调用已故障的 API 端点。
 * <p>
 * 状态机：
 * <ul>
 *   <li>CLOSED（正常）→ 连续失败 >= 3 → OPEN</li>
 *   <li>OPEN（熔断）→ 等待 60s → HALF_OPEN</li>
 *   <li>HALF_OPEN → 试探请求成功 → CLOSED</li>
 *   <li>HALF_OPEN → 试探请求失败 → OPEN</li>
 * </ul>
 * <p>
 * 【新增】Phase 2 错误恢复框架组件。
 */
@Component
public class ApiCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(ApiCircuitBreaker.class);

    // ==================== 状态枚举 ====================

    public enum State {
        /** 正常状态 — 所有请求通过 */
        CLOSED,
        /** 熔断状态 — 拒绝所有请求 */
        OPEN,
        /** 半开状态 — 允许试探请求 */
        HALF_OPEN
    }

    // ==================== 配置常量（新增） ====================

    /** 连续失败触发熔断的阈值 */
    private static final int FAILURE_THRESHOLD = 3;

    /** 熔断恢复超时（从 OPEN 进入 HALF_OPEN 的等待时间） */
    private static final Duration RECOVERY_TIMEOUT = Duration.ofSeconds(60);

    // ==================== 状态 ====================

    private volatile State state = State.CLOSED;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile Instant lastFailureTime;

    // ==================== 核心 API ====================

    /**
     * 检查是否允许请求通过。
     * <p>
     * - CLOSED: 始终允许
     * - OPEN: 检查是否超过恢复超时，超时则转为 HALF_OPEN 并允许
     * - HALF_OPEN: 允许（试探请求）
     *
     * @return true 表示允许请求通过
     */
    public boolean allowRequest() {
        switch (state) {
            case CLOSED:
                return true;
            case OPEN:
                // 检查是否到达恢复时间
                if (lastFailureTime != null
                        && Instant.now().isAfter(lastFailureTime.plus(RECOVERY_TIMEOUT))) {
                    state = State.HALF_OPEN;
                    log.info("Circuit breaker: OPEN → HALF_OPEN (recovery timeout elapsed)");
                    return true;
                }
                log.debug("Circuit breaker: request blocked (state=OPEN, "
                        + "remaining={}s)", remainingOpenTime());
                return false;
            case HALF_OPEN:
                return true;
            default:
                return true;
        }
    }

    /**
     * 记录请求成功。
     * <p>
     * - HALF_OPEN → CLOSED（恢复正常）
     * - 重置连续失败计数
     */
    public void recordSuccess() {
        if (state == State.HALF_OPEN) {
            state = State.CLOSED;
            log.info("Circuit breaker: HALF_OPEN → CLOSED (probe succeeded)");
        }
        consecutiveFailures.set(0);
    }

    /**
     * 记录请求失败。
     * <p>
     * - HALF_OPEN → OPEN（试探失败，重新熔断）
     * - CLOSED: 累加失败计数，达到阈值时 → OPEN
     */
    public void recordFailure() {
        lastFailureTime = Instant.now();

        if (state == State.HALF_OPEN) {
            state = State.OPEN;
            log.info("Circuit breaker: HALF_OPEN → OPEN (probe failed)");
            return;
        }

        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= FAILURE_THRESHOLD && state == State.CLOSED) {
            state = State.OPEN;
            log.warn("Circuit breaker: CLOSED → OPEN (consecutive failures: {})", failures);
        }
    }

    /**
     * 获取当前状态。
     */
    public State getState() {
        return state;
    }

    /**
     * 获取连续失败次数。
     */
    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    /**
     * 重置熔断器（用于测试或手动恢复）。
     */
    public void reset() {
        state = State.CLOSED;
        consecutiveFailures.set(0);
        lastFailureTime = null;
        log.info("Circuit breaker: manually reset to CLOSED");
    }

    /**
     * 计算 OPEN 状态剩余时间（秒）。
     */
    private long remainingOpenTime() {
        if (lastFailureTime == null) return 0;
        long elapsed = Duration.between(lastFailureTime, Instant.now()).getSeconds();
        return Math.max(0, RECOVERY_TIMEOUT.getSeconds() - elapsed);
    }
}
