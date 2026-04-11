package com.aicodeassistant.tool;

import com.aicodeassistant.model.PermissionBehavior;

import java.util.List;
import java.util.Map;

/**
 * 所有工具的基础接口 — 后端核心方法 (ToolCore)。
 * <p>
 * 对照源码 Tool.ts 的 47 个属性/方法，Java 版本保留 Core 方法，
 * 渲染方法由前端 React 组件负责。
 * <p>
 * 工具是 LLM 与外部世界交互的唯一通道。
 *
 * @see <a href="SPEC §3.2.1">工具接口定义</a>
 */
public interface Tool {

    // ==================== 基础标识 ====================

    /** 工具名称 — API 调用标识符 */
    String getName();

    /** 工具别名列表 — 支持多名称引用 */
    default List<String> getAliases() { return List.of(); }

    /** 工具描述 — 展示给 LLM 的功能说明 */
    String getDescription();

    /** 输入参数的 JSON Schema */
    Map<String, Object> getInputSchema();

    /** 工具分组 — 用于 UI 分类展示 (read/edit/bash/mcp/...) */
    default String getGroup() { return "general"; }

    // ==================== 执行与权限 ====================

    /**
     * 执行工具 — 主调用方法。
     *
     * @param input   工具输入参数
     * @param context 工具执行上下文
     * @return 工具执行结果
     */
    ToolResult call(ToolInput input, ToolUseContext context);

    /** 是否需要用户权限确认 */
    default PermissionRequirement getPermissionRequirement() {
        return PermissionRequirement.NONE;
    }

    // ==================== 延迟加载与激活 ====================

    /** 是否应延迟加载此工具 */
    default boolean shouldDefer() { return false; }

    /** 是否始终加载（忽略 shouldDefer） */
    default boolean alwaysLoad() { return false; }

    /** 工具是否启用 */
    default boolean isEnabled() { return true; }

    // ==================== 并发安全性 ====================

    /** 是否可与其他工具并发执行 */
    default boolean isConcurrencySafe(ToolInput input) {
        return isReadOnly(input);
    }

    /** 中断行为 */
    default InterruptBehavior interruptBehavior() {
        return InterruptBehavior.BLOCK;
    }

    // ==================== 输入/输出控制 ====================

    /** 是否启用严格输入模式 */
    default boolean isStrict() { return false; }

    /** 最大结果内容长度 (字符数) */
    default int getMaxResultSizeChars() { return 200_000; }

    /** 回填可观察输入 */
    default ToolInput backfillObservableInput(ToolInput input) { return input; }

    /**
     * 工具自身权限检查 — 管线 Step 1c。
     * <p>
     * 默认实现返回 PASSTHROUGH，交给管线后续步骤处理。
     * 工具可覆写此方法实现子命令级规则匹配（如 BashTool）。
     */
    default PermissionBehavior checkPermissions(ToolInput input, ToolUseContext context) {
        return PermissionBehavior.PASSTHROUGH;
    }

    /** 是否需要用户交互（如 AskTool 需要用户输入） */
    default boolean requiresUserInteraction() { return false; }

    /**
     * AUTO 模式分类器输入 — 返回工具调用的安全相关摘要。
     * <p>
     * 返回 null 或空字符串表示无分类器相关输入，直接放行。
     */
    default String toAutoClassifierInput(ToolInput input) { return null; }

    // ==================== 安全性标记 ====================

    /** 是否为破坏性操作 */
    default boolean isDestructive(ToolInput input) { return false; }

    /** 是否为开放世界工具 */
    default boolean isOpenWorld() { return false; }

    /** 是否为只读操作 */
    default boolean isReadOnly(ToolInput input) { return false; }

    // ==================== AUTO 模式分类 ====================

    /** isSearchOrReadCommand 信息 */
    default SearchReadInfo isSearchOrReadCommand(ToolInput input) {
        return SearchReadInfo.NONE;
    }

    // ==================== 验证 ====================

    /** 验证工具输入 */
    default ValidationResult validateInput(ToolInput input, ToolUseContext context) {
        return ValidationResult.ok();
    }

    /** 获取操作路径 */
    default String getPath(ToolInput input) { return null; }

    // ==================== 展示与命名 ====================

    /** 用户面向名称 */
    default String userFacingName(ToolInput input) { return getName(); }

    /** 工具提示词片段 */
    default String prompt() { return getDescription(); }

    // ==================== 原版对齐三方法 ====================

    /**
     * 搜索提示 — 返回工具调用相关的搜索关键信息。
     * <p>
     * 对齐原版 Tool.ts searchHint()。
     * 用于文件读取工具返回文件路径，搜索工具返回搜索模式等。
     *
     * @param input 工具输入
     * @return 搜索提示字符串，或 null 表示无提示
     */
    default String searchHint(ToolInput input) { return null; }

    /**
     * 结果映射 — 对工具返回结果进行后处理转换。
     * <p>
     * 对齐原版 Tool.ts mapToolResult()。
     * 工具可通过覆写此方法对结果进行标准化、截断或富化处理。
     *
     * @param result 原始工具结果
     * @return 处理后的工具结果
     */
    default ToolResult mapToolResult(ToolResult result) { return result; }

    /**
     * 权限匹配器准备 — 为权限规则匹配提供工具特定上下文。
     * <p>
     * 对齐原版 Tool.ts preparePermissionMatcher()。
     * BashTool 可提取命令前缀用于匹配 alwaysAllow/deny 规则。
     *
     * @param input 工具输入
     * @return 权限行为指示，默认 PASSTHROUGH
     */
    default PermissionBehavior preparePermissionMatcher(ToolInput input) {
        return PermissionBehavior.PASSTHROUGH;
    }

    // ==================== 类型标记 ====================

    /** 是否为 MCP 工具 */
    default boolean isMcp() { return false; }

    // ==================== API 格式 ====================

    /** 转换为 API 工具定义格式 — 使用 prompt() 作为 LLM 可见描述 */
    default Map<String, Object> toToolDefinition() {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", getName(),
                        "description", prompt(),
                        "parameters", getInputSchema()
                )
        );
    }
}
