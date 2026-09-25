package com.aicodeassistant.tool;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/** V2 structured tool outcome. ToolExecutionResult remains a context-only wrapper. */
public record ToolResult(
        int schemaVersion,
        ExecutionStatus executionStatus,
        ToolFailureType failureType,
        String failureCode,
        String content,
        Retryability retryability,
        EffectState effectState,
        Integer exitCode,
        Instant startedAt,
        Instant finishedAt,
        String outputPreview,
        boolean outputTruncated,
        Map<String, Object> metadata
) {
    public enum ExecutionStatus { SUCCEEDED, FAILED, TIMED_OUT, CANCELLED }
    public enum ToolFailureType { VALIDATION, PERMISSION, PROCESS, NETWORK, PROVIDER, INTERNAL }
    public enum Retryability { NEVER, SAFE_READ_ONLY, IDEMPOTENCY_REQUIRED }
    public enum EffectState { NOT_STARTED, NONE, APPLIED, PARTIAL, UNKNOWN }

    public ToolResult {
        if (schemaVersion != 2) {
            throw new IllegalArgumentException("Unsupported ToolResult schemaVersion: " + schemaVersion);
        }
        if (executionStatus == null || retryability == null || effectState == null) {
            throw new IllegalArgumentException("ToolResult status, retryability and effectState are required");
        }
        if (executionStatus == ExecutionStatus.SUCCEEDED) {
            if (failureType != null || failureCode != null) {
                throw new IllegalArgumentException("Successful ToolResult cannot contain failure information");
            }
        } else if (failureType == null || failureCode == null || failureCode.isBlank()) {
            throw new IllegalArgumentException("Failed ToolResult requires failureType and failureCode");
        }
        if (finishedAt != null && startedAt != null && finishedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("ToolResult finishedAt cannot precede startedAt");
        }
        metadata = metadata == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(metadata));
        outputPreview = outputPreview == null ? preview(content) : outputPreview;
        startedAt = startedAt == null ? Instant.now() : startedAt;
        finishedAt = finishedAt == null ? Instant.now() : finishedAt;
    }

    public boolean isError() { return executionStatus != ExecutionStatus.SUCCEEDED; }
    public static ToolResult success(String content) { return success(content, Map.of()); }
    public static ToolResult success(String content, Map<String,Object> metadata) {
        return v2(ExecutionStatus.SUCCEEDED, null, null, content, Retryability.NEVER,
                EffectState.NONE, number(metadata,"exitCode"), false, metadata);
    }
    public static ToolResult successWithEffect(String content, EffectState effectState) {
        return successWithEffect(content, effectState, Map.of());
    }
    public static ToolResult successWithEffect(String content, EffectState effectState,
                                               Map<String, Object> metadata) {
        if (effectState == null || effectState == EffectState.NOT_STARTED) {
            throw new IllegalArgumentException("Successful result requires a completed effect state");
        }
        return v2(ExecutionStatus.SUCCEEDED, null, null, content, Retryability.NEVER,
                effectState, number(metadata, "exitCode"), false, metadata);
    }
    public static ToolResult validationError(String code, String message) {
        return failed(ToolFailureType.VALIDATION, code, message, Retryability.NEVER,
                EffectState.NOT_STARTED, null, Map.of());
    }
    public static ToolResult internalError(String code, String message, EffectState effectState) {
        return failed(ToolFailureType.INTERNAL, code, message, Retryability.NEVER,
                effectState, null, Map.of());
    }
    public static ToolResult networkError(String code, String message, Retryability retryability) {
        return networkError(code, message, retryability, EffectState.NONE);
    }
    public static ToolResult networkError(String code, String message, Retryability retryability,
                                          EffectState effectState) {
        return failed(ToolFailureType.NETWORK, code, message, retryability,
                effectState, null, Map.of());
    }
    public static ToolResult providerError(String code, String message, Retryability retryability) {
        return failed(ToolFailureType.PROVIDER, code, message, retryability,
                EffectState.NONE, null, Map.of());
    }
    public static ToolResult permissionDenied(String code, String message) {
        return v2(ExecutionStatus.FAILED, ToolFailureType.PERMISSION, code, message,
                Retryability.NEVER, EffectState.NOT_STARTED, null, false, Map.of());
    }
    public static ToolResult timedOut(String code, String message, Integer exitCode, boolean terminationConfirmed) {
        return timedOut(code, message, exitCode, terminationConfirmed,
                terminationConfirmed ? EffectState.UNKNOWN : EffectState.PARTIAL);
    }
    public static ToolResult timedOut(String code, String message, Integer exitCode,
                                      boolean terminationConfirmed, EffectState effectState) {
        return v2(ExecutionStatus.TIMED_OUT, ToolFailureType.PROCESS, code, message,
                Retryability.NEVER, effectState,
                exitCode, false, Map.of("terminationConfirmed", terminationConfirmed));
    }
    public static ToolResult cancelled(String code, String message, EffectState effectState) {
        return v2(ExecutionStatus.CANCELLED, ToolFailureType.INTERNAL, code, message,
                Retryability.NEVER, effectState, null, false, Map.of());
    }
    public static ToolResult backgroundStarted(String content, long pid) {
        return v2(ExecutionStatus.SUCCEEDED, null, null, content, Retryability.NEVER,
                EffectState.UNKNOWN, null, false, Map.of(
                        "mode", "BACKGROUND", "pid", pid,
                        "terminationConfirmed", false, "backgroundRiskDetected", true));
    }
    public static ToolResult failed(ToolFailureType type, String code, String message,
                                    Retryability retryability, EffectState effectState,
                                    Integer exitCode, Map<String, Object> metadata) {
        if (type == null || code == null || code.isBlank())
            throw new IllegalArgumentException("Structured failures require type and code");
        return v2(ExecutionStatus.FAILED, type, code, message, retryability, effectState,
                exitCode, Boolean.TRUE.equals(metadata == null ? null : metadata.get("truncated")), metadata);
    }
    public static ToolResult process(String content, int exitCode, Map<String,Object> metadata) {
        return v2(exitCode == 0 ? ExecutionStatus.SUCCEEDED : ExecutionStatus.FAILED,
                exitCode == 0 ? null : ToolFailureType.PROCESS,
                exitCode == 0 ? null : "PROCESS_EXIT_NONZERO", content,
                Retryability.NEVER, exitCode == 0 ? EffectState.NONE : EffectState.UNKNOWN,
                exitCode, Boolean.TRUE.equals(metadata == null ? null : metadata.get("truncated")), metadata);
    }
    public static ToolResult text(String content) { return success(content); }
    public static ToolResult image(String base64, String mimeType, long originalSize) {
        return success(base64, Map.of("type","image","mimeType",mimeType,"originalSize",originalSize));
    }
    public boolean isRetryable() {
        return retryability != Retryability.NEVER
                && (effectState == EffectState.NONE || effectState == EffectState.NOT_STARTED);
    }
    public ToolResult withMetadata(String key,Object value) {
        var copy=new HashMap<>(metadata); copy.put(key,value);
        Integer nextExit = exitCode;
        if ("exitCode".equals(key) && value instanceof Number n) {
            nextExit = Integer.valueOf(n.intValue());
        }
        boolean truncated=outputTruncated || ("truncated".equals(key) && Boolean.TRUE.equals(value));
        return new ToolResult(schemaVersion,executionStatus,failureType,failureCode,content,retryability,
                effectState,nextExit,startedAt,finishedAt,outputPreview,truncated,copy);
    }
    public ToolResult withContent(String nextContent, boolean truncated) {
        return new ToolResult(schemaVersion,executionStatus,failureType,failureCode,nextContent,retryability,
                effectState,exitCode,startedAt,Instant.now(),preview(nextContent),
                outputTruncated||truncated,metadata);
    }
    private static final String CONTEXT_MODIFIER_KEY="__contextModifier";
    public ToolResult withContextModifier(UnaryOperator<ToolUseContext> modifier) { return withMetadata(CONTEXT_MODIFIER_KEY,modifier); }
    @SuppressWarnings("unchecked") public UnaryOperator<ToolUseContext> getContextModifier() {
        Object value=metadata.get(CONTEXT_MODIFIER_KEY); return value instanceof UnaryOperator<?> ? (UnaryOperator<ToolUseContext>)value:null;
    }
    public ToolResult toSerializable() {
        if(!metadata.containsKey(CONTEXT_MODIFIER_KEY)) return this;
        var copy=new HashMap<>(metadata); copy.remove(CONTEXT_MODIFIER_KEY); copy.put("hasContextModifier",true);
        return new ToolResult(schemaVersion,executionStatus,failureType,failureCode,content,retryability,effectState,
                exitCode,startedAt,finishedAt,outputPreview,outputTruncated,copy);
    }
    private static ToolResult v2(ExecutionStatus status,ToolFailureType type,String code,String content,
            Retryability retry,EffectState effect,Integer exit,boolean truncated,Map<String,Object> metadata) {
        Instant now=Instant.now(); return new ToolResult(2,status,type,code,content,retry,effect,exit,now,now,preview(content),truncated,metadata);
    }
    private static String preview(String text) { return text==null?"":text.substring(0,Math.min(30_000,text.length())); }
    private static Integer number(Map<String,Object> map,String key) { Object v=map==null?null:map.get(key); return v instanceof Number n?n.intValue():null; }
}
