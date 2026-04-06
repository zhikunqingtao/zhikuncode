package com.aicodeassistant.tool;

/**
 * 输入验证结果。
 */
public record ValidationResult(
        boolean isValid,
        String errorCode,
        String errorMessage
) {
    public static ValidationResult ok() {
        return new ValidationResult(true, null, null);
    }

    public static ValidationResult invalid(String errorCode, String errorMessage) {
        return new ValidationResult(false, errorCode, errorMessage);
    }
}
