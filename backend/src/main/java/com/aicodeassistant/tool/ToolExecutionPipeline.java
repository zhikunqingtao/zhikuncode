package com.aicodeassistant.tool;

import com.aicodeassistant.hook.HookRegistry;
import com.aicodeassistant.hook.HookService;
import com.aicodeassistant.model.*;
import com.aicodeassistant.permission.PermissionModeManager;
import com.aicodeassistant.permission.PermissionNotifier;
import com.aicodeassistant.permission.PermissionPipeline;
import com.aicodeassistant.permission.PermissionRuleRepository;
import com.aicodeassistant.run.RunTracker;
import com.aicodeassistant.artifact.ArtifactManifestService;
import com.aicodeassistant.security.SensitiveDataFilter;
import com.aicodeassistant.tool.recovery.ToolRecoveryFramework;
import com.aicodeassistant.tool.recovery.ToolRecoveryFramework.RecoveryAction;
import com.aicodeassistant.tool.recovery.ToolRecoveryFramework.RecoveryContext;
import com.aicodeassistant.tool.recovery.ToolRecoveryFramework.RecoveryDecision;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 工具执行管线 — 7 阶段执行流程。
 * <p>
 * 阶段 1: Schema 输入验证
 * 阶段 1.5: JSON Schema 结构化验证
 * 阶段 2: 工具自定义验证
 * 阶段 2.5: 输入预处理 (backfill)
 * 阶段 3: PreToolUse 钩子
 * 阶段 4: 权限检查
 * 阶段 5: 工具调用
 * 阶段 6: 结果处理 + PostToolUse 钩子
 * 阶段 7: contextModifier 提取与应用
 *
 */
@Component
public class ToolExecutionPipeline {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutionPipeline.class);

    private final HookService hookService;
    private final ObjectMapper objectMapper;
    private final PermissionPipeline permissionPipeline;
    private final PermissionRuleRepository permissionRuleRepository;
    private final SensitiveDataFilter sensitiveDataFilter;
    private final PermissionModeManager permissionModeManager;
    private final ToolRecoveryFramework recoveryFramework;
    @Nullable
    private final RunTracker runTracker;
    @Nullable private final ArtifactManifestService artifactManifestService;

    public ToolExecutionPipeline(HookService hookService, ObjectMapper objectMapper,
                                  PermissionPipeline permissionPipeline,
                                  PermissionRuleRepository permissionRuleRepository,
                                  SensitiveDataFilter sensitiveDataFilter,
                                  PermissionModeManager permissionModeManager,
                                  ToolRecoveryFramework recoveryFramework,
                                  @Lazy @Nullable RunTracker runTracker) {
        this(hookService, objectMapper, permissionPipeline, permissionRuleRepository,
                sensitiveDataFilter, permissionModeManager, recoveryFramework, runTracker, null);
    }

    @Autowired
    public ToolExecutionPipeline(HookService hookService, ObjectMapper objectMapper,
                                  PermissionPipeline permissionPipeline,
                                  PermissionRuleRepository permissionRuleRepository,
                                  SensitiveDataFilter sensitiveDataFilter,
                                  PermissionModeManager permissionModeManager,
                                  ToolRecoveryFramework recoveryFramework,
                                  @Lazy @Nullable RunTracker runTracker,
                                  @Lazy @Nullable ArtifactManifestService artifactManifestService) {
        this.hookService = hookService;
        this.objectMapper = objectMapper;
        this.permissionPipeline = permissionPipeline;
        this.permissionRuleRepository = permissionRuleRepository;
        this.sensitiveDataFilter = sensitiveDataFilter;
        this.permissionModeManager = permissionModeManager;
        this.recoveryFramework = recoveryFramework;
        this.runTracker = runTracker;
        this.artifactManifestService = artifactManifestService;
    }

    /**
     * 执行工具 — 完整 7 阶段管线。
     *
     * @param tool    工具实例
     * @param input   工具输入
     * @param context 执行上下文
     * @param wsPusher WebSocket 推送器（用于权限请求推送）
     * @return 工具执行结果
     */
    public ToolExecutionResult execute(Tool tool, ToolInput input, ToolUseContext context,
                               PermissionNotifier wsPusher) {
        return doExecute(tool, input, context, wsPusher, 1);
    }

    /**
     * 执行工具 — 完整 7 阶段管线。
     *
     * @param tool    工具实例
     * @param input   工具输入
     * @param context 执行上下文
     * @return 工具执行结果
     */
    public ToolExecutionResult execute(Tool tool, ToolInput input, ToolUseContext context) {
        return doExecute(tool, input, context, null, 1);
    }

    /** 最大自动重试次数 */
    private static final int MAX_RETRY_ATTEMPTS = 3;

    private ToolExecutionResult doExecute(Tool tool, ToolInput input, ToolUseContext context,
                                  PermissionNotifier wsPusher, int attemptCount) {
        // 回退: 若调用方未传 wsPusher，从 context 中获取
        PermissionNotifier effectivePusher = wsPusher != null
                ? wsPusher : context.permissionNotifier();
        String toolName = tool.getName();
        long startTime = System.currentTimeMillis();

        try {
            // ── 阶段 1: Schema 输入验证 ──
            log.debug("Executing tool: {} (stage 1: validation)", toolName);

            // ── 阶段 1.5: JSON Schema 结构化验证 ──
            validateSchema(tool, input.getRawData() instanceof Map
                    ? (Map<String, Object>) input.getRawData()
                    : Map.of("input", String.valueOf(input.getRawData())));

            // ── 阶段 2: 工具自定义验证 ──
            ValidationResult validation = tool.validateInput(input, context);
            if (!validation.isValid()) {
                log.warn("Tool input validation failed for {}: {} - {}",
                        toolName, validation.errorCode(), validation.errorMessage());
                return ToolExecutionResult.of(ToolResult.validationError(
                        validation.errorCode() == null ? "TOOL_INPUT_INVALID" : validation.errorCode(),
                        "Input validation failed: " + validation.errorMessage()));
            }

            // ── 阶段 2.5: 输入预处理 ──
            ToolInput processedInput = tool.backfillObservableInput(input);

            // ── 阶段 3: PreToolUse 钩子 ──
            String inputJson;
            try {
                inputJson = objectMapper.writeValueAsString(processedInput.getRawData());
            } catch (Exception e) {
                inputJson = processedInput.getRawData().toString();
            }

            HookRegistry.HookResult hookResult = hookService.executePreToolUse(
                    toolName, inputJson, context.sessionId());

            if (!hookResult.proceed()) {
                log.info("Tool {} blocked by PreToolUse hook: {}", toolName, hookResult.message());
                return ToolExecutionResult.of(ToolResult.permissionDenied("TOOL_BLOCKED_BY_HOOK",
                        "Tool execution blocked by hook: " + hookResult.message()));
            }

            // 如果钩子修改了输入，使用修改后的输入
            if (hookResult.modifiedInput() != null) {
                log.debug("Tool {} input modified by PreToolUse hook", toolName);
                try {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> modified =
                        objectMapper.readValue(hookResult.modifiedInput(), java.util.Map.class);
                    processedInput = ToolInput.from(modified);
                } catch (Exception e) {
                    log.warn("Failed to parse modified input from hook, using original: {}",
                            e.getMessage());
                }
            }

            // ── 阶段 4: 权限检查 ──
            PermissionRequirement permReq = tool.getPermissionRequirement();
            log.debug("Permission stage: tool={}, requirement={}, sessionId={}", toolName, permReq, context.sessionId());
            if (permReq != PermissionRequirement.NONE) {
                // 构建权限上下文 — 从 PermissionModeManager 获取会话级模式
                PermissionMode sessionMode = permissionModeManager.getMode(context.sessionId());
                PermissionContext permContext = permissionRuleRepository.buildContext(
                        sessionMode, false, false);
                PermissionDecision decision = permissionPipeline.checkPermission(
                        tool, processedInput, context, permContext);
                log.debug("Permission decision: tool={}, behavior={}, reason={}",
                        toolName, decision.behavior(), decision.reason());

                if (decision.isDenied()) {
                    log.info("Tool {} permission denied: {}", toolName, decision.reason());
                    // 通知前端清除已显示的 changedFiles
                    if (effectivePusher != null && context.sessionId() != null) {
                        String denyToolUseId = context.toolUseId() != null ? context.toolUseId() : toolName;
                        effectivePusher.sendToolPermissionDenied(context.sessionId(), denyToolUseId, toolName);
                    }
                    return ToolExecutionResult.of(ToolResult.permissionDenied(
                            "PERMISSION_POLICY_DENIED", "Permission denied: " + decision.reason()));
                }

                if (decision.behavior() == PermissionBehavior.ASK) {
                    log.info("[PERM-BUBBLE] ASK decision: tool={}, bubble={}, parentSessionId={}, "
                            + "sessionId={}, reason={}", toolName, decision.bubble(),
                            context.parentSessionId(), context.sessionId(), decision.reason());

                    // 冒泡判定：parentSessionId 存在即为子代理会话，无条件冒泡
                    boolean shouldBubble = context.parentSessionId() != null;

                    if (shouldBubble) {
                        log.info("Forwarding permission to parent session: tool={}, parentSession={}, "
                                + "originalBubble={}", toolName, context.parentSessionId(), decision.bubble());
                        ToolExecutionResult bubbleResult = forwardPermissionToParent(
                                tool, processedInput, decision.withBubble(true), context, effectivePusher);
                        if (bubbleResult != null) {
                            return bubbleResult; // 拒绝或错误
                        }
                        // null 表示父代理已批准，继续执行工具
                    } else {
                    // 兜底：如果 parentSessionId 为 null 但 bubble 标记为 true，属于异常状态
                    if (decision.bubble()) {
                        log.error("[BUG] decision.bubble()=true but parentSessionId is null! "
                                + "tool={}, sessionId={}", toolName, context.sessionId());
                    }
                    if (effectivePusher == null || context.sessionId() == null) {
                        log.warn("Tool {} requires permission but no WebSocket pusher available, denying. "
                                + "sessionId={}, contextNotifier={}, wsPusherParam={}",
                                toolName, context.sessionId(),
                        context.permissionNotifier() != null ? context.permissionNotifier().getClass().getSimpleName() : "null",
                                wsPusher != null ? wsPusher.getClass().getSimpleName() : "null");
                        return ToolExecutionResult.of(ToolResult.permissionDenied(
                                "PERMISSION_PROMPT_UNAVAILABLE", "Permission required but cannot prompt user. "
                                + "This typically occurs in REST API mode \u2014 use WebSocket for interactive permission prompts."));
                    }
                    // 异步等待用户决策（在 VirtualThread 上 join() 不会阻塞平台线程）
                    String toolUseId = context.toolUseId() != null ? context.toolUseId() : toolName;
                    PermissionDecision userDecision = permissionPipeline.requestPermission(
                            toolUseId, tool, processedInput,
                            decision.reason() != null ? decision.reason() : "Tool requires permission",
                            effectivePusher, context.sessionId(),
                            context.currentRunId(),
                            null, context.workingDirectory()
                    ).join();

                    if (!userDecision.isAllowed()) {
                        String failureCode = permissionFailureCode(userDecision.reasonType());
                        log.info("Tool {} permission interaction ended: code={}", toolName, failureCode);
                        // ★ V4: Deny + Remember 也需要记住拒绝决策
                        if (userDecision.remember()) {
                            String denyProjectKey = context != null ? context.workingDirectory() : null;
                            permissionPipeline.rememberDecision(
                                    tool, processedInput,
                                    false,  // denied
                                    userDecision.rememberScope() != null ? userDecision.rememberScope() : PermissionScope.SESSION,
                                    denyProjectKey, context.sessionId(), context.currentRunId(), toolUseId);
                        }
                        effectivePusher.sendToolPermissionDenied(context.sessionId(), toolUseId, toolName);
                        return ToolExecutionResult.of(ToolResult.permissionDenied(
                                failureCode, userDecision.reason() == null
                                        ? "Permission interaction did not allow execution" : userDecision.reason()));
                    }

                    if (userDecision.remember()) {
                        String allowProjectKey = context != null ? context.workingDirectory() : null;
                        permissionPipeline.rememberDecision(tool, processedInput,
                                true, userDecision.rememberScope() != null
                                        ? userDecision.rememberScope() : PermissionScope.SESSION, allowProjectKey,
                                context.sessionId(), context.currentRunId(), toolUseId);
                    }
                    } // end else (non-bubble path)
                }
            }

            // ── 阶段 5: 工具调用 ──
            log.debug("Executing tool: {} (stage 5: call)", toolName);

            // ★ RunTracker: 记录 tool_call 事件
            String currentRunId = context.currentRunId();
            if (currentRunId != null && runTracker != null) {
                try {
                    Map<String, Object> toolCallPayload = new HashMap<>();
                    toolCallPayload.put("toolName", toolName);
                    toolCallPayload.put("toolUseId", context.toolUseId() != null ? context.toolUseId() : "");
                    String inputFilePath = extractFilePathFromInput(processedInput);
                    if (inputFilePath != null) {
                        toolCallPayload.put("filePath", inputFilePath);
                    }
                    runTracker.recordEvent(currentRunId, "tool_call", toolCallPayload);
                } catch (Exception e) {
                    log.warn("Failed to record tool_call event: {}", e.getMessage());
                }
            }

            List<DeclaredOutput> declaredOutputs;
            try {
                declaredOutputs = declareOutputs(toolName, processedInput, context);
            } catch (Exception declarationError) {
                log.warn("Artifact pre-declaration rejected: tool={}, error={}", toolName,
                        declarationError.getMessage());
                return ToolExecutionResult.of(ToolResult.failed(
                        ToolResult.ToolFailureType.VALIDATION, "ARTIFACT_DECLARATION_FAILED",
                        declarationError.getMessage(), ToolResult.Retryability.NEVER,
                        ToolResult.EffectState.NOT_STARTED, null, Map.of()));
            }

            ToolResult result = tool.call(processedInput, context);

            // A tool may report an operational error after its file effect has already
            // been committed (for example, a post-move verification failure).  The
            // artifact still has to be sealed so that the database reflects the real
            // workspace state and the model is not encouraged to repeat the write.
            if ((!result.isError() || result.effectState() == ToolResult.EffectState.APPLIED)
                    && artifactManifestService != null && context.currentRunId() != null) {
                for (DeclaredOutput output : declaredOutputs) {
                    if ("deleted".equals(output.operation())) continue; // sealed from the pre-delete bytes
                    try {
                        artifactManifestService.sealFromFile(context.currentRunId(), output.path(),
                                context.workingDirectory());
                    } catch (Exception sealError) {
                        log.error("Artifact seal failed after file effect: tool={}, path={}",
                                toolName, output.path(), sealError);
                        result = ToolResult.failed(ToolResult.ToolFailureType.INTERNAL,
                                "ARTIFACT_SEAL_FAILED",
                                "Tool effect was applied but output sealing failed: " + sealError.getMessage(),
                                ToolResult.Retryability.NEVER, ToolResult.EffectState.APPLIED, null,
                                Map.of("filePath", output.path()));
                        break;
                    }
                }
            }

            // ★ RunTracker: 记录 tool_result 事件
            if (currentRunId != null && runTracker != null) {
                try {
                    Map<String, Object> toolResultPayload = new HashMap<>();
                    toolResultPayload.put("toolName", toolName);
                    toolResultPayload.put("toolUseId", context.toolUseId() != null ? context.toolUseId() : "");
                    toolResultPayload.put("isError", result.isError());
                    toolResultPayload.put("schemaVersion", 2);
                    toolResultPayload.put("executionStatus", result.executionStatus().name().toLowerCase());
                    if (result.failureCode() != null) toolResultPayload.put("failureCode", result.failureCode());
                    toolResultPayload.put("effectState", result.effectState().name().toLowerCase());
                    toolResultPayload.put("retryability", result.retryability().name().toLowerCase());
                    toolResultPayload.put("outputPreview", result.outputPreview());
                    toolResultPayload.put("outputTruncated", result.outputTruncated());
                    String resultFilePath = extractFilePathFromInput(processedInput);
                    if (resultFilePath != null) {
                        toolResultPayload.put("filePath", resultFilePath);
                    }
                    runTracker.recordEvent(currentRunId, "tool_result", toolResultPayload);
                    if (result.executionStatus() == ToolResult.ExecutionStatus.TIMED_OUT) {
                        runTracker.recordEvent(currentRunId, "process_timed_out", Map.of(
                                "toolName", toolName,
                                "toolUseId", context.toolUseId() == null ? "" : context.toolUseId(),
                                "failureCode", result.failureCode() == null ? "PROCESS_DEADLINE_EXCEEDED" : result.failureCode(),
                                "terminationConfirmed", result.metadata().getOrDefault("terminationConfirmed", false)));
                    } else if (result.executionStatus() == ToolResult.ExecutionStatus.CANCELLED) {
                        runTracker.recordEvent(currentRunId, "process_cancelled", Map.of(
                                "toolName", toolName,
                                "toolUseId", context.toolUseId() == null ? "" : context.toolUseId(),
                                "failureCode", result.failureCode() == null ? "PROCESS_CANCELLED" : result.failureCode(),
                                "effectState", result.effectState().name().toLowerCase()));
                    }
                } catch (Exception e) {
                    log.warn("Failed to record tool_result event: {}", e.getMessage());
                }
            }

            // ── 阶段 5b: mapToolResult — 工具自定义结果映射 ──
            result = tool.mapToolResult(result);

            // ── 阶段 6: 结果处理 + PostToolUse 钩子 ──
            HookRegistry.HookResult postHookResult = hookService.executePostToolUse(
                    toolName,
                    result.content() != null ? result.content() : "",
                    context.sessionId());

            if (postHookResult.modifiedOutput() != null) {
                log.debug("Tool {} output modified by PostToolUse hook", toolName);
                result = result.withContent(postHookResult.modifiedOutput(), false);
            }

            // ── 阶段 6b: 敏感信息过滤 ──
            if (result.content() != null) {
                String filtered = sensitiveDataFilter.filter(result.content());
                if (!filtered.equals(result.content())) {
                    log.debug("Tool {} output contained sensitive data, filtered", toolName);
                    result = result.withContent(filtered, false);
                }
            }

            long durationMs = System.currentTimeMillis() - startTime;
            log.info("Tool {} completed in {}ms (error={})",
                    toolName, durationMs, result.isError());

            // ── 阶段 5c: 错误结果恢复（仅 Bash 工具）──
            if (result.isError()
                    && result.effectState() == ToolResult.EffectState.NOT_STARTED
                    && "Bash".equals(toolName)) {
                ToolExecutionResult recoveryResult = attemptBashErrorRecovery(
                        tool, input, context, wsPusher, result, attemptCount, durationMs);
                if (recoveryResult != null) {
                    return recoveryResult;
                }
            }

            // 结果大小检查
            if (result.content() != null && result.content().length() > tool.getMaxResultSizeChars()) {
                String truncated = result.content().substring(0, tool.getMaxResultSizeChars())
                        + "\n... [truncated, " + result.content().length() + " chars total]";
                result = result.withContent(truncated, true);
            }

            // ── 阶段 7: contextModifier 提取与应用 ──
            var modifier = result.getContextModifier();
            ToolUseContext updatedContext = null;
            if (modifier != null) {
                updatedContext = modifier.apply(context);
                log.debug("Tool {} applied contextModifier", toolName);
            }

            return ToolExecutionResult.of(result.toSerializable(), updatedContext);

        } catch (ToolInputValidationException e) {
            log.warn("Tool {} input validation error: {}", toolName, e.getMessage());
            return ToolExecutionResult.of(ToolResult.validationError("INVALID_TOOL_INPUT", e.getMessage()));
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;
            log.error("Tool {} execution failed after {}ms: {}", toolName, durationMs, e.getMessage(), e);

            // ★ 尝试恢复—通过 ToolRecoveryFramework
            ToolResult failedResult = ToolResult.internalError("TOOL_EXECUTION_FAILED",
                    "Tool execution error: " + e.getMessage(), ToolResult.EffectState.UNKNOWN);
            Optional<RecoveryDecision> recovery = attemptToolRecovery(
                    toolName, input, failedResult, durationMs, e.getMessage());

            if (recovery.isPresent()) {
                RecoveryDecision decision = recovery.get();
                return buildRecoveryResult(decision, failedResult);
            }

            return ToolExecutionResult.of(failedResult);
        }
    }

    /**
     * 尝试工具执行失败后的恢复。
     */
    private Optional<RecoveryDecision> attemptToolRecovery(
            String toolName, ToolInput input, ToolResult failedResult,
            long durationMs, String errorMessage) {
        try {
            // 构建恢复上下文
            int exitCode = extractExitCode(failedResult);
            RecoveryContext context = new RecoveryContext(
                    toolName,
                    input.getRawData(),
                    failedResult,
                    1, // 当前管线层面是第一次尝试
                    Duration.ofMillis(durationMs),
                    errorMessage,
                    exitCode
            );
            return recoveryFramework.attemptRecovery(context);
        } catch (Exception ex) {
            log.error("Recovery framework error for tool {}: {}", toolName, ex.getMessage(), ex);
            return Optional.empty();
        }
    }

    /**
     * 根据恢复决策构建返回结果。
     */
    private ToolExecutionResult buildRecoveryResult(RecoveryDecision decision, ToolResult originalResult) {
        String hint = decision.hintForLlm() != null ? decision.hintForLlm() : "";

        return switch (decision.action()) {
            case REPORT_TO_LLM -> ToolExecutionResult.of(
                    originalResult.withContent(originalResult.content() + "\n\n[Recovery Hint] " + hint, false));
            case RETRY_SAME -> ToolExecutionResult.of(
                    originalResult.withContent(originalResult.content() + "\n\n[Recovery] Retry was not automatically applied: " + hint, false));
            case ESCALATE_TO_USER -> ToolExecutionResult.of(
                    originalResult.withContent("[Requires User Intervention] " + hint, false));
            case ABORT_WITH_SUMMARY -> ToolExecutionResult.of(
                    originalResult.withContent("[Aborted] " + hint, false));
            case TRY_ALTERNATIVE -> ToolExecutionResult.of(
                    originalResult.withContent(originalResult.content()
                            + "\n\n[Recovery] Try alternative tool: " + decision.alternativeToolName()
                            + ". " + hint, false));
            case SIMPLIFY_TASK -> ToolExecutionResult.of(
                    originalResult.withContent(originalResult.content()
                            + "\n\n[Recovery] Task too complex, please simplify: " + hint, false));
        };
    }

    /**
     * 从 ToolResult 中提取退出码。
     * <p>
     * 优先从 metadata 中获取（成功路径下 BashTool 会设置）；
     * 降级从错误内容中解析（"Exit code: N" 格式）；
     * 超时场景返回 137。
     */
    private int extractExitCode(ToolResult result) {
        return result.exitCode() == null ? -1 : result.exitCode();
    }

    /**
     * 针对 Bash 工具错误结果尝试恢复。
     *
     * @return 若决策为 RETRY_SAME 且未达上限则递归重试并返回结果；
     *         若为其他决策则返回带 hint 的错误结果；
     *         若无策略匹配则返回 null（让原流程继续）。
     */
    private ToolExecutionResult attemptBashErrorRecovery(
            Tool tool, ToolInput input, ToolUseContext context,
            PermissionNotifier wsPusher, ToolResult errorResult,
            int attemptCount, long durationMs) {
        try {
            int exitCode = extractExitCode(errorResult);
            RecoveryContext recoveryContext = new RecoveryContext(
                    tool.getName(),
                    input.getRawData(),
                    errorResult,
                    attemptCount,
                    Duration.ofMillis(durationMs),
                    errorResult.content(),
                    exitCode
            );
            Optional<RecoveryDecision> recovery = recoveryFramework.attemptRecovery(recoveryContext);
            if (recovery.isEmpty()) {
                return null; // 无策略匹配，走原流程
            }

            RecoveryDecision decision = recovery.get();

            // RETRY_SAME：自动重试（带指数退避延迟）
            if (decision.action() == RecoveryAction.RETRY_SAME && errorResult.isRetryable()
                    && attemptCount < MAX_RETRY_ATTEMPTS) {
                long delayMs = calculateRetryDelay(attemptCount);
                log.info("[Recovery] Bash tool retry #{} after {}ms delay (exitCode={}, hint={})",
                        attemptCount + 1, delayMs, exitCode, decision.hintForLlm());
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return ToolExecutionResult.of(errorResult);
                }
                return doExecute(tool, input, context, wsPusher, attemptCount + 1);
            }

            // 非重试决策或已达上限 → 附加 hint 信息返回给 LLM
            return enrichErrorWithHint(errorResult, decision);
        } catch (Exception ex) {
            log.error("[Recovery] Bash error recovery failed: {}", ex.getMessage(), ex);
            return null; // 恢复框架本身出错，走原流程
        }
    }

    /**
     * 将恢复决策的 hint 附加到错误结果中返回。
     */
    private ToolExecutionResult enrichErrorWithHint(ToolResult errorResult, RecoveryDecision decision) {
        String hint = decision.hintForLlm() != null ? decision.hintForLlm() : "";
        String enrichedContent = errorResult.content() + "\n\n[Recovery Hint] " + hint;
        return ToolExecutionResult.of(errorResult.withContent(enrichedContent, false));
    }

    /**
     * 计算重试延迟（指数退避）：1s, 2s, 4s...
     */
    private long calculateRetryDelay(int attemptCount) {
        return 1000L * (1L << (attemptCount - 1));
    }

    /**
     * 将权限请求冒泡转发给父代理的前端界面。
     *
     * @return null 表示父代理已批准（继续执行工具）；非 null 表示拒绝或错误
     */
    private ToolExecutionResult forwardPermissionToParent(
            Tool tool, ToolInput processedInput, PermissionDecision decision,
            ToolUseContext context, PermissionNotifier wsPusher) {
        try {
            String toolUseId = context.toolUseId() != null ? context.toolUseId() : tool.getName();
            // 通过子代理专用接口转发权限请求，携带 source 和 childSessionId 信息
            String childSessionId = context.sessionId();
            String reason = String.format("[From Sub-Agent] %s",
                    decision.reason() != null ? decision.reason() : "Tool requires permission");

            // The same durable interaction path is used for direct and bubbled requests.
            PermissionDecision result = permissionPipeline.requestPermission(
                    toolUseId, tool, processedInput, reason, wsPusher,
                    context.parentSessionId(), context.currentRunId(), childSessionId,
                    context.workingDirectory()).join();

            if (!result.isAllowed()) {
                log.info("Parent agent denied permission for tool={}", tool.getName());
                // ★ V4: Deny + Remember 也需要记住拒绝决策
                if (result.remember()) {
                    String denyProjectKey = context != null ? context.workingDirectory() : null;
                    permissionPipeline.rememberDecision(
                            tool, processedInput,
                            false,  // denied
                            result.rememberScope() != null ? result.rememberScope() : PermissionScope.SESSION,
                            denyProjectKey, context.sessionId(), context.currentRunId(), toolUseId);
                }
                // 通知前端（父会话）清除已显示的 changedFiles
                if (wsPusher != null) {
                    String targetSessionId = context.parentSessionId() != null
                            ? context.parentSessionId()
                            : context.sessionId();
                    if (targetSessionId != null) {
                        String denyToolUseId = context.toolUseId() != null
                                ? context.toolUseId()
                                : tool.getName();
                        wsPusher.sendToolPermissionDenied(
                                targetSessionId, denyToolUseId, tool.getName());
                    }
                }
                return ToolExecutionResult.of(ToolResult.permissionDenied(
                        "PERMISSION_PARENT_DENIED", "Permission denied by parent agent"));
            }

            // 记忆规则
            if (result.remember()) {
                permissionPipeline.rememberChildDecision(tool, processedInput, true, context, toolUseId);
            }

            return null; // null 表示继续执行工具
        } catch (Exception e) {
            log.error("Failed to forward permission to parent: {}", e.getMessage(), e);
            return ToolExecutionResult.of(ToolResult.internalError("PERMISSION_FORWARDING_FAILED",
                    "Permission forwarding failed: " + e.getMessage(), ToolResult.EffectState.NOT_STARTED));
        }
    }

    private static String permissionFailureCode(com.aicodeassistant.model.PermissionDecisionReason reason) {
        if (reason == null) return "PERMISSION_DENIED";
        return switch (reason) {
            case USER_DENIED -> "PERMISSION_USER_DENIED";
            case INTERACTION_EXPIRED -> "PERMISSION_EXPIRED";
            case INTERACTION_UNDELIVERABLE -> "PERMISSION_UNDELIVERABLE";
            case INTERACTION_CANCELLED -> "PERMISSION_CANCELLED";
            default -> "PERMISSION_DENIED";
        };
    }

    /**
     * 从工具输入中提取文件路径。
     * <p>
     * 依次尝试从输入参数中查找 filePath、path、file_path 等字段。
     *
     * @param input 工具输入
     * @return 文件路径，未找到返回 null
     */
    @SuppressWarnings("unchecked")
    private String extractFilePathFromInput(ToolInput input) {
        Object raw = input.getRawData();
        if (!(raw instanceof Map)) {
            return null;
        }
        Map<String, Object> map = (Map<String, Object>) raw;
        for (String key : new String[]{"file_path", "filePath", "path", "notebook_path"}) {
            Object val = map.get(key);
            if (val instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }

    private record DeclaredOutput(String path, String operation) {}

    @SuppressWarnings("unchecked")
    private List<DeclaredOutput> declareOutputs(String toolName, ToolInput input,
                                                ToolUseContext context) throws Exception {
        if (artifactManifestService == null || context.currentRunId() == null) return List.of();
        List<Map<String,Object>> declarations = new ArrayList<>();
        if (Set.of("Write", "Edit", "FileWrite", "FileEdit", "NotebookEdit").contains(toolName)) {
            String path = extractFilePathFromInput(input);
            if (path != null) {
                Path target = Path.of(path);
                if (!target.isAbsolute()) target = Path.of(context.workingDirectory()).resolve(target).normalize();
                declarations.add(Map.of("path", path,
                        "operation", Files.exists(target) ? "modified" : "created",
                        "requiredValidatorId", "sha256"));
            }
        } else if (("Bash".equals(toolName) || "Python".equals(toolName))
                && input.getRawData() instanceof Map<?,?> raw
                && raw.get("declared_outputs") instanceof List<?> outputs) {
            if (Boolean.TRUE.equals(raw.get("is_background")) && !outputs.isEmpty())
                throw new IllegalArgumentException("BACKGROUND_DECLARED_OUTPUTS_UNSUPPORTED");
            for (Object candidate : outputs) {
                if (!(candidate instanceof Map<?,?> output))
                    throw new IllegalArgumentException("declared_outputs entries must be objects");
                Object path = output.get("path");
                if (!(path instanceof String value) || value.isBlank())
                    throw new IllegalArgumentException("declared output path is required");
                Map<String,Object> declaration = new HashMap<>();
                declaration.put("path", value);
                declaration.put("operation", String.valueOf(
                        output.containsKey("operation") ? output.get("operation") : "modified"));
                declaration.put("requiredValidatorId", String.valueOf(
                        output.containsKey("requiredValidatorId")
                                ? output.get("requiredValidatorId") : "sha256"));
                declarations.add(declaration);
            }
        }
        List<DeclaredOutput> result = new ArrayList<>();
        for (Map<String,Object> declaration : declarations) {
            String path=String.valueOf(declaration.get("path"));
            String operation=String.valueOf(declaration.get("operation"));
            String validator=String.valueOf(declaration.get("requiredValidatorId"));
            var entry=artifactManifestService.declare(context.currentRunId(),context.sessionId(),
                    context.toolUseId()==null?toolName:context.toolUseId(),path,operation,validator,
                    context.workingDirectory());
            if("deleted".equals(entry.operation())) {
                artifactManifestService.sealFromFile(context.currentRunId(),path,context.workingDirectory());
            }
            result.add(new DeclaredOutput(path,entry.operation()));
        }
        return List.copyOf(result);
    }

    /**
     * JSON Schema 结构化验证 — 管线阶段 1.5。
     * <p>
     * 使用 networknt json-schema-validator 对工具输入进行结构化验证。
     * 降级策略：Schema 验证过程本身出异常时（如 Schema 格式错误），仅 warn 日志不阻断执行。
     *
     * @param tool  工具实例
     * @param input 工具输入参数
     */
    @SuppressWarnings("unchecked")
    private void validateSchema(Tool tool, Map<String, Object> input) {
        Map<String, Object> schema = tool.getSchema();
        if (schema == null || schema.isEmpty()) {
            return; // 空映射 = 跳过验证
        }

        try {
            JsonNode schemaNode = objectMapper.valueToTree(schema);
            JsonNode inputNode = objectMapper.valueToTree(input);

            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
            JsonSchema jsonSchema = factory.getSchema(schemaNode);
            Set<ValidationMessage> errors = jsonSchema.validate(inputNode);

            if (!errors.isEmpty()) {
                StringBuilder sb = new StringBuilder("Schema validation failed for tool "
                        + tool.getName() + ":");
                for (ValidationMessage msg : errors) {
                    sb.append(" ").append(msg.getMessage()).append(";");
                }
                throw new ToolInputValidationException(sb.toString());
            }
        } catch (ToolInputValidationException e) {
            throw e; // 验证失败直接上抛
        } catch (Exception e) {
            log.warn("Schema validation error for tool {} (degraded, continuing): {}",
                    tool.getName(), e.getMessage());
        }
    }
}
