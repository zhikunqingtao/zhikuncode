package com.aicodeassistant.prompt;

/**
 * 系统提示分段 — 带缓存控制标记。
 * <p>
 * 对齐原版 cache_control: {type: "ephemeral"} 机制：
 * - cacheable=true 的段标记为可缓存（静态/半静态段）
 * - cacheable=false 的段不缓存（动态段）
 *
 * @param name      段名称（用于调试和日志）
 * @param content   段文本内容
 * @param cacheable 是否可缓存（Anthropic 使用 cache_control: {type: "ephemeral"}）
 */
public record SystemPromptSegment(String name, String content, boolean cacheable) {

    /** 创建可缓存段 */
    public static SystemPromptSegment cached(String name, String content) {
        return new SystemPromptSegment(name, content, true);
    }

    /** 创建不可缓存段 */
    public static SystemPromptSegment dynamic(String name, String content) {
        return new SystemPromptSegment(name, content, false);
    }
}
