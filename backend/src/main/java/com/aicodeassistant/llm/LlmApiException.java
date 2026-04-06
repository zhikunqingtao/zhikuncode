package com.aicodeassistant.llm;

/**
 * LLM API 异常 — 区分可重试和不可重试错误。
 * <p>
 * 包含 HTTP 状态码、错误类型、retry-after 等重试相关信息。
 *
 * @see <a href="SPEC §3.1.1b">withRetry 重试机制</a>
 */
public class LlmApiException extends RuntimeException {

    private final boolean retryable;
    private final int httpStatus;
    private final String errorType;
    private final long retryAfterMs;

    public LlmApiException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
        this.httpStatus = 0;
        this.errorType = null;
        this.retryAfterMs = 0;
    }

    public LlmApiException(String message, boolean retryable, int httpStatus) {
        super(message);
        this.retryable = retryable;
        this.httpStatus = httpStatus;
        this.errorType = null;
        this.retryAfterMs = 0;
    }

    public LlmApiException(String message, boolean retryable, int httpStatus,
                            String errorType, long retryAfterMs) {
        super(message);
        this.retryable = retryable;
        this.httpStatus = httpStatus;
        this.errorType = errorType;
        this.retryAfterMs = retryAfterMs;
    }

    public LlmApiException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
        this.httpStatus = 0;
        this.errorType = null;
        this.retryAfterMs = 0;
    }

    public boolean isRetryable() { return retryable; }
    public int getHttpStatus() { return httpStatus; }
    public int getStatusCode() { return httpStatus; }
    public String getErrorType() { return errorType; }
    public long getRetryAfterMs() { return retryAfterMs; }
}
