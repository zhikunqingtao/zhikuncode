package com.aicodeassistant.model;

/**
 * 权限规则来源类型 — 标识规则的配置层级（按优先级排列）。
 * <p>
 * 8 种来源对应不同的配置层级，优先级从高到低决定最终行为。
 *
 */
public enum PermissionRuleSource {
    /** 用户全局配置 (~/.config/.../settings.json) */
    USER_SETTINGS,
    /** 项目级配置 (.zhikun/settings.json) */
    PROJECT_SETTINGS,
    /** 本地配置 (.zhikun/settings.local.json) */
    LOCAL_SETTINGS,
    /** 特性标志推送的设置 */
    FLAG_SETTINGS,
    /** 企业策略 (MDM 推送) */
    POLICY_SETTINGS,
    /** CLI 命令行参数 (--allowedTools 等) */
    CLI_ARG,
    /** Slash 命令设置 (/permissions) */
    COMMAND,
    /** 会话内临时设置 (用户单次授权) */
    SESSION,

    /** 系统默认 */
    SYSTEM_DEFAULT,
    /** Hook 注入 */
    HOOK,
    /** MCP 服务器注入 */
    MCP,
    /** 分类器注入 */
    CLASSIFIER,
    /** 沙箱覆盖 */
    SANDBOX
}
