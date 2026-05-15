package com.aicodeassistant.llm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ApiCircuitBreaker 单元测试 — TC-MODEL-007 ~ TC-MODEL-011。
 * <p>
 * 纯逻辑测试，不依赖 Spring Context。
 * TC-MODEL-008 使用反射修改 lastFailureTime 模拟超时。
 */
class ApiCircuitBreakerTest {

    private ApiCircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        breaker = new ApiCircuitBreaker();
        breaker.reset();
    }

    // ==================== TC-MODEL-007 ====================

    @Test
    @DisplayName("TC-MODEL-007: CLOSED → 3 次连续失败 → OPEN")
    void tc007_threeConsecutiveFailuresTriggersOpen() {
        assertThat(breaker.getState()).isEqualTo(ApiCircuitBreaker.State.CLOSED);
        assertThat(breaker.allowRequest()).isTrue();

        breaker.recordFailure();
        assertThat(breaker.getConsecutiveFailures()).isEqualTo(1);
        assertThat(breaker.getState()).isEqualTo(ApiCircuitBreaker.State.CLOSED);

        breaker.recordFailure();
        assertThat(breaker.getConsecutiveFailures()).isEqualTo(2);
        assertThat(breaker.getState()).isEqualTo(ApiCircuitBreaker.State.CLOSED);

        breaker.recordFailure();
        assertThat(breaker.getConsecutiveFailures()).isEqualTo(3);
        assertThat(breaker.getState()).isEqualTo(ApiCircuitBreaker.State.OPEN);
        assertThat(breaker.allowRequest()).isFalse();
    }

    // ==================== TC-MODEL-008 ====================

    @Test
    @DisplayName("TC-MODEL-008: OPEN → 60s 后 → HALF_OPEN")
    void tc008_openTransitionsToHalfOpenAfterTimeout() throws Exception {
        // 先触发 OPEN
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();
        assertThat(breaker.getState()).isEqualTo(ApiCircuitBreaker.State.OPEN);

        // 通过反射将 lastFailureTime 设置为 61 秒前，模拟超时
        Field lastFailureTimeField = ApiCircuitBreaker.class.getDeclaredField("lastFailureTime");
        lastFailureTimeField.setAccessible(true);
        lastFailureTimeField.set(breaker, Instant.now().minusSeconds(61));

        // allowRequest() 应检测到超时并转换为 HALF_OPEN
        assertThat(breaker.allowRequest()).isTrue();
        assertThat(breaker.getState()).isEqualTo(ApiCircuitBreaker.State.HALF_OPEN);
    }

    // ==================== TC-MODEL-009 ====================

    @Test
    @DisplayName("TC-MODEL-009: HALF_OPEN → 试探成功 → CLOSED")
    void tc009_halfOpenSuccessTransitionsToClosed() throws Exception {
        // 触发 OPEN 并模拟超时进入 HALF_OPEN
        triggerHalfOpen();
        assertThat(breaker.getState()).isEqualTo(ApiCircuitBreaker.State.HALF_OPEN);

        // 试探成功
        breaker.recordSuccess();
        assertThat(breaker.getState()).isEqualTo(ApiCircuitBreaker.State.CLOSED);
        assertThat(breaker.getConsecutiveFailures()).isEqualTo(0);
        assertThat(breaker.allowRequest()).isTrue();
    }

    // ==================== TC-MODEL-010 ====================

    @Test
    @DisplayName("TC-MODEL-010: HALF_OPEN → 试探失败 → OPEN")
    void tc010_halfOpenFailureTransitionsToOpen() throws Exception {
        // 触发 OPEN 并模拟超时进入 HALF_OPEN
        triggerHalfOpen();
        assertThat(breaker.getState()).isEqualTo(ApiCircuitBreaker.State.HALF_OPEN);

        // 试探失败
        breaker.recordFailure();
        assertThat(breaker.getState()).isEqualTo(ApiCircuitBreaker.State.OPEN);
        assertThat(breaker.allowRequest()).isFalse();
    }

    // ==================== TC-MODEL-011 ====================

    @Test
    @DisplayName("TC-MODEL-011: CLOSED 状态成功调用重置失败计数")
    void tc011_successResetsConsecutiveFailureCount() {
        // 2 次失败
        breaker.recordFailure();
        breaker.recordFailure();
        assertThat(breaker.getConsecutiveFailures()).isEqualTo(2);
        assertThat(breaker.getState()).isEqualTo(ApiCircuitBreaker.State.CLOSED);

        // 1 次成功 → 失败计数重置为 0
        breaker.recordSuccess();
        assertThat(breaker.getConsecutiveFailures()).isEqualTo(0);

        // 再 2 次失败 → 仍然 CLOSED（只有 2 次）
        breaker.recordFailure();
        breaker.recordFailure();
        assertThat(breaker.getConsecutiveFailures()).isEqualTo(2);
        assertThat(breaker.getState()).isEqualTo(ApiCircuitBreaker.State.CLOSED);

        // 第 3 次失败（从重置后算第 3 次）→ OPEN
        breaker.recordFailure();
        assertThat(breaker.getConsecutiveFailures()).isEqualTo(3);
        assertThat(breaker.getState()).isEqualTo(ApiCircuitBreaker.State.OPEN);
    }

    // ==================== 辅助方法 ====================

    /**
     * 触发 OPEN 并通过反射模拟超时进入 HALF_OPEN。
     */
    private void triggerHalfOpen() throws Exception {
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();
        assertThat(breaker.getState()).isEqualTo(ApiCircuitBreaker.State.OPEN);

        // 反射修改 lastFailureTime 为 61 秒前
        Field lastFailureTimeField = ApiCircuitBreaker.class.getDeclaredField("lastFailureTime");
        lastFailureTimeField.setAccessible(true);
        lastFailureTimeField.set(breaker, Instant.now().minusSeconds(61));

        // 触发状态转换
        breaker.allowRequest();
        assertThat(breaker.getState()).isEqualTo(ApiCircuitBreaker.State.HALF_OPEN);
    }
}
