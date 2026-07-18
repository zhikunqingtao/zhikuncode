package com.aicodeassistant.tool;

import com.aicodeassistant.hook.HookRegistry;
import com.aicodeassistant.hook.HookService;
import com.aicodeassistant.authorization.AuthorizationException;
import com.aicodeassistant.authorization.AuthorizationService;
import com.aicodeassistant.authorization.AuthorizedOperation;
import com.aicodeassistant.authorization.FrozenToolInput;
import com.aicodeassistant.authorization.FrozenToolInputFactory;
import com.aicodeassistant.authorization.ToolExecutionGateway;
import com.aicodeassistant.authorization.ToolExecutionGateway.AdmissionException;
import com.aicodeassistant.authorization.PreparedOperation;
import com.aicodeassistant.model.*;
import com.aicodeassistant.interaction.DurableInteractionService.InteractionOperationException;
import com.aicodeassistant.permission.PermissionNotifier;
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
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 工具执行的唯一入口。
 * <p>
 * 输入依次经过 Schema/工具验证、backfill、可修改输入的前置钩子和二次验证；
 * 之后冻结输入、生成操作描述、执行只可收紧的安全钩子，并通过授权网关完成最终复检与调用。
 * 结果再经过展示钩子、敏感数据过滤和持久化后返回。
 */
@Component
public class ToolExecutionPipeline {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutionPipeline.class);

    private final HookService hookService;
    private final ObjectMapper objectMapper;
    private final SensitiveDataFilter sensitiveDataFilter;
    private final FrozenToolInputFactory frozenInputs;
    private final AuthorizationService authorizationService;
    private final ToolExecutionGateway gateway;
    private final ToolRecoveryFramework recoveryFramework;
    @Nullable
    private final RunTracker runTracker;
    @Nullable private final ArtifactManifestService artifactManifestService;

    @Autowired
    public ToolExecutionPipeline(HookService hookService, ObjectMapper objectMapper,
                                  SensitiveDataFilter sensitiveDataFilter,
                                  FrozenToolInputFactory frozenInputs,
                                  AuthorizationService authorizationService,
                                  ToolExecutionGateway gateway,
                                  ToolRecoveryFramework recoveryFramework,
                                  @Lazy @Nullable RunTracker runTracker,
                                  @Lazy @Nullable ArtifactManifestService artifactManifestService) {
        this.hookService = hookService;
        this.objectMapper = objectMapper;
        this.sensitiveDataFilter = sensitiveDataFilter;
        this.frozenInputs = frozenInputs;
        this.authorizationService = authorizationService;
        this.gateway = gateway;
        this.recoveryFramework = recoveryFramework;
        this.runTracker = runTracker;
        this.artifactManifestService = artifactManifestService;
    }

    /**
     * 执行工具完整管线。
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
     * 执行工具完整管线。
     *
     * @param tool    工具实例
     * @param input   工具输入
     * @param context 执行上下文
     * @return 工具执行结果
     */
    public ToolExecutionResult execute(Tool tool, ToolInput input, ToolUseContext context) {
        return doExecute(tool, input, context, null, 1);
    }

    private ToolExecutionResult doExecute(Tool tool, ToolInput input, ToolUseContext context,
                                  PermissionNotifier wsPusher, int attemptCount) {
        // 回退: 若调用方未传 wsPusher，从 context 中获取
        PermissionNotifier effectivePusher = wsPusher != null
                ? wsPusher : context.permissionNotifier();
        String toolName = tool.getName();
        long startTime = System.currentTimeMillis();
        FrozenToolInput frozen = null;

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
                    log.warn("Input-transform hook returned invalid JSON: tool={}", toolName, e);
                    return ToolExecutionResult.of(ToolResult.validationError(
                            "HOOK_INPUT_INVALID", "Input-transform hook returned invalid JSON: " + e.getMessage()));
                }
            }

            // 输入转换钩子不属于可信输入源，必须重新执行完整 Schema 和工具校验。
            validateSchema(tool, processedInput.getRawData());
            ValidationResult transformedValidation = tool.validateInput(processedInput, context);
            if (!transformedValidation.isValid()) {
                return ToolExecutionResult.of(ToolResult.validationError(
                        transformedValidation.errorCode() == null ? "TOOL_INPUT_INVALID" : transformedValidation.errorCode(),
                        "Transformed input validation failed: " + transformedValidation.errorMessage()));
            }

            // ── 阶段 4: 冻结输入并进入单一授权权威 ──
            frozen = frozenInputs.freeze(toolName, processedInput);
            processedInput = frozen.toToolInput(objectMapper);
            PreparedOperation prepared = authorizationService.prepare(tool, frozen, processedInput, context);
            HookRegistry.HookResult securityHook = hookService.executeSecurityConstraints(
                    toolName, frozen.canonicalJson(), context.sessionId(), Map.of(
                            "operationHash", prepared.descriptor().operationHash(),
                            "risk", prepared.descriptor().risk().name(),
                            "effects", prepared.descriptor().effects()));
            if (!securityHook.proceed()) {
                log.info("Security hook denied tool execution: tool={}, runId={}",
                        toolName, context.currentRunId());
                log.debug("Security hook denial detail: tool={}, reason={}",
                        toolName, securityHook.message());
                return ToolExecutionResult.of(ToolResult.permissionDenied("TOOL_BLOCKED_BY_SECURITY_HOOK",
                        "Tool execution blocked by security hook: " + securityHook.message()));
            }
            AuthorizedOperation authorized = authorizationService.authorizePrepared(
                    tool, frozen, processedInput, context, prepared);

            // ── 阶段 5: 工具调用 ──
            log.debug("Executing tool: {} (stage 5: call)", toolName);

            // 授权诊断和 tool_started 由 AuthorizationService/Gateway 统一记录。
            String currentRunId = context.currentRunId();

            List<DeclaredOutput> declaredOutputs;
            try {
                declaredOutputs = planOutputs(toolName, processedInput, context);
            } catch (Exception declarationError) {
                log.warn("Artifact pre-declaration rejected: tool={}, error={}", toolName,
                        declarationError.getMessage());
                return ToolExecutionResult.of(ToolResult.failed(
                        ToolResult.ToolFailureType.VALIDATION, "ARTIFACT_DECLARATION_FAILED",
                        declarationError.getMessage(), ToolResult.Retryability.NEVER,
                        ToolResult.EffectState.NOT_STARTED, null, Map.of()));
            }

            ToolResult result = gateway.execute(tool, authorized, context,
                    () -> {
                        try {
                            declareOutputsInCurrentTransaction(declaredOutputs, toolName, context);
                        } catch (RuntimeException admissionFailure) {
                            throw new AdmissionException("ARTIFACT_DECLARATION_FAILED", admissionFailure);
                        }
                    });

            // 工具可能在文件副作用已提交后才报告错误（例如原子移动后的校验失败）。此时仍需封存产物，
            // 让数据库反映真实工作区状态，并避免模型因“假失败”重复写入。
            if ((!result.isError() || result.effectState() == ToolResult.EffectState.APPLIED)
                    && artifactManifestService != null && context.currentRunId() != null) {
                for (DeclaredOutput output : declaredOutputs) {
                    if ("deleted".equals(output.operation())) continue; // 已根据删除前字节完成封存。
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

            // 只持久化最终返回给模型和用户的映射、展示处理、脱敏及截断后结果。
            recordToolResult(currentRunId, toolName, processedInput, context, result);

            // ── 阶段 7: contextModifier 提取与应用 ──
            var modifier = result.getContextModifier();
            ToolUseContext updatedContext = null;
            if (modifier != null) {
                updatedContext = modifier.apply(context);
                log.debug("Tool {} applied contextModifier", toolName);
            }

            return ToolExecutionResult.of(result.toSerializable(), updatedContext);

        } catch (AuthorizationException denied) {
            log.info("Tool {} authorization ended: code={}", toolName, denied.code());
            if (effectivePusher != null && context.sessionId() != null) {
                effectivePusher.sendToolPermissionDenied(context.sessionId(),
                        context.toolUseId() == null ? toolName : context.toolUseId(), toolName);
            }
            return ToolExecutionResult.of(ToolResult.permissionDenied(denied.code(), denied.getMessage()));
        } catch (AdmissionException admissionFailure) {
            log.warn("Tool admission rejected: tool={}, runId={}, toolUseId={}, code={}",
                    toolName, context.currentRunId(), context.toolUseId(), admissionFailure.code());
            log.debug("Tool admission rejection detail: tool={}", toolName, admissionFailure);
            return ToolExecutionResult.of(ToolResult.failed(
                    ToolResult.ToolFailureType.VALIDATION, admissionFailure.code(),
                    "Tool admission failed before execution: " + admissionFailure.getCause().getMessage(),
                    ToolResult.Retryability.NEVER, ToolResult.EffectState.NOT_STARTED, null, Map.of()));
        } catch (com.aicodeassistant.config.database.SqliteConfig.DatabaseWriteUnavailableException unavailable) {
            log.warn("Authorization database unavailable: tool={}, runId={}, toolUseId={}, code={}",
                    toolName, context.currentRunId(), context.toolUseId(), unavailable.code());
            ToolResult.Retryability retryability = tool.isReadOnly(input)
                    ? ToolResult.Retryability.SAFE_READ_ONLY
                    : ToolResult.Retryability.IDEMPOTENCY_REQUIRED;
            return ToolExecutionResult.of(ToolResult.failed(
                    ToolResult.ToolFailureType.INTERNAL, unavailable.code(),
                    "Authorization state is temporarily unavailable",
                    retryability, ToolResult.EffectState.NOT_STARTED,
                    null, Map.of()));
        } catch (InteractionOperationException interactionFailure) {
            log.error("Permission interaction operation failed: tool={}, runId={}, toolUseId={}, code={}",
                    toolName, context.currentRunId(), context.toolUseId(), interactionFailure.code(),
                    interactionFailure);
            boolean invalidPayload = "INTERACTION_PAYLOAD_INVALID".equals(interactionFailure.code());
            return ToolExecutionResult.of(ToolResult.failed(
                    invalidPayload ? ToolResult.ToolFailureType.VALIDATION : ToolResult.ToolFailureType.INTERNAL,
                    interactionFailure.code(), invalidPayload
                            ? "Permission interaction payload is invalid"
                            : "Permission interaction could not be persisted",
                    ToolResult.Retryability.NEVER, ToolResult.EffectState.NOT_STARTED,
                    null, Map.of()));
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
        } finally {
            if (frozen != null) frozen.close();
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

    private void recordToolResult(String runId, String toolName, ToolInput processedInput,
                                  ToolUseContext context, ToolResult result) {
        if (runId == null || runTracker == null) return;
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("toolName", toolName);
            payload.put("toolUseId", context.toolUseId() != null ? context.toolUseId() : "");
            payload.put("isError", result.isError());
            payload.put("schemaVersion", 2);
            payload.put("executionStatus", result.executionStatus().name().toLowerCase());
            if (result.failureCode() != null) payload.put("failureCode", result.failureCode());
            payload.put("effectState", result.effectState().name().toLowerCase());
            payload.put("retryability", result.retryability().name().toLowerCase());
            payload.put("outputPreview", result.outputPreview());
            payload.put("outputTruncated", result.outputTruncated());
            String filePath = extractFilePathFromInput(processedInput);
            if (filePath != null) payload.put("filePath", filePath);
            runTracker.recordEvent(runId, "tool_result", payload);
            if (result.executionStatus() == ToolResult.ExecutionStatus.TIMED_OUT) {
                runTracker.recordEvent(runId, "process_timed_out", Map.of(
                        "toolName", toolName, "toolUseId", context.toolUseId() == null ? "" : context.toolUseId(),
                        "failureCode", result.failureCode() == null ? "PROCESS_DEADLINE_EXCEEDED" : result.failureCode(),
                        "terminationConfirmed", result.metadata().getOrDefault("terminationConfirmed", false)));
            } else if (result.executionStatus() == ToolResult.ExecutionStatus.CANCELLED) {
                runTracker.recordEvent(runId, "process_cancelled", Map.of(
                        "toolName", toolName, "toolUseId", context.toolUseId() == null ? "" : context.toolUseId(),
                        "failureCode", result.failureCode() == null ? "PROCESS_CANCELLED" : result.failureCode(),
                        "effectState", result.effectState().name().toLowerCase()));
            }
        } catch (Exception failure) {
            log.warn("Failed to record tool_result event: tool={}, runId={}, toolUseId={}",
                    toolName, runId, context.toolUseId(), failure);
        }
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

            // 管线无法证明递归重试时钩子、Shell 状态和动态授权事实未变化，因此仅返回恢复提示，
            // 由模型发起新的 toolUseId 并重新完成验证与授权。
            if (decision.action() == RecoveryAction.RETRY_SAME && errorResult.isRetryable()) {
                return enrichErrorWithHint(errorResult, decision);
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

    private record DeclaredOutput(String path, String operation, String validator,
                                  String deleteSealHash) {}

    @SuppressWarnings("unchecked")
    private List<DeclaredOutput> planOutputs(String toolName, ToolInput input,
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
            String operation=ArtifactManifestService.normalizeOperation(
                    String.valueOf(declaration.get("operation")));
            String validator=String.valueOf(declaration.get("requiredValidatorId"));
            String deleteSealHash = null;
            if ("deleted".equals(operation)) {
                ArtifactManifestService.DeleteSeal seal = artifactManifestService.prepareDeleteSeal(
                        path, context.workingDirectory());
                path = seal.canonicalPath();
                deleteSealHash = seal.sha256();
            }
            result.add(new DeclaredOutput(path, operation, validator, deleteSealHash));
        }
        return List.copyOf(result);
    }

    private void declareOutputsInCurrentTransaction(List<DeclaredOutput> outputs, String toolName,
                                                    ToolUseContext context) {
        if (artifactManifestService == null) return;
        for (DeclaredOutput output : outputs) {
            var entry = artifactManifestService.declareInCurrentTransaction(context.currentRunId(),
                    context.sessionId(), context.toolUseId() == null ? toolName : context.toolUseId(),
                    output.path(), output.operation(), output.validator(), context.workingDirectory());
            if ("deleted".equals(entry.operation())) {
                try {
                    artifactManifestService.sealDeleteInCurrentTransaction(context.currentRunId(),
                            output.path(), output.deleteSealHash(), context.workingDirectory());
                } catch (Exception failure) {
                    throw new IllegalStateException("ARTIFACT_DELETE_SEAL_FAILED", failure);
                }
            }
        }
    }

    /**
     * JSON Schema 结构化验证 — 管线阶段 1.5。
     * <p>
     * 使用 networknt json-schema-validator 对工具输入进行结构化验证。
     * Schema 定义或验证器本身异常时 fail-closed，防止未验证输入进入授权和执行阶段。
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
            log.error("Schema validation infrastructure failed: tool={}", tool.getName(), e);
            throw new ToolInputValidationException(
                    "Schema validation could not be completed for tool " + tool.getName(), e);
        }
    }
}
