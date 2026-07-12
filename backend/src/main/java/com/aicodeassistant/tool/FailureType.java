package com.aicodeassistant.tool;

/**
 * 统一错误分类枚举 — 为 ToolResult 提供结构化的失败类型。
 * <p>
 * 分为四大类:
 * <ul>
 *   <li>retryable — 可自动重试 (瞬态 I/O, 限速, 超时, 资源忙)</li>
 *   <li>input_error — 需修正输入 (无效参数, 文件不存在, 路径越界, 编码, 冲突)</li>
 *   <li>needs_human — 需人工干预 (权限, 磁盘满, 命令缺失)</li>
 *   <li>fatal — 不可恢复 (内部错误, 中止)</li>
 * </ul>
 */
public enum FailureType {
    // 可自动重试类
    TRANSIENT_IO("Temporary I/O failure", true),
    RATE_LIMITED("API rate limit hit", true),
    TIMEOUT("Operation timed out", true),
    RESOURCE_BUSY("Resource temporarily locked", true),

    // 需修正输入类
    INVALID_INPUT("Invalid tool input parameters", false),
    FILE_NOT_FOUND("Target file does not exist", false),
    PATH_OUTSIDE_WORKSPACE("Path outside allowed workspace", false),
    ENCODING_ERROR("File encoding not supported", false),
    CONFLICT("File was modified externally", false),

    // 需人工干预类
    PERMISSION_DENIED("Insufficient permissions", false),
    DISK_FULL("No space left on device", false),
    COMMAND_NOT_FOUND("Required command not installed", false),

    // 不可恢复类
    INTERNAL_ERROR("Internal tool error", false),
    ABORTED("Operation was aborted", false);

    private final String defaultMessage;
    private final boolean retryable;

    FailureType(String defaultMessage, boolean retryable) {
        this.defaultMessage = defaultMessage;
        this.retryable = retryable;
    }

    public String getDefaultMessage() { return defaultMessage; }
    public boolean isRetryable() { return retryable; }

    public String getCategory() {
        if (retryable) return "retryable";
        return switch (this) {
            case INVALID_INPUT, FILE_NOT_FOUND, PATH_OUTSIDE_WORKSPACE, ENCODING_ERROR, CONFLICT -> "input_error";
            case PERMISSION_DENIED, DISK_FULL, COMMAND_NOT_FOUND -> "needs_human";
            case INTERNAL_ERROR, ABORTED -> "fatal";
            default -> "unknown";
        };
    }
}
