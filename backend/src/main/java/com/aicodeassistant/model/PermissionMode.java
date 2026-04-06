package com.aicodeassistant.model;

/**
 * 权限模式 — 7 种模式（5 种外部 + 2 种内部）。
 *
 * @see <a href="SPEC §3.4.1">权限模式</a>
 */
public enum PermissionMode {
    // ===== 外部模式（用户可选） =====
    DEFAULT,
    PLAN,
    ACCEPT_EDITS,
    DONT_ASK,
    BYPASS_PERMISSIONS,

    // ===== 内部模式（程序控制） =====
    AUTO,
    BUBBLE
}
