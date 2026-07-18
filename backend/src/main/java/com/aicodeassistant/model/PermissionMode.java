package com.aicodeassistant.model;

/**
 * 权限模式 — 四种用户可见的保守策略。
 * <p>
 * 语义定义:
 * <ul>
 *   <li>{@link #DEFAULT} — 所有写操作弹窗确认</li>
 *   <li>{@link #PLAN} — 安全的工作区读取自动允许，其他操作拒绝</li>
 *   <li>{@link #ACCEPT_EDITS} — 文件编辑自动允许，其他写操作弹窗确认</li>
 *   <li>{@link #DONT_ASK} — 不弹窗。只读操作自动允许，写操作自动拒绝</li>
 * </ul>
 *
 */
public enum PermissionMode {
    // ===== 外部模式（用户可选） =====
    /** 标准模式 — 写操作需要用户确认 */
    DEFAULT,
    /** 计划模式 — 只读操作自动允许 */
    PLAN,
    /** 接受编辑模式 — 文件编辑自动允许 */
    ACCEPT_EDITS,
    /** 不询问模式 — 不弹窗，需要交互的操作结构化拒绝 */
    DONT_ASK
}
