package com.aicodeassistant.tool;

import com.aicodeassistant.hook.HookRegistry;
import com.aicodeassistant.hook.HookService;
import com.aicodeassistant.model.*;
import com.aicodeassistant.permission.PermissionPipeline;
import com.aicodeassistant.permission.PermissionRuleRepository;
import com.aicodeassistant.security.SensitiveDataFilter;
import com.aicodeassistant.websocket.WebSocketController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 工具执行管线 — 6 阶段执行流程。
 * <p>
 * 阶段 1: Schema 输入验证
 * 阶段 2: 工具自定义验证
 * 阶段 2.5: 输入预处理 (backfill)
 * 阶段 3: PreToolUse 钩子
 * 阶段 4: 权限检查
 * 阶段 5: 工具调用
 * 阶段 6: 结果处理 + PostToolUse 钩子
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

    public ToolExecutionPipeline(HookService hookService, ObjectMapper objectMapper,
                                  PermissionPipeline permissionPipeline,
                                  PermissionRuleRepository permissionRuleRepository,
                                  SensitiveDataFilter sensitiveDataFilter) {
        this.hookService = hookService;
        this.objectMapper = objectMapper;
        this.permissionPipeline = permissionPipeline;
        this.permissionRuleRepository = permissionRuleRepository;
        this.sensitiveDataFilter = sensitiveDataFilter;
    }

    /**
     * 执行工具 — 完整 6 阶段管线。
     *
     * @param tool    工具实例
     * @param input   工具输入
     * @param context 执行上下文
     * @param wsPusher WebSocket 推送器（用于权限请求推送）
     * @return 工具执行结果
     */
    public ToolResult execute(Tool tool, ToolInput input, ToolUseContext context,
                               WebSocketController wsPusher) {
        return doExecute(tool, input, context, wsPusher);
    }

    /**
     * 执行工具 — 完整 6 阶段管线。
     *
     * @param tool    工具实例
     * @param input   工具输入
     * @param context 执行上下文
     * @return 工具执行结果
     */
    public ToolResult execute(Tool tool, ToolInput input, ToolUseContext context) {
        return doExecute(tool, input, context, null);
    }

    private ToolResult doExecute(Tool tool, ToolInput input, ToolUseContext context,
                                  WebSocketController wsPusher) {
        String toolName = tool.getName();
        long startTime = System.currentTimeMillis();

        try {
            // ── 阶段 1: Schema 输入验证 ──
            log.debug("Executing tool: {} (stage 1: validation)", toolName);

            // ── 阶段 2: 工具自定义验证 ──
            ValidationResult validation = tool.validateInput(input, context);
            if (!validation.isValid()) {
                log.warn("Tool input validation failed for {}: {} - {}",
                        toolName, validation.errorCode(), validation.errorMessage());
                return ToolResult.error(
                        "Input validation failed: " + validation.errorMessage());
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
                return ToolResult.error("Tool execution blocked by hook: " + hookResult.message());
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
                // 构建权限上下文
                PermissionContext permContext = permissionRuleRepository.buildContext(
                        PermissionMode.DEFAULT, false, false);
                PermissionDecision decision = permissionPipeline.checkPermission(
                        tool, processedInput, context, permContext);

                if (decision.isDenied()) {
                    log.info("Tool {} permission denied: {}", toolName, decision.reason());
                    return ToolResult.error("Permission denied: " + decision.reason());
                }

                if (decision.behavior() == PermissionBehavior.ASK) {
                    if (wsPusher == null || context.sessionId() == null) {
                        log.warn("Tool {} requires permission but no WebSocket pusher available, denying", toolName);
                        return ToolResult.error("Permission required but cannot prompt user");
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
                            wsPusher, context.sessionId()
                    ).join();

                    if (!userDecision.isAllowed()) {
                        log.info("Tool {} permission denied by user", toolName);
                        return ToolResult.error("Permission denied by user");
                    }

                    // 如果用户选择 "remember"，持久化规则
                    if (userDecision.remember()) {
                        permissionPipeline.rememberDecision(tool, processedInput,
                                true, userDecision.rememberScope() != null
                                        ? userDecision.rememberScope() : RuleScope.SESSION);
                    }
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
                return new ToolResult(truncated, result.isError(), result.metadata());
            }

            return result;

        } catch (ToolInputValidationException e) {
            log.warn("Tool {} input validation error: {}", toolName, e.getMessage());
            return ToolResult.error("Input validation error: " + e.getMessage());
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;
            log.error("Tool {} execution failed after {}ms: {}", toolName, durationMs, e.getMessage(), e);
            return ToolResult.error("Tool execution error: " + e.getMessage());
        }
    }
}
