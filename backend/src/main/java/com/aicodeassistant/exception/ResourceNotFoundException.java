package com.aicodeassistant.exception;

/**
 * 资源未找到异常 — REST API 统一 404 处理。
 * <p>
 * 所有 "找不到" 类错误继承此异常，由 GlobalExceptionHandler 统一转换为
 * { "error": { "code": "...", "message": "..." } } 格式。
 *
 * @see <a href="SPEC §6.1.6a">全局异常处理</a>
 */
public class ResourceNotFoundException extends RuntimeException {

    private final String code;

    public ResourceNotFoundException(String code, String message) {
        super(message);
        this.code = code;
    }

    public ResourceNotFoundException(String message) {
        this("RESOURCE_NOT_FOUND", message);
    }

    public String getCode() {
        return code;
    }
}
