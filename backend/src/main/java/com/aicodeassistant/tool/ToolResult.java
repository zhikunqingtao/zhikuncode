package com.aicodeassistant.tool;

import java.util.HashMap;
import java.util.Map;

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
}
