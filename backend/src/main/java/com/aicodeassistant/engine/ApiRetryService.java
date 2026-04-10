package com.aicodeassistant.engine;

import com.aicodeassistant.llm.LlmApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.function.Supplier;

/**
 * API 重试服务 — 完整的 API 重试机制，独立于查询循环。
 * <p>
 * 对照源码 withRetry.ts，包含:
 * 1. 标准指数退避重试 (overloaded/rate_limit/api_error)
 * 2. 529 源分类重试 (仅特定查询源重试 529 错误)
 * 3. 模型降级 (FallbackTriggeredError)
 *
 * @see <a href="SPEC §3.1.5">CompactService 压缩算法 - API 层重试</a>
 */
@Service
public class ApiRetryService {

    private static final Logger log = LoggerFactory.getLogger(ApiRetryService.class);

    // ==================== 核心常量 ====================
    private static final int DEFAULT_MAX_RETRIES = 10;
    private static final int MAX_529_RETRIES = 3;
    private static final long BASE_DELAY_MS = 500;
    private static final long MAX_DELAY_MS = 30_000;
    private static final double BACKOFF_MULTIPLIER = 2.0;

    // ==================== 依赖注入 ====================
    private final ModelTierService modelTierService;

    public ApiRetryService(ModelTierService modelTierService) {
        this.modelTierService = modelTierService;
    }

    // ==================== 529 源分类 ====================
    private static final Set<String> FOREGROUND_529_RETRY_SOURCES = Set.of(
            "repl_main_thread",
            "sdk",
            "agent:custom",
            "agent:default",
            "agent:builtin",
            "compact",
            "hook_agent",
            "hook_prompt",
            "verification_agent",
            "side_question",
            "auto_mode"
    );

    private static final Set<String> RETRYABLE_ERROR_TYPES = Set.of(
            "overloaded_error", "rate_limit_error", "api_error"
    );

    /**
     * 带完整重试策略的 API 调用。
     *
     * @param operation    API 调用操作
     * @param querySource  查询源标识（用于 529 源分类）
     * @param currentModel 当前使用的模型标识
     * @return API 调用结果
     */
    public <T> T executeWithRetry(Supplier<T> operation, String querySource,
                                   String currentModel) {
        int attempt = 0;
        int retries529 = 0;

        while (true) {
            try {
                T result = operation.get();
                // ★ 成功 → 报告给 ModelTierService
                modelTierService.reportSuccess(currentModel);
                return result;
            } catch (LlmApiException e) {
                attempt++;

                // 1. 检查是否为 529 错误 (容量超限)
                if (e.getStatusCode() == 529) {
                    // ★ 触发模型冷却
                    modelTierService.triggerCooldown(
                            currentModel, e.getRetryAfterMs(),
                            "529_overloaded: " + e.getMessage());

                    if (!shouldRetry529(querySource, retries529)) {
                        throw e;
                    }
                    retries529++;
                    log.warn("529 容量超限 (attempt {}/{}), 源: {}, 等待重试...",
                            retries529, MAX_529_RETRIES, querySource);
                }
                // 2. 检查是否为可重试的标准错误
                else if (!isRetryableError(e)) {
                    throw e;
                }

                // 3. 超过最大重试次数
                if (attempt >= DEFAULT_MAX_RETRIES) {
                    log.error("API 调用超过最大重试次数 ({}), 放弃", DEFAULT_MAX_RETRIES);
                    throw e;
                }

                // 4. 计算延迟并等待
                long delay = calculateDelay(attempt, e);
                log.warn("API 调用失败 (attempt {}/{}), {} 后重试: {}",
                        attempt, DEFAULT_MAX_RETRIES, delay + "ms", e.getMessage());

                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("API 重试被中断", ie);
                }
            }
        }
    }

    /**
     * 529 源分类: 仅前台查询源重试 529 错误。
     */
    private boolean shouldRetry529(String querySource, int retries529) {
        if (retries529 >= MAX_529_RETRIES) return false;
        if (querySource == null) return false;
        return FOREGROUND_529_RETRY_SOURCES.stream()
                .anyMatch(pattern -> querySource.equals(pattern)
                        || querySource.startsWith(pattern + ":"));
    }

    /**
     * 检查是否为可重试的错误。
     */
    private boolean isRetryableError(LlmApiException e) {
        if (e.getStatusCode() >= 500) return true;
        String errorType = e.getErrorType();
        return errorType != null && RETRYABLE_ERROR_TYPES.contains(errorType);
    }

    /**
     * 计算重试延迟 — 指数退避 + 随机抖动。
     */
    private long calculateDelay(int attempt, LlmApiException e) {
        // 优先使用服务端 retry-after
        if (e.getRetryAfterMs() > 0) {
            return Math.min(e.getRetryAfterMs(), MAX_DELAY_MS);
        }

        // 指数退避
        long baseDelay = (long) (BASE_DELAY_MS * Math.pow(BACKOFF_MULTIPLIER, attempt - 1));
        long delay = Math.min(baseDelay, MAX_DELAY_MS);
        double jitter = 0.5 + Math.random() * 0.5;
        return (long) (delay * jitter);
    }
}
