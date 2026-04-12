package com.aicodeassistant.model;

/**
 * 权限模式 — 7 种模式（5 种外部 + 2 种内部）。
 * <p>
 * 语义定义:
 * <ul>
 *   <li>{@link #DEFAULT} — 所有写操作弹窗确认</li>
 *   <li>{@link #PLAN} — 只读操作自动允许，写操作弹窗确认</li>
 *   <li>{@link #ACCEPT_EDITS} — 文件编辑自动允许，其他写操作弹窗确认</li>
 *   <li>{@link #DONT_ASK} — 不弹窗。只读操作自动允许，写操作自动拒绝</li>
 *   <li>{@link #BYPASS_PERMISSIONS} — 所有操作自动允许（危险命令仍触发安全检查）</li>
 *   <li>{@link #AUTO} — LLM 分类器自动判定，不确定时弹窗确认</li>
 *   <li>{@link #BUBBLE} — 子代理内部模式，权限请求冒泡到父代理</li>
 * </ul>
 *
 * @see <a href="SPEC §3.4.1">权限模式</a>
 */
public enum PermissionMode {
    // ===== 外部模式（用户可选） =====
    /** 标准模式 — 写操作需要用户确认 */
    DEFAULT,
    /** 计划模式 — 只读操作自动允许 */
    PLAN,
    /** 接受编辑模式 — 文件编辑自动允许 */
    ACCEPT_EDITS,
    /** 不询问模式 — 不弹窗，写操作自动拒绝 */
    DONT_ASK,
    /** 绕过权限模式 — 所有操作自动允许（REST API 非交互场景） */
    BYPASS_PERMISSIONS,

    // ===== 内部模式（程序控制） =====
    /** 自动模式 — LLM 分类器驱动 */
    AUTO,
    /** 冒泡模式 — 子代理权限请求转发给父代理 */
    BUBBLE
}
