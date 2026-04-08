package com.aicodeassistant.keybinding;

/**
 * 键盘绑定上下文 — 定义快捷键生效的场景。
 * <p>
 * 对应源码: src/keybindings/schema.ts KeybindingContext
 * <p>
 * v1.65.0 H-01: 20 种上下文 (新增 SCROLL + MESSAGE_ACTIONS)
 *
 * @see <a href="SPEC §4.8">键盘绑定系统</a>
 */
public enum KeybindingContext {
    GLOBAL,             // 全局（任意场景）
    CHAT,               // 聊天输入模式
    AUTOCOMPLETE,       // 自动补全模式
    CONFIRMATION,       // 权限确认模式
    HELP,               // 帮助页面
    TRANSCRIPT,         // 对话记录浏览
    HISTORY_SEARCH,     // 历史搜索模式
    TASK,               // 任务面板
    THEME_PICKER,       // 主题选择器
    SETTINGS,           // 设置面板
    TABS,               // 标签页模式
    SCROLL,             // 页面滚动与文本选择 (v1.65.0 H-01 新增)
    ATTACHMENTS,        // 附件管理
    FOOTER,             // 底部面板
    MESSAGE_SELECTOR,   // 消息选择器
    MESSAGE_ACTIONS,    // 消息操作导航 (v1.65.0 H-01 新增)
    DIFF_DIALOG,        // Diff 对话框
    MODEL_PICKER,       // 模型选择器
    SELECT,             // 通用选择组件
    PLUGIN              // 插件面板
}
