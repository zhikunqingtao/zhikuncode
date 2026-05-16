package com.aicodeassistant.tool;

import com.aicodeassistant.hook.HookRegistry;
import com.aicodeassistant.hook.HookService;
import com.aicodeassistant.model.*;
import com.aicodeassistant.permission.PermissionModeManager;
import com.aicodeassistant.permission.PermissionNotifier;
import com.aicodeassistant.permission.PermissionPipeline;
import com.aicodeassistant.permission.PermissionRuleRepository;
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
import org.springframework.stereotype.Component;

import java.time.Duration;
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

    public ToolExecutionPipeline(HookService hookService, ObjectMapper objectMapper,
                                  PermissionPipeline permissionPipeline,
                                  PermissionRuleRepository permissionRuleRepository,
                                  SensitiveDataFilter sensitiveDataFilter,
                                  PermissionModeManager permissionModeManager,
                                  ToolRecoveryFramework recoveryFramework) {
        this.hookService = hookService;
        this.objectMapper = objectMapper;
        this.permissionPipeline = permissionPipeline;
        this.permissionRuleRepository = permissionRuleRepository;
        this.sensitiveDataFilter = sensitiveDataFilter;
        this.permissionModeManager = permissionModeManager;
        this.recoveryFramework = recoveryFramework;
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
                return ToolExecutionResult.of(ToolResult.error(
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
                return ToolExecutionResult.of(ToolResult.error("Tool execution blocked by hook: " + hookResult.message()));
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
            log.info("[DIAG-PERM] Stage 4: tool={}, permReq={}, sessionId={}", toolName, permReq, context.sessionId());
            if (permReq != PermissionRequirement.NONE) {
                // 构建权限上下文 — 从 PermissionModeManager 获取会话级模式
                PermissionMode sessionMode = permissionModeManager.getMode(context.sessionId());
                PermissionContext permContext = permissionRuleRepository.buildContext(
                        sessionMode, false, false);
                PermissionDecision decision = permissionPipeline.checkPermission(
                        tool, processedInput, context, permContext);
                log.info("[DIAG-PERM] Stage 4 decision: tool={}, behavior={}, reason={}",
                        toolName, decision.behavior(), decision.reason());

                if (decision.isDenied()) {
                    log.info("Tool {} permission denied: {}", toolName, decision.reason());
                    // 通知前端清除已显示的 changedFiles
                    if (effectivePusher != null && context.sessionId() != null) {
                        String denyToolUseId = context.toolUseId() != null ? context.toolUseId() : toolName;
                        effectivePusher.sendToolPermissionDenied(context.sessionId(), denyToolUseId, toolName);
                    }
                    return ToolExecutionResult.of(ToolResult.error("Permission denied: " + decision.reason()));
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
                        return ToolExecutionResult.of(ToolResult.error(
                                "Permission required but cannot prompt user. "
                                + "This typically occurs in REST API mode \u2014 use WebSocket for interactive permission prompts."));
                    }
                    // 异步等待用户决策（在 VirtualThread 上 join() 不会阻塞平台线程）
                    String toolUseId = context.toolUseId() != null ? context.toolUseId() : toolName;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> inputMap = processedInput.getRawData() instanceof Map
                            ? (Map<String, Object>) processedInput.getRawData()
                            : Map.of("input", String.valueOf(processedInput.getRawData()));

                    PermissionDecision userDecision = permissionPipeline.requestPermission(
                            toolUseId, toolName, inputMap,
                            decision.reason() != null ? decision.reason() : "Tool requires permission",
                            effectivePusher, context.sessionId()
                    ).join();

                    if (!userDecision.isAllowed()) {
                        log.info("Tool {} permission denied by user", toolName);
                        effectivePusher.sendToolPermissionDenied(context.sessionId(), toolUseId, toolName);
                        return ToolExecutionResult.of(ToolResult.error("Permission denied by user"));
                    }

                    if (userDecision.remember()) {
                        permissionPipeline.rememberDecision(tool, processedInput,
                                true, userDecision.rememberScope() != null
                                        ? userDecision.rememberScope() : RuleScope.SESSION);
                    }
                    } // end else (non-bubble path)
                }
            }

            // ── 阶段 5: 工具调用 ──
            log.debug("Executing tool: {} (stage 5: call)", toolName);
            ToolResult result = tool.call(processedInput, context);

            // ── 阶段 5b: mapToolResult — 工具自定义结果映射 ──
            result = tool.mapToolResult(result);

            // ── 阶段 6: 结果处理 + PostToolUse 钩子 ──
            HookRegistry.HookResult postHookResult = hookService.executePostToolUse(
                    toolName,
                    result.content() != null ? result.content() : "",
                    context.sessionId());

            if (postHookResult.modifiedOutput() != null) {
                log.debug("Tool {} output modified by PostToolUse hook", toolName);
                result = new ToolResult(postHookResult.modifiedOutput(),
                        result.isError(), result.metadata());
            }

            // ── 阶段 6b: 敏感信息过滤 ──
            if (result.content() != null) {
                String filtered = sensitiveDataFilter.filter(result.content());
                if (!filtered.equals(result.content())) {
                    log.debug("Tool {} output contained sensitive data, filtered", toolName);
                    result = new ToolResult(filtered, result.isError(), result.metadata());
                }
            }

            long durationMs = System.currentTimeMillis() - startTime;
            log.info("Tool {} completed in {}ms (error={})",
                    toolName, durationMs, result.isError());

            // ── 阶段 5c: 错误结果恢复（仅 Bash 工具）──
            if (result.isError() && "Bash".equals(toolName)) {
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
                result = new ToolResult(truncated, result.isError(), result.metadata());
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
            return ToolExecutionResult.of(ToolResult.error("Input validation error: " + e.getMessage()));
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;
            log.error("Tool {} execution failed after {}ms: {}", toolName, durationMs, e.getMessage(), e);

            // ★ 尝试恢复—通过 ToolRecoveryFramework
            ToolResult failedResult = ToolResult.error("Tool execution error: " + e.getMessage());
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
                    ToolResult.error(originalResult.content() + "\n\n[Recovery Hint] " + hint));
            case RETRY_SAME -> ToolExecutionResult.of(
                    ToolResult.error(originalResult.content() + "\n\n[Recovery] Will retry: " + hint));
            case ESCALATE_TO_USER -> ToolExecutionResult.of(
                    ToolResult.error("[Requires User Intervention] " + hint));
            case ABORT_WITH_SUMMARY -> ToolExecutionResult.of(
                    ToolResult.error("[Aborted] " + hint));
            case TRY_ALTERNATIVE -> ToolExecutionResult.of(
                    ToolResult.error(originalResult.content()
                            + "\n\n[Recovery] Try alternative tool: " + decision.alternativeToolName()
                            + ". " + hint));
            case SIMPLIFY_TASK -> ToolExecutionResult.of(
                    ToolResult.error(originalResult.content()
                            + "\n\n[Recovery] Task too complex, please simplify: " + hint));
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
        // 1. 优先从 metadata 获取
        if (result.metadata() != null && result.metadata().containsKey("exitCode")) {
            Object exitCode = result.metadata().get("exitCode");
            if (exitCode instanceof Number) {
                return ((Number) exitCode).intValue();
            }
        }
        // 2. 从错误消息中解析
        return extractExitCodeFromContent(result.content());
    }

    /**
     * 从错误内容文本中解析退出码。
     * <p>
     * BashTool 错误格式：
     * - 超时："Command timed out after 120000ms"
     * - 执行失败："Exit code: 1\n..."
     */
    private int extractExitCodeFromContent(String content) {
        if (content == null || content.isEmpty()) return -1;
        // 超时 → 等价于 SIGKILL (137)
        if (content.startsWith("Command timed out")) return 137;
        // "Exit code: N" 格式
        if (content.startsWith("Exit code: ")) {
            try {
                int end = content.indexOf('\n');
                String codeStr = content.substring("Exit code: ".length(),
                        end > 0 ? end : content.length()).trim();
                return Integer.parseInt(codeStr);
            } catch (NumberFormatException e) {
                log.debug("Failed to parse exit code from content: {}", content.substring(0, Math.min(50, content.length())));
            }
        }
        return -1;
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
            if (decision.action() == RecoveryAction.RETRY_SAME && attemptCount < MAX_RETRY_ATTEMPTS) {
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
        return ToolExecutionResult.of(new ToolResult(enrichedContent, true, errorResult.metadata()));
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
    @SuppressWarnings("unchecked")
    private ToolExecutionResult forwardPermissionToParent(
            Tool tool, ToolInput processedInput, PermissionDecision decision,
            ToolUseContext context, PermissionNotifier wsPusher) {
        try {
            String toolUseId = context.toolUseId() != null ? context.toolUseId() : tool.getName();
            Map<String, Object> inputMap = processedInput.getRawData() instanceof Map
                    ? (Map<String, Object>) processedInput.getRawData()
                    : Map.of("input", String.valueOf(processedInput.getRawData()));

            // 通过子代理专用接口转发权限请求，携带 source 和 childSessionId 信息
            String childSessionId = context.sessionId();
            String riskLevel = (decision.reason() != null
                    && (decision.reason().contains("dangerous") || decision.reason().contains("destructive")))
                    ? "high" : "medium";
            String reason = String.format("[From Sub-Agent] %s",
                    decision.reason() != null ? decision.reason() : "Tool requires permission");

            // 使用子代理专用接口，确保前端收到 source: "subagent" + childSessionId
            wsPusher.sendPermissionRequestFromChild(
                    context.parentSessionId(), childSessionId,
                    toolUseId, tool.getName(), inputMap, riskLevel, reason);

            // 注册 pending future 以便接收前端响应
            CompletableFuture<PermissionDecision> future = new CompletableFuture<>();
            permissionPipeline.registerPendingRequest(toolUseId, future);
            CompletableFuture<PermissionDecision> parentDecision = future
                    .orTimeout(120, TimeUnit.SECONDS)
                    .exceptionally(ex -> {
                        log.warn("Parent permission request timed out for tool={}", tool.getName());
                        return PermissionDecision.denyByMode("Parent permission request timed out");
                    });

            PermissionDecision result = parentDecision.join();

            if (!result.isAllowed()) {
                log.info("Parent agent denied permission for tool={}", tool.getName());
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
                return ToolExecutionResult.of(ToolResult.error("Permission denied by parent agent"));
            }

            // 记忆规则
            if (result.remember()) {
                permissionPipeline.rememberDecision(tool, processedInput,
                        true, result.rememberScope() != null
                                ? result.rememberScope() : RuleScope.SESSION);
            }

            return null; // null 表示继续执行工具
        } catch (Exception e) {
            log.error("Failed to forward permission to parent: {}", e.getMessage(), e);
            return ToolExecutionResult.of(ToolResult.error("Permission forwarding failed: " + e.getMessage()));
        }
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
