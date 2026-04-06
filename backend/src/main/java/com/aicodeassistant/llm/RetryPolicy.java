package com.aicodeassistant.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * withRetry 重试策略 — 指数退避 + jitter。
 * <p>
 * 对照源码 withRetry.ts。
 * <p>
 * 核心参数:
 * <ul>
 *   <li>BASE_DELAY_MS = 500ms</li>
 *   <li>maxRetries = 10 (默认)</li>
 *   <li>普通最大退避 = 32s</li>
 *   <li>抖动比例 = 0-25%</li>
 * </ul>
 * <p>
 * 可重试: 429, 529, 408, 5xx, ECONNRESET/EPIPE
 * 不可重试: 400, 401, 403, 404
 *
 * @see <a href="SPEC §3.1.1b">withRetry 重试机制</a>
 */
@Component
public class RetryPolicy {

    private static final Logger log = LoggerFactory.getLogger(RetryPolicy.class);

    private static final long BASE_DELAY_MS = 500;
    private static final int DEFAULT_MAX_RETRIES = 10;
    private static final long MAX_DELAY_MS = 32_000;
    private static final double JITTER_FACTOR = 0.25;

    /**
     * 执行带重试的操作 — 阻塞当前线程（适用于 Virtual Thread）。
     *
     * @param action     要重试的操作
     * @param maxRetries 最大重试次数
     * @param <T>        返回类型
     * @return 操作结果
     * @throws LlmApiException 所有重试耗尽后抛出最后一次异常
     */
    public <T> T withRetry(Supplier<T> action, int maxRetries) {
        LlmApiException lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return action.get();
            } catch (LlmApiException e) {
                lastException = e;

                if (!e.isRetryable()) {
                    throw e;
                }

                if (attempt >= maxRetries) {
                    break;
                }

                long delay = calculateDelay(attempt);
                log.warn("LLM API call failed (attempt {}/{}), retrying in {}ms: {}",
                        attempt + 1, maxRetries + 1, delay, e.getMessage());

                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new LlmApiException("Retry interrupted", e, false);
                }
            }
        }

        throw lastException != null ? lastException
                : new LlmApiException("Max retries exceeded", true);
    }

    /**
     * 执行带重试的操作（使用默认重试次数）。
     */
    public <T> T withRetry(Supplier<T> action) {
        return withRetry(action, DEFAULT_MAX_RETRIES);
    }

    /**
     * 执行带重试的无返回值操作。
     */
    public void withRetryVoid(Runnable action, int maxRetries) {
        withRetry(() -> {
            action.run();
            return null;
        }, maxRetries);
    }

    /**
     * 计算退避延迟 = min(BASE_DELAY × 2^attempt, MAX_DELAY) + jitter。
     */
    long calculateDelay(int attempt) {
        long baseDelay = Math.min(BASE_DELAY_MS * (1L << attempt), MAX_DELAY_MS);
        long jitter = (long) (baseDelay * JITTER_FACTOR * ThreadLocalRandom.current().nextDouble());
        return baseDelay + jitter;
    }
}
