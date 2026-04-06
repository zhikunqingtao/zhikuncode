package com.aicodeassistant.model;

/**
 * 权限行为枚举。
 *
 * @see <a href="SPEC §3.4.2">权限规则</a>
 */
public enum PermissionBehavior {
    ALLOW,
    DENY,
    ASK,
    /** 透传 — 中间状态，等待管线后续步骤处理 */
    PASSTHROUGH
}
