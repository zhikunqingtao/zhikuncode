package com.aicodeassistant.llm;

import java.time.Instant;

/**
 * 速率限制信息 — 统一各 Provider 的速率限制头解析结果。
 * <p>
 * OpenAI 兼容头: x-ratelimit-limit-requests, x-ratelimit-remaining-requests, etc.
 * Anthropic 头: anthropic-ratelimit-unified-status, anthropic-ratelimit-unified-reset
 *
 * @see <a href="SPEC §3.1.1b">withRetry 重试机制</a>
 */
public record RateLimitInfo(
        String status,            // active/fair/overextended (Anthropic) 或 null
        Instant resetTime,        // 速率限制重置时间
        Integer remainingRequests,
        Integer remainingTokens,
        Integer limitRequests,
        Integer limitTokens
) {

    /**
     * 从 OpenAI 兼容响应头解析速率限制信息。
     */
    public static RateLimitInfo fromOpenAiHeaders(okhttp3.Headers headers) {
        Integer limitReq = parseIntHeader(headers, "x-ratelimit-limit-requests");
        Integer remainReq = parseIntHeader(headers, "x-ratelimit-remaining-requests");
        Integer limitTok = parseIntHeader(headers, "x-ratelimit-limit-tokens");
        Integer remainTok = parseIntHeader(headers, "x-ratelimit-remaining-tokens");

        Instant resetTime = null;
        String resetStr = headers.get("x-ratelimit-reset-requests");
        if (resetStr != null) {
            try {
                // 可能是 ISO 8601 或相对秒数
                if (resetStr.contains("T")) {
                    resetTime = Instant.parse(resetStr);
                } else {
                    // "6s" / "1m" 格式
                    resetTime = parseRelativeTime(resetStr);
                }
            } catch (Exception ignored) {}
        }

        return new RateLimitInfo(null, resetTime, remainReq, remainTok, limitReq, limitTok);
    }

    /**
     * 是否已触发速率限制。
     */
    public boolean isRateLimited() {
        return (remainingRequests != null && remainingRequests <= 0)
                || (remainingTokens != null && remainingTokens <= 0);
    }

    /**
     * 计算建议等待时间（毫秒）。
     */
    public long suggestedWaitMs() {
        if (resetTime == null) return 1000;
        long waitMs = resetTime.toEpochMilli() - System.currentTimeMillis();
        return Math.max(waitMs, 100);
    }

    private static Integer parseIntHeader(okhttp3.Headers headers, String name) {
        String value = headers.get(name);
        if (value == null) return null;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Instant parseRelativeTime(String value) {
        // Parse "6s", "1m30s", "2m" etc.
        long totalSeconds = 0;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)([smh])")
                .matcher(value);
        while (m.find()) {
            long num = Long.parseLong(m.group(1));
            switch (m.group(2)) {
                case "s" -> totalSeconds += num;
                case "m" -> totalSeconds += num * 60;
                case "h" -> totalSeconds += num * 3600;
            }
        }
        return Instant.now().plusSeconds(Math.max(totalSeconds, 1));
    }
}
