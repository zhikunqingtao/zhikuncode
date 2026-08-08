package com.aicodeassistant.model;

/**
 * 权限模式 — 五种用户可见的会话策略。
 * <p>
 * 语义定义:
 * <ul>
 *   <li>{@link #DEFAULT} — 已注册 Project 内的普通文件编辑自动允许，其他写操作弹窗确认</li>
 *   <li>{@link #PLAN} — 安全的工作区读取自动允许，其他操作拒绝</li>
 *   <li>{@link #ACCEPT_EDITS} — 文件编辑自动允许，其他写操作弹窗确认</li>
 *   <li>{@link #DONT_ASK} — 不弹窗。安全读取、已有 Grant 及已选 Project 内普通文件编辑允许，
 *       其他需要交互的操作拒绝</li>
 *   <li>{@link #AUTO_APPROVE} — 自动批准所有已经通过安全与正确性检查的工具权限请求</li>
 * </ul>
 *
 */
public enum PermissionMode {
    // ===== 外部模式（用户可选） =====
    /** 标准模式 — Project 内普通文件编辑免重复确认，其他写操作需要确认 */
    DEFAULT,
    /** 计划模式 — 只读操作自动允许 */
    PLAN,
    /** 接受编辑模式 — 文件编辑自动允许 */
    ACCEPT_EDITS,
    /** 不询问模式 — 不弹窗，保留已有授权，需要新交互的操作结构化拒绝 */
    DONT_ASK,
    /** 自动批准模式 — 跳过人工权限交互，但不绕过安全检查 */
    AUTO_APPROVE
}
