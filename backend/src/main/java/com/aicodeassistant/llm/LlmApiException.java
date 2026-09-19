package com.aicodeassistant.llm;

/**
 * LLM API 异常 — 区分可重试和不可重试错误。
 * <p>
 * 包含 HTTP 状态码、错误类型、retry-after 等重试相关信息。
 *
 */
public class LlmApiException extends RuntimeException {

    private final boolean retryable;
    private final int httpStatus;
    private final String errorType;
    private final long retryAfterMs;
    private final boolean retrySuppressed;

    public LlmApiException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
        this.httpStatus = 0;
        this.errorType = null;
        this.retryAfterMs = 0;
        this.retrySuppressed = false;
    }

    public LlmApiException(String message, boolean retryable, int httpStatus) {
        super(message);
        this.retryable = retryable;
        this.httpStatus = httpStatus;
        this.errorType = null;
        this.retryAfterMs = 0;
        this.retrySuppressed = false;
    }

    public LlmApiException(String message, boolean retryable, int httpStatus,
                            String errorType, long retryAfterMs) {
        super(message);
        this.retryable = retryable;
        this.httpStatus = httpStatus;
        this.errorType = errorType;
        this.retryAfterMs = retryAfterMs;
        this.retrySuppressed = false;
    }

    public LlmApiException(String message, Throwable cause, boolean retryable) {
        super(message, cause);
        this.retryable = retryable;
        this.httpStatus = 0;
        this.errorType = null;
        this.retryAfterMs = 0;
        this.retrySuppressed = false;
    }

    public LlmApiException(String message, Throwable cause, boolean retryable,
                           int httpStatus, String errorType, long retryAfterMs) {
        super(message, cause);
        this.retryable = retryable;
        this.httpStatus = httpStatus;
        this.errorType = errorType;
        this.retryAfterMs = retryAfterMs;
        this.retrySuppressed = false;
    }

    private LlmApiException(String message, Throwable cause, boolean retryable,
                            int httpStatus, String errorType, long retryAfterMs,
                            boolean retrySuppressed) {
        super(message, cause);
        this.retryable = retryable;
        this.httpStatus = httpStatus;
        this.errorType = errorType;
        this.retryAfterMs = retryAfterMs;
        this.retrySuppressed = retrySuppressed;
    }

    public LlmApiException withRetryable(boolean value) {
        return new LlmApiException(getMessage(), this, value,
                httpStatus, errorType, retryAfterMs, !value);
    }

    public boolean isRetryable() { return retryable; }
    public int getHttpStatus() { return httpStatus; }
    public int getStatusCode() { return httpStatus; }
    public String getErrorType() { return errorType; }
    public long getRetryAfterMs() { return retryAfterMs; }
    public boolean isRetrySuppressed() { return retrySuppressed; }

    /**
     * 判断是否应触发模型降级。
         * - 429 Too Many Requests
     * - 529 Overloaded
     * - error_type 包含 "overloaded"
     */
    public boolean isFallbackTrigger() {
        if (httpStatus == 429 || httpStatus == 529) return true;
        if (errorType != null && errorType.contains("overloaded")) return true;
        String msg = getMessage();
        return msg != null && msg.contains("overloaded");
    }
}
