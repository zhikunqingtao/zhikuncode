package com.aicodeassistant.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 工具执行管线 — 6 阶段执行流程。
 * <p>
 * 阶段 1: Schema 输入验证
 * 阶段 2: 工具自定义验证
 * 阶段 2.5: 输入预处理 (backfill)
 * 阶段 3: PreToolUse 钩子 (预留)
 * 阶段 4: 权限检查
 * 阶段 5: 工具调用
 * 阶段 6: 结果处理 + PostToolUse 钩子
 *
 * @see <a href="SPEC §3.2.1b">工具执行管线</a>
 */
@Component
public class ToolExecutionPipeline {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutionPipeline.class);

    /**
     * 执行工具 — 完整 6 阶段管线。
     *
     * @param tool    工具实例
     * @param input   工具输入
     * @param context 执行上下文
     * @return 工具执行结果
     */
    public ToolResult execute(Tool tool, ToolInput input, ToolUseContext context) {
        String toolName = tool.getName();
        long startTime = System.currentTimeMillis();

        try {
            // ── 阶段 1: Schema 输入验证 ──
            // P0 简化: 基础非空检查，完整 JSON Schema 验证在后续 Round 实现
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

            // ── 阶段 3: PreToolUse 钩子 (P1 预留) ──
            // Hook 系统在后续 Round 实现

            // ── 阶段 4: 权限检查 ──
            PermissionRequirement permReq = tool.getPermissionRequirement();
            if (permReq == PermissionRequirement.ALWAYS_ASK) {
                // P0: 权限对话框在后续 Round 实现（WebSocket 交互）
                // 当前版本默认放行
                log.debug("Tool {} requires permission (always_ask), auto-allowing in P0", toolName);
            }

            // ── 阶段 5: 工具调用 ──
            log.debug("Executing tool: {} (stage 5: call)", toolName);
            ToolResult result = tool.call(processedInput, context);

            // ── 阶段 6: 结果处理 ──
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
