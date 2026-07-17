package com.aicodeassistant.engine;

import com.aicodeassistant.llm.ApiCircuitBreaker;
import com.aicodeassistant.llm.LlmApiException;
import com.aicodeassistant.llm.ModelAwareRetryPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApiRetryCancellationTest {
    @Test
    void cancellationInterruptsRetryBackoffAndPreventsAnotherHttpAttempt() throws Exception {
        ModelTierService tiers = mock(ModelTierService.class);
        ModelAwareRetryPolicy policy = mock(ModelAwareRetryPolicy.class);
        ApiCircuitBreaker circuit = mock(ApiCircuitBreaker.class);
        when(circuit.allowRequest()).thenReturn(true);
        when(policy.calculateDelay(anyString(), anyInt())).thenReturn(Duration.ofSeconds(30));
        ApiRetryService retries = new ApiRetryService(tiers, policy, circuit);
        AbortContext cancellation = new AbortContext();
        CountDownLatch attempted = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();

        CompletableFuture<Void> execution = CompletableFuture.runAsync(() ->
                retries.executeWithRetry(() -> {
                    calls.incrementAndGet();
                    attempted.countDown();
                    throw new LlmApiException("temporary", true, 503, "api_error", 0);
                }, "sdk", "model", cancellation));

        assertTrue(attempted.await(1, TimeUnit.SECONDS));
        cancellation.abort(AbortReason.USER_INTERRUPT);
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> execution.get(1, TimeUnit.SECONDS));
        assertInstanceOf(LlmApiException.class, failure.getCause());
        assertEquals("LLM_CALL_CANCELLED", failure.getCause().getMessage());
        assertEquals(1, calls.get());
    }
}
