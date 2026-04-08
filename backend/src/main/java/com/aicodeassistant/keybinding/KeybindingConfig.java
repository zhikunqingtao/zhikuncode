package com.aicodeassistant.keybinding;

/**
 * 键盘绑定配置 — 用户可自定义的快捷键映射。
 *
 * @param key     按键组合（如 "ctrl+c", "escape", "enter"）
 * @param action  动作标识（如 "app:interrupt", "chat:submit"）
 * @param context 生效上下文
 * @param when    条件表达式（是否启用）
 * @see <a href="SPEC §4.8">键盘绑定系统</a>
 */
public record KeybindingConfig(
        String key,
        String action,
        KeybindingContext context,
        boolean when
) {
    /** 便捷构造 — 默认启用 */
    public KeybindingConfig(String key, String action, KeybindingContext context) {
        this(key, action, context, true);
    }
}
