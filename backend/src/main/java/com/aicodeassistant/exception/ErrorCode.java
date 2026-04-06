package com.aicodeassistant.exception;

/**
 * 统一错误码枚举 (§9.7.1) — 所有 REST API 和 WebSocket 错误使用此枚举。
 * <p>
 * 命名规则: {DOMAIN}_{SPECIFIC_ERROR}
 * 前端通过 error.code 字段匹配并显示对应本地化消息。
 */
public enum ErrorCode {

    // ── 认证错误 (AUTH_*) ──
    AUTH_REQUIRED("认证未提供", 401),
    AUTH_TOKEN_EXPIRED("认证令牌已过期", 401),
    AUTH_TOKEN_REVOKED("OAuth 令牌已被撤销", 403),
    AUTH_INVALID_API_KEY("API Key 无效", 401),
    AUTH_ORG_DISABLED("所属组织已被禁用", 400),
    AUTH_ORG_NOT_ALLOWED("组织无权访问", 403),
    AUTH_CCR_TRANSIENT("临时认证错误，请重试", 401),

    // ── 会话错误 (SESSION_*) ──
    SESSION_NOT_FOUND("会话不存在", 404),
    SESSION_ALREADY_EXISTS("会话已存在", 409),
    SESSION_EXPIRED("会话已过期", 410),
    SESSION_CONCURRENT_MODIFICATION("会话被并发修改", 409),

    // ── 模型错误 (MODEL_*) ──
    MODEL_NOT_FOUND("模型不存在或无访问权限", 404),
    MODEL_RATE_LIMITED("模型请求频率超限", 429),
    MODEL_OVERLOADED("模型服务过载", 529),
    MODEL_PROMPT_TOO_LONG("提示词超出上下文窗口", 400),
    MODEL_TIMEOUT("模型请求超时", 504),

    // ── 工具错误 (TOOL_*) ──
    TOOL_PERMISSION_DENIED("工具调用权限被拒绝", 403),
    TOOL_EXECUTION_FAILED("工具执行失败", 500),
    TOOL_SANDBOX_VIOLATION("工具沙箱违规", 403),
    TOOL_RESULT_TOO_LARGE("工具结果超出大小限制", 413),

    // ── 文件错误 (FILE_*) ──
    FILE_NOT_FOUND("文件不存在", 404),
    FILE_PERMISSION_DENIED("文件访问权限不足", 403),
    FILE_TOO_LARGE("文件大小超出限制", 413),
    FILE_BINARY_NOT_SUPPORTED("不支持二进制文件", 415),
    FILE_PDF_TOO_LARGE("PDF 页数超出限制", 413),
    FILE_PDF_PASSWORD_PROTECTED("PDF 受密码保护", 415),
    FILE_IMAGE_TOO_LARGE("图片大小超出限制", 413),

    // ── 配置错误 (CONFIG_*) ──
    CONFIG_VALIDATION_ERROR("配置参数验证失败", 400),
    CONFIG_WRITE_FAILED("配置写入失败", 500),

    // ── MCP 错误 (MCP_*) ──
    MCP_SERVER_NOT_FOUND("MCP 服务器不存在", 404),
    MCP_SERVER_CONNECTION_FAILED("MCP 服务器连接失败", 502),
    MCP_CONFIG_INVALID("MCP 服务器配置无效", 400),
    MCP_TOOL_EXECUTION_FAILED("MCP 工具执行失败", 500),

    // ── 插件错误 (PLUGIN_*) ──
    PLUGIN_NOT_FOUND("插件不存在", 404),
    PLUGIN_INSTALL_FAILED("插件安装失败", 500),
    PLUGIN_MANIFEST_INVALID("插件清单文件无效", 400),
    PLUGIN_LOAD_FAILED("插件加载失败", 500),
    PLUGIN_HOOK_FAILED("插件 Hook 执行失败", 500),

    // ── 计费错误 (BILLING_*) ──
    BILLING_CREDIT_LOW("账户余额不足", 402),
    BILLING_QUOTA_EXCEEDED("使用配额已耗尽", 429),

    // ── 系统错误 (SYSTEM_*) ──
    SYSTEM_INTERNAL_ERROR("内部服务器错误", 500),
    SYSTEM_PYTHON_SERVICE_UNAVAILABLE("Python 服务不可用", 502),
    SYSTEM_LLM_API_UNAVAILABLE("LLM API 不可用", 503);

    private final String defaultMessage;
    private final int httpStatus;

    ErrorCode(String defaultMessage, int httpStatus) {
        this.defaultMessage = defaultMessage;
        this.httpStatus = httpStatus;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
