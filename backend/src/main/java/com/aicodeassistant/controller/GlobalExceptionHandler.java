package com.aicodeassistant.controller;

import com.aicodeassistant.exception.ResourceNotFoundException;
import com.aicodeassistant.llm.LlmApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * REST API 全局异常处理 — 统一错误响应格式。
 * <p>
 * 所有 Controller 抛出的异常统一转换为以下 JSON 格式:
 * <pre>
 * { "error": { "code": "SESSION_NOT_FOUND", "message": "...", "details": {...} } }
 * </pre>
 *
 * @see <a href="SPEC §6.1.6a">全局异常处理</a>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 资源未找到 (404)。
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(404).body(errorBody(ex.getCode(), ex.getMessage(), null));
    }

    /**
     * 参数校验失败 (400)。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(e -> fieldErrors.put(e.getField(), e.getDefaultMessage()));
        return ResponseEntity.badRequest().body(
                errorBody("VALIDATION_ERROR", "Request validation failed", fieldErrors));
    }

    /**
     * 非法参数 (400)。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(
                errorBody("INVALID_REQUEST", ex.getMessage(), null));
    }

    /**
     * LLM API 错误 (502/503)。
     */
    @ExceptionHandler(LlmApiException.class)
    public ResponseEntity<Map<String, Object>> handleLlmError(LlmApiException ex) {
        int status = ex.isRetryable() ? 503 : 502;
        return ResponseEntity.status(status).body(
                errorBody("LLM_API_ERROR", ex.getMessage(),
                        Map.of("retryable", ex.isRetryable())));
    }

    /**
     * 未知错误 (500)。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(500).body(
                errorBody("INTERNAL_ERROR", "An unexpected error occurred", null));
    }

    // ───── 错误响应构建 ─────

    private Map<String, Object> errorBody(String code, String message, Object details) {
        Map<String, Object> error = new HashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("timestamp", Instant.now().toString());
        if (details != null) {
            error.put("details", details);
        }
        return Map.of("error", error);
    }
}
