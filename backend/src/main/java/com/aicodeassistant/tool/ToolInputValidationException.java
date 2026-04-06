package com.aicodeassistant.tool;

/**
 * 工具输入验证异常 — 当工具输入参数缺失或类型不匹配时抛出。
 */
public class ToolInputValidationException extends RuntimeException {

    public ToolInputValidationException(String message) {
        super(message);
    }

    public ToolInputValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
