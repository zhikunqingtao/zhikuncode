package com.aicodeassistant.controller;

import com.aicodeassistant.exception.ResourceNotFoundException;
import com.aicodeassistant.exception.RequestValidationException;
import com.aicodeassistant.exception.WorkspaceException;
import com.aicodeassistant.llm.LlmApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.server.ResponseStatusException;

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
 */
@RestControllerAdvice(basePackages = "com.aicodeassistant")
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 资源未找到 (404)。
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(404).body(errorBody(ex.getCode(), ex.getMessage(), null));
    }

    @ExceptionHandler(WorkspaceException.class)
    public ResponseEntity<Map<String, Object>> handleWorkspace(
            WorkspaceException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(errorBody(ex.getCode(), ex.getMessage(), null));
    }

    @ExceptionHandler(RequestValidationException.class)
    public ResponseEntity<Map<String, Object>> handleRequestValidation(
            RequestValidationException ex) {
        return ResponseEntity.badRequest()
                .body(errorBody(ex.getCode(), ex.getMessage(), null));
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
     * 缺少必需的请求参数 (400)。
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException ex) {
        return ResponseEntity.badRequest().body(
                errorBody("MISSING_PARAMETER",
                        "Required parameter '" + ex.getParameterName() + "' is missing", null));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, Object>> handleMissingHeader(MissingRequestHeaderException ex) {
        return ResponseEntity.badRequest().body(
                errorBody("MISSING_HEADER",
                        "Required header '" + ex.getHeaderName() + "' is missing", null));
    }

    /** Preserve deliberate API status codes instead of converting them to 500. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException ex) {
        String reason = ex.getReason() == null ? "REQUEST_REJECTED" : ex.getReason();
        return ResponseEntity.status(ex.getStatusCode())
                .body(errorBody(reason, reason, null));
    }

    /**
     * 请求体不可读 / 格式错误 (400)。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleHttpMessageNotReadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.badRequest().body(
                errorBody("INVALID_REQUEST_BODY", "Request body is missing or malformed", null));
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
