package com.aicodeassistant.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * LLM 错误分类器 — 将 LlmApiException 归类为 5 种标准错误类型。
 * <p>
 * 对齐 Claude Code errorClassifier.ts:
 * <ul>
 *   <li>OVERLOADED (529): 服务过载</li>
 *   <li>RATE_LIMITED (429): 限流</li>
 *   <li>PROMPT_TOO_LONG (413): 输入超长</li>
 *   <li>AUTH_FAILED (401/403): 认证失败</li>
 *   <li>NETWORK_TIMEOUT: 网络超时</li>
 *   <li>UNKNOWN: 其他未知错误</li>
 * </ul>
 */
@Component
public class LlmErrorClassifier {

    private static final Logger log = LoggerFactory.getLogger(LlmErrorClassifier.class);

    public enum ErrorCategory {
        OVERLOADED,
        RATE_LIMITED,
        PROMPT_TOO_LONG,
        AUTH_FAILED,
        NETWORK_TIMEOUT,
        UNKNOWN
    }

    /**
     * 分类错误 — 基于 HTTP 状态码 + 错误消息。
     */
    public ErrorCategory classify(LlmApiException e) {
        int status = e.getHttpStatus();

        // HTTP 状态码分类
        if (status == 529 || status == 503) {
            return ErrorCategory.OVERLOADED;
        }
        if (status == 429) {
            return ErrorCategory.RATE_LIMITED;
        }
        if (status == 413) {
            return ErrorCategory.PROMPT_TOO_LONG;
        }
        if (status == 401 || status == 403) {
            return ErrorCategory.AUTH_FAILED;
        }

        // 消息匹配分类
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        if (msg.contains("overloaded") || msg.contains("overload")) {
            return ErrorCategory.OVERLOADED;
        }
        if (msg.contains("rate") && msg.contains("limit")) {
            return ErrorCategory.RATE_LIMITED;
        }
        if (msg.contains("prompt") && (msg.contains("too long") || msg.contains("too large"))) {
            return ErrorCategory.PROMPT_TOO_LONG;
        }
        if (msg.contains("context_length_exceeded") || msg.contains("maximum context length")) {
            return ErrorCategory.PROMPT_TOO_LONG;
        }
        if (msg.contains("timeout") || msg.contains("timed out")) {
            return ErrorCategory.NETWORK_TIMEOUT;
        }
        if (e.getCause() instanceof java.net.SocketTimeoutException) {
            return ErrorCategory.NETWORK_TIMEOUT;
        }

        return ErrorCategory.UNKNOWN;
    }

    /**
     * 根据错误类型获取建议的重试延迟（毫秒）。
     */
    public long getRetryDelayMs(ErrorCategory category, int attempt) {
        long baseDelay = switch (category) {
            case OVERLOADED -> 10_000L;  // 过载: 10s 基准
            case RATE_LIMITED -> 5_000L; // 限流: 5s 基准
            case NETWORK_TIMEOUT -> 2_000L; // 超时: 2s 基准
            case PROMPT_TOO_LONG, AUTH_FAILED -> 0L; // 不可重试
            case UNKNOWN -> 3_000L;
        };

        if (baseDelay == 0) return 0;

        // 指数退避 + jitter
        long delay = baseDelay * (1L << Math.min(attempt, 4));
        long jitter = (long) (delay * 0.2 * Math.random());
        return delay + jitter;
    }

    /**
     * 是否可重试。
     */
    public boolean isRetryable(ErrorCategory category) {
        return switch (category) {
            case OVERLOADED, RATE_LIMITED, NETWORK_TIMEOUT -> true;
            case PROMPT_TOO_LONG, AUTH_FAILED, UNKNOWN -> false;
        };
    }

    /**
     * 获取最大重试次数。
     */
    public int getMaxRetries(ErrorCategory category) {
        return switch (category) {
            case OVERLOADED -> 3;      // 过载: 最多3次 (熔断)
            case RATE_LIMITED -> 5;    // 限流: 最多5次
            case NETWORK_TIMEOUT -> 3; // 超时: 最多3次
            case PROMPT_TOO_LONG, AUTH_FAILED, UNKNOWN -> 0;
        };
    }
}
