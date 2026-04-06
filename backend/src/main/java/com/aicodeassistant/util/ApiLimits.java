package com.aicodeassistant.util;

/**
 * API 限制常量 — 对齐 src/constants/apiLimits.ts。
 * 客户端验证，避免 API 返回不友好错误。
 *
 * @see <a href="SPEC §7.5.4">API 限制常量</a>
 */
public final class ApiLimits {

    // 图像限制
    public static final int IMAGE_MAX_BASE64_SIZE  = 5 * 1024 * 1024;   // 5 MB
    public static final int IMAGE_TARGET_RAW_SIZE  = IMAGE_MAX_BASE64_SIZE * 3 / 4; // 3.75 MB
    public static final int IMAGE_MAX_WIDTH        = 2000;
    public static final int IMAGE_MAX_HEIGHT       = 2000;

    // PDF 限制
    public static final int PDF_TARGET_RAW_SIZE          = 20 * 1024 * 1024;  // 20 MB
    public static final int PDF_MAX_PAGES                = 100;
    public static final int PDF_EXTRACT_SIZE_THRESHOLD   = 3 * 1024 * 1024;   // 3 MB
    public static final int PDF_MAX_EXTRACT_SIZE         = 100 * 1024 * 1024;  // 100 MB
    public static final int PDF_MAX_PAGES_PER_READ       = 20;
    public static final int PDF_AT_MENTION_INLINE_THRESHOLD = 10;

    // 媒体限制
    public static final int MAX_MEDIA_PER_REQUEST = 100;

    private ApiLimits() {} // 不可实例化
}
