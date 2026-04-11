package com.aicodeassistant.tool;

import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * 工具执行结果 — 包含内容、错误标志和元数据。
 * <p>
 * 支持链式调用 withMetadata() 添加元数据。
 *
 * @see <a href="SPEC §3.2.1">工具接口定义</a>
 */
public record ToolResult(
        String content,
        boolean isError,
        Map<String, Object> metadata
) {

    /** 成功结果 */
    public static ToolResult success(String content) {
        return new ToolResult(content, false, new HashMap<>());
    }

    /** 成功结果（带元数据） */
    public static ToolResult success(String content, Map<String, Object> metadata) {
        return new ToolResult(content, false, new HashMap<>(metadata));
    }

    /** 错误结果 */
    public static ToolResult error(String message) {
        return new ToolResult(message, true, new HashMap<>());
    }

    /** 错误结果（带元数据） */
    public static ToolResult error(String message, Map<String, Object> metadata) {
        return new ToolResult(message, true, new HashMap<>(metadata));
    }

    /** 文本结果 */
    public static ToolResult text(String content) {
        return success(content);
    }

    /** 图片结果 */
    public static ToolResult image(String base64, String mimeType, long originalSize) {
        var metadata = new HashMap<String, Object>();
        metadata.put("type", "image");
        metadata.put("mimeType", mimeType);
        metadata.put("originalSize", originalSize);
        return new ToolResult(base64, false, metadata);
    }

    /** 链式添加元数据 — 返回新的 ToolResult */
    public ToolResult withMetadata(String key, Object value) {
        var newMetadata = new HashMap<>(this.metadata);
        newMetadata.put(key, value);
        return new ToolResult(this.content, this.isError, newMetadata);
    }

    // ==================== contextModifier 支持 ====================

    private static final String CONTEXT_MODIFIER_KEY = "__contextModifier";

    /** 携带 contextModifier 的新 ToolResult（modifier 存入 metadata） */
    public ToolResult withContextModifier(UnaryOperator<ToolUseContext> modifier) {
        var newMetadata = new HashMap<>(this.metadata);
        newMetadata.put(CONTEXT_MODIFIER_KEY, modifier);
        return new ToolResult(this.content, this.isError, newMetadata);
    }

    /** 提取 contextModifier（可能为 null） */
    @SuppressWarnings("unchecked")
    public UnaryOperator<ToolUseContext> getContextModifier() {
        Object modifier = this.metadata.get(CONTEXT_MODIFIER_KEY);
        return modifier instanceof UnaryOperator ? (UnaryOperator<ToolUseContext>) modifier : null;
    }

    /** 返回可安全序列化的副本（去除不可序列化的 contextModifier） */
    public ToolResult toSerializable() {
        if (!this.metadata.containsKey(CONTEXT_MODIFIER_KEY)) return this;
        var cleanMeta = new HashMap<>(this.metadata);
        cleanMeta.remove(CONTEXT_MODIFIER_KEY);
        return new ToolResult(this.content, this.isError, cleanMeta);
    }
}
