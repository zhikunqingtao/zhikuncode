package com.aicodeassistant.tool;

import com.aicodeassistant.hook.HookRegistry;
import com.aicodeassistant.hook.HookService;
import com.aicodeassistant.model.*;
import com.aicodeassistant.permission.PermissionModeManager;
import com.aicodeassistant.permission.PermissionNotifier;
import com.aicodeassistant.permission.PermissionPipeline;
import com.aicodeassistant.permission.PermissionRuleRepository;
import com.aicodeassistant.security.SensitiveDataFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

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
 * @see <a href="SPEC §3.2.1b">工具执行管线</a>
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

    public ToolExecutionPipeline(HookService hookService, ObjectMapper objectMapper,
                                  PermissionPipeline permissionPipeline,
                                  PermissionRuleRepository permissionRuleRepository,
                                  SensitiveDataFilter sensitiveDataFilter,
                                  PermissionModeManager permissionModeManager) {
        this.hookService = hookService;
        this.objectMapper = objectMapper;
        this.permissionPipeline = permissionPipeline;
        this.permissionRuleRepository = permissionRuleRepository;
        this.sensitiveDataFilter = sensitiveDataFilter;
        this.permissionModeManager = permissionModeManager;
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
        return doExecute(tool, input, context, wsPusher);
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
        return doExecute(tool, input, context, null);
    }

    private ToolExecutionResult doExecute(Tool tool, ToolInput input, ToolUseContext context,
                                  PermissionNotifier wsPusher) {
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
            if (permReq != PermissionRequirement.NONE) {
                // 构建权限上下文 — 从 PermissionModeManager 获取会话级模式
                PermissionMode sessionMode = permissionModeManager.getMode(context.sessionId());
                PermissionContext permContext = permissionRuleRepository.buildContext(
                        sessionMode, false, false);
                PermissionDecision decision = permissionPipeline.checkPermission(
                        tool, processedInput, context, permContext);

                if (decision.isDenied()) {
                    log.info("Tool {} permission denied: {}", toolName, decision.reason());
                    return ToolExecutionResult.of(ToolResult.error("Permission denied: " + decision.reason()));
                }

                if (decision.behavior() == PermissionBehavior.ASK) {
                    // NEW: 检查是否需要冒泡转发给父代理
                    if (decision.bubble() && context.parentSessionId() != null) {
                        log.info("Forwarding permission to parent session: tool={}, parentSession={}",
                                toolName, context.parentSessionId());
                        ToolExecutionResult bubbleResult = forwardPermissionToParent(
                                tool, processedInput, decision, context, effectivePusher);
                        if (bubbleResult != null) {
                            return bubbleResult; // 拒绝或错误
                        }
                        // null 表示父代理已批准，继续执行工具
                    } else {
                    if (effectivePusher == null || context.sessionId() == null) {
                        log.warn("Tool {} requires permission but no WebSocket pusher available, denying", toolName);
                        return ToolExecutionResult.of(ToolResult.error("Permission required but cannot prompt user"));
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
                        return ToolExecutionResult.of(ToolResult.error("Permission denied by user"));
                    }

                    // 如果用户选择 "remember"，持久化规则
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
            return ToolExecutionResult.of(ToolResult.error("Tool execution error: " + e.getMessage()));
        }
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

            // 通过父会话的 sessionId 转发权限请求
            CompletableFuture<PermissionDecision> parentDecision = permissionPipeline.requestPermission(
                    toolUseId, tool.getName(), inputMap,
                    String.format("[From Sub-Agent] %s",
                            decision.reason() != null ? decision.reason() : "Tool requires permission"),
                    wsPusher,
                    context.parentSessionId()  // 转发到父会话
            );

            PermissionDecision result = parentDecision.join();

            if (!result.isAllowed()) {
                log.info("Parent agent denied permission for tool={}", tool.getName());
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
