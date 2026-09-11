package com.aicodeassistant.engine;

import com.aicodeassistant.llm.ApiCircuitBreaker;
import com.aicodeassistant.llm.LlmApiException;
import com.aicodeassistant.llm.ModelAwareRetryPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 重试层异常分类测试 — 验证按异常类型区分重试预算：
 * ConnectException → 小预算（2 次重试、固定短间隔）；
 * 400/401/402/403 确定性错误 → 0 次重试；
 * 429/5xx → 保持现有退避策略与预算不变（回归）。
 */
class ApiRetryServiceRetryClassificationTest {

    private ModelTierService tiers;
    private ModelAwareRetryPolicy policy;
    private ApiCircuitBreaker circuit;
    private ApiRetryService retries;

    @BeforeEach
    void setUp() {
        tiers = mock(ModelTierService.class);
        policy = mock(ModelAwareRetryPolicy.class);
        circuit = mock(ApiCircuitBreaker.class);
        when(circuit.allowRequest()).thenReturn(true);
        retries = new ApiRetryService(tiers, policy, circuit);
    }

    @Test
    @DisplayName("ConnectException → 最多 2 次重试（共 3 次尝试）、固定短间隔，总耗时 ≤10s")
    void connectExceptionUsesSmallRetryBudget() {
        AtomicInteger calls = new AtomicInteger();
        long startedNanos = System.nanoTime();

        LlmApiException failure = assertThrows(LlmApiException.class,
                () -> retries.executeWithRetry(() -> {
                    calls.incrementAndGet();
                    throw new LlmApiException(
                            "OpenAI stream error: Failed to connect to /127.0.0.1:17890",
                            new ConnectException("Failed to connect to /127.0.0.1:17890"),
                            true);
                }, "sdk", "model"));

        long elapsedMs = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        assertEquals(3, calls.get(), "首次 + 2 次重试后必须失败");
        assertTrue(failure.getMessage().contains("127.0.0.1:17890"));
        assertTrue(elapsedMs <= 10_000, "总耗时必须 ≤10s，实际 " + elapsedMs + "ms");
        verify(circuit, times(1)).recordFailure();
        // 固定短间隔，不走模型感知指数退避
        verify(policy, never()).calculateDelay(anyString(), anyInt());
    }

    @Test
    @DisplayName("402 确定性错误 → 0 次重试立即失败（即使 retryable 被误标为 true），不触发熔断器")
    void deterministic402FailsImmediately() {
        AtomicInteger calls = new AtomicInteger();

        LlmApiException failure = assertThrows(LlmApiException.class,
                () -> retries.executeWithRetry(() -> {
                    calls.incrementAndGet();
                    throw new LlmApiException("Insufficient Balance", true, 402);
                }, "sdk", "model"));

        assertEquals(402, failure.getStatusCode());
        assertEquals(1, calls.get(), "402 不允许任何重试");
        // 确定性 4xx 是请求级错误（余额不足），不是服务可用性问题，
        // 不得计入跨模型共享的熔断器，避免欠费时误伤健康模型
        verify(circuit, never()).recordFailure();
        verify(policy, never()).calculateDelay(anyString(), anyInt());
    }

    @Test
    @DisplayName("400/401/403 确定性错误 → 0 次重试立即失败")
    void deterministicClientErrorsFailImmediately() {
        for (int status : new int[]{400, 401, 403}) {
            AtomicInteger calls = new AtomicInteger();

            LlmApiException failure = assertThrows(LlmApiException.class,
                    () -> retries.executeWithRetry(() -> {
                        calls.incrementAndGet();
                        throw new LlmApiException("client error", true, status);
                    }, "sdk", "model"));

            assertEquals(status, failure.getStatusCode());
            assertEquals(1, calls.get(), "HTTP " + status + " 不允许任何重试");
        }
        // 确定性 4xx 同样不触发熔断器（请求级错误，非可用性问题）
        verify(circuit, never()).recordFailure();
        verify(policy, never()).calculateDelay(anyString(), anyInt());
    }

    @Test
    @DisplayName("回归：429 保持现有预算（10 次尝试）与模型感知退避策略")
    void rateLimited429KeepsExistingBackoffBudget() {
        when(policy.calculateDelay(anyString(), anyInt())).thenReturn(Duration.ofMillis(1));
        AtomicInteger calls = new AtomicInteger();

        LlmApiException failure = assertThrows(LlmApiException.class,
                () -> retries.executeWithRetry(() -> {
                    calls.incrementAndGet();
                    throw new LlmApiException("Too Many Requests", true, 429,
                            "rate_limit_error", 0);
                }, "sdk", "model"));

        assertEquals(429, failure.getStatusCode());
        assertEquals(10, calls.get(), "429 预算必须保持 DEFAULT_MAX_RETRIES=10 不变");
        verify(policy, times(9)).calculateDelay(anyString(), anyInt());
        verify(circuit, times(1)).recordFailure();
    }

    @Test
    @DisplayName("回归：5xx 保持现有预算（10 次尝试）与模型感知退避策略")
    void serverError5xxKeepsExistingBackoffBudget() {
        when(policy.calculateDelay(anyString(), anyInt())).thenReturn(Duration.ofMillis(1));
        AtomicInteger calls = new AtomicInteger();

        LlmApiException failure = assertThrows(LlmApiException.class,
                () -> retries.executeWithRetry(() -> {
                    calls.incrementAndGet();
                    throw new LlmApiException("Internal Server Error", true, 503,
                            "api_error", 0);
                }, "sdk", "model"));

        assertEquals(503, failure.getStatusCode());
        assertEquals(10, calls.get(), "5xx 预算必须保持 DEFAULT_MAX_RETRIES=10 不变");
        verify(policy, times(9)).calculateDelay(anyString(), anyInt());
        verify(circuit, times(1)).recordFailure();
    }
}
