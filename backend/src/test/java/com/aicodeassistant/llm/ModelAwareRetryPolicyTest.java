package com.aicodeassistant.llm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ModelAwareRetryPolicy 单元测试 — TC-MODEL-012 ~ TC-MODEL-016。
 * <p>
 * 纯逻辑测试，不依赖 Spring Context。
 * Jitter 测试使用统计范围断言。
 */
class ModelAwareRetryPolicyTest {

    private ModelAwareRetryPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new ModelAwareRetryPolicy();
    }

    // ==================== TC-MODEL-012 ====================

    @Test
    @DisplayName("TC-MODEL-012: 基础延迟 500ms × 2^attempt 指数退避")
    void tc012_exponentialBackoffWithBaseDelay() {
        // DEFAULT baseDelay = 500ms
        // attempt 0: base=500, range [500, 625)
        // attempt 1: base=1000, range [1000, 1250)
        // attempt 2: base=2000, range [2000, 2500)
        // attempt 3: base=4000, range [4000, 5000)
        // 源码 jitter 为正向: delay + delay*0.25*random[0,1)

        long[] expectedBase = {500, 1000, 2000, 4000};
        for (int attempt = 0; attempt < expectedBase.length; attempt++) {
            Duration delay = policy.calculateDelay("unknown-default-model", attempt);
            long delayMs = delay.toMillis();
            long base = expectedBase[attempt];
            long maxWithJitter = (long) (base * 1.25);

            assertThat(delayMs)
                    .as("attempt %d: expected [%d, %d], got %d", attempt, base, maxWithJitter, delayMs)
                    .isBetween(base, maxWithJitter);
        }
    }

    // ==================== TC-MODEL-013 ====================

    @Test
    @DisplayName("TC-MODEL-013: 最大延迟不超过 30s + 25% jitter")
    void tc013_maxDelayCapAt30sWithJitter() {
        // attempt=100 → 指数值远超上限，被 cap 到 MAX_DELAY_MS=30_000
        // 加上 jitter 后最大值 = 30_000 * 1.25 = 37_500
        Duration delay = policy.calculateDelay("unknown-default-model", 100);
        long delayMs = delay.toMillis();

        assertThat(delayMs)
                .as("delay should be between 30000 and 37500ms, got %d", delayMs)
                .isBetween(30_000L, 37_500L);
    }

    // ==================== TC-MODEL-014 ====================

    @Test
    @DisplayName("TC-MODEL-014: Jitter 范围 [base, base*1.25)")
    void tc014_jitterRangeVerification() {
        // 对同一 attempt 运行 100 次，验证所有值在 jitter 允许范围内
        // attempt=2, DEFAULT base=500ms → base delay = 500*4=2000ms
        // jitter 范围: [2000, 2500)
        long baseDelay = 2000;
        long maxJittered = (long) (baseDelay * 1.25);

        for (int i = 0; i < 100; i++) {
            Duration delay = policy.calculateDelay("unknown-default-model", 2);
            long delayMs = delay.toMillis();

            assertThat(delayMs)
                    .as("iteration %d: expected [%d, %d], got %d", i, baseDelay, maxJittered, delayMs)
                    .isGreaterThanOrEqualTo(baseDelay)
                    .isLessThanOrEqualTo(maxJittered);
        }
    }

    // ==================== TC-MODEL-015 ====================

    @Test
    @DisplayName("TC-MODEL-015: 未知模型默认最大重试 10 次")
    void tc015_unknownModelDefaultMaxRetries() {
        ModelAwareRetryPolicy.RetryConfig config = policy.getRetryConfig("unknown-model-xyz");

        assertThat(config).isNotNull();
        assertThat(config.maxRetries()).isEqualTo(10);
        assertThat(config.baseDelay()).isEqualTo(Duration.ofMillis(500));
        assertThat(config.respectRetryAfter()).isTrue();
    }

    // ==================== TC-MODEL-016 ====================

    @Test
    @DisplayName("TC-MODEL-016: Claude 模型最大重试 5 次")
    void tc016_claudeModelMaxRetries() {
        // 模糊匹配: modelId 包含 "claude"
        ModelAwareRetryPolicy.RetryConfig config = policy.getRetryConfig("claude-sonnet-4-20250514");

        assertThat(config).isNotNull();
        assertThat(config.maxRetries()).isEqualTo(5);
        assertThat(config.baseDelay()).isEqualTo(Duration.ofSeconds(60));
        assertThat(config.respectRetryAfter()).isTrue();
    }
}
