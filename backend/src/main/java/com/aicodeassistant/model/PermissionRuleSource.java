package com.aicodeassistant.model;

/**
 * 权限规则来源类型。
 * 完整 8 种来源详见 §4.9.0。
 *
 * @see <a href="SPEC §3.4.2">权限规则</a>
 */
public enum PermissionRuleSource {
    USER_GLOBAL,
    USER_PROJECT,
    USER_SESSION,
    SYSTEM_DEFAULT,
    HOOK,
    MCP,
    CLASSIFIER,
    SANDBOX
}
