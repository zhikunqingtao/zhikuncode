package com.aicodeassistant.hook;

/**
 * 钩子事件类型枚举 — 定义系统支持的所有钩子事件。
 * <p>
 * 对照源码 loadPluginHooks.ts 的钩子类型定义。
 * <p>
 * ★ 审查修复 [B2]：新增 Agent/Compact/协作相关事件类型 ★
 *
 * @see <a href="SPEC section 3.10">Hook 系统</a>
 */
public enum HookEvent {

    // ===== 基础事件（原有） =====

    /** 工具调用前拦截 — 可修改输入或拒绝调用 */
    PRE_TOOL_USE,

    /** 工具调用后处理 — 可修改输出 */
    POST_TOOL_USE,

    /** 用户提交消息前 — 可修改消息或阻止提交 */
    USER_PROMPT_SUBMIT,

    /** 通知事件处理 */
    NOTIFICATION,

    /** 查询循环结束后处理 */
    STOP,

    /** 会话开始 */
    SESSION_START,

    /** 会话结束 */
    SESSION_END,

    // ===== Agent/协作事件（新增） =====

    /** 子代理任务完成 — 触发结果汇总和上下文清理 */
    TASK_COMPLETED,

    /** 协作代理空闲 — Coordinator 模式下检测到队友空闲可分配新任务 */
    TEAMMATE_IDLE,

    /** StopHooks 执行 — 查询循环结束前的轮结束判断（isNaturalEnd / hasPendingWork） */
    STOP_HOOKS,

    // ===== Compact/上下文事件（新增） =====

    /** 压缩前 — 允许 hook 保存压缩前的上下文快照 */
    PRE_COMPACT,

    /** 压缩后 — 允许 hook 校验压缩结果或触发下游操作 */
    POST_COMPACT;

    /**
     * 从字符串解析事件类型 — 支持 UPPER_CASE 和 kebab-case 格式。
     * <p>
     * 示例："PRE_TOOL_USE" 和 "pre-tool-use" 都能解析为 PRE_TOOL_USE。
     *
     * @param value 事件类型字符串
     * @return 对应的枚举值
     * @throws IllegalArgumentException 无法解析时抛出
     */
    public static HookEvent fromString(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("HookEvent value cannot be null or blank");
        }
        // 尝试直接解析 UPPER_CASE
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException e) {
            // 尝试 kebab-case → UPPER_CASE 转换
            String normalized = value.replace("-", "_").toUpperCase();
            return valueOf(normalized);
        }
    }
}
