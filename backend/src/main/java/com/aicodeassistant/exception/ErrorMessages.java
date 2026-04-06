package com.aicodeassistant.exception;

/**
 * 用户面错误消息常量 (§9.7.2) — 对齐源码 errors.ts 的消息常量。
 * <p>
 * 区分交互式/非交互式两种模式的提示文案。
 */
public final class ErrorMessages {

    private ErrorMessages() {}

    // ── 认证相关 ──
    public static final String CREDIT_BALANCE_TOO_LOW =
            "Credit balance is too low";
    public static final String INVALID_API_KEY =
            "Not logged in · Please run /login";
    public static final String INVALID_API_KEY_EXTERNAL =
            "Invalid API key · Fix external API key";
    public static final String TOKEN_REVOKED =
            "OAuth token revoked · Please run /login";
    public static final String CCR_AUTH_ERROR =
            "Authentication error · This may be a temporary network issue, please try again";

    // ── 模型相关 ──
    public static final String REPEATED_529 =
            "Repeated 529 Overloaded errors";
    public static final String API_TIMEOUT =
            "Request timed out";

    // ── 组织相关 ──
    public static final String ORG_DISABLED_WITH_OAUTH =
            "Your LLM_API_KEY belongs to a disabled organization · "
                    + "Unset the environment variable to use your subscription instead";
    public static final String ORG_NOT_ALLOWED =
            "Your account does not have access. Please run /login.";

    // ── 安全相关 ──
    public static final String ACCESS_DENIED_PUBLIC_NETWORK =
            "Access denied: only private network allowed";
    public static final String AUTH_REQUIRED =
            "Authentication required. Use token URL or Bearer header.";
}
