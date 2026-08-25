package com.aicodeassistant.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 内容块 — sealed interface 保证类型穷举。
 * 含 text / tool_use / tool_result / image / thinking / redacted_thinking 六种子类型。
 *
 * ★ 审查修复 [S2]：Jackson 多态序列化配置 ★
 * sealed interface 需要 @JsonTypeInfo + @JsonSubTypes 才能正确序列化/反序列化。
 * 使用 NAME 策略将类型信息写入 JSON 的 "type" 字段。
 *
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type"
)
@JsonSubTypes({
    @JsonSubTypes.Type(value = ContentBlock.TextBlock.class, name = "text"),
    @JsonSubTypes.Type(value = ContentBlock.ToolUseBlock.class, name = "tool_use"),
    @JsonSubTypes.Type(value = ContentBlock.ToolResultBlock.class, name = "tool_result"),
    @JsonSubTypes.Type(value = ContentBlock.ThinkingBlock.class, name = "thinking"),
    @JsonSubTypes.Type(value = ContentBlock.ImageBlock.class, name = "image"),
    @JsonSubTypes.Type(value = ContentBlock.RedactedThinkingBlock.class, name = "redacted_thinking")
})
public sealed interface ContentBlock {

    record TextBlock(
            String text
    ) implements ContentBlock {}

    record ToolUseBlock(
            @JsonProperty("id") String id,
            @JsonProperty("name") String name,
            @JsonProperty("input") JsonNode input
    ) implements ContentBlock {}

    record ToolResultBlock(
            @JsonProperty("tool_use_id") String toolUseId,
            @JsonProperty("content") String content,
            @JsonProperty("is_error") boolean isError,
            @JsonProperty("metadata") Map<String, Object> metadata
    ) implements ContentBlock {
        public ToolResultBlock {
            Object structured = metadata == null ? null : metadata.get("structuredResult");
            if (structured instanceof Map<?, ?> structuredMap) {
                Map<String, Object> copy = new HashMap<>();
                structuredMap.forEach((key, value) -> {
                    if (key instanceof String stringKey) copy.put(stringKey, value);
                });
                metadata = Map.of("structuredResult", Collections.unmodifiableMap(copy));
            } else {
                metadata = Map.of();
            }
        }

        /** Existing and historical tool results remain valid without UI metadata. */
        public ToolResultBlock(String toolUseId, String content, boolean isError) {
            this(toolUseId, content, isError, Map.of());
        }
    }

    record ImageBlock(
            String mediaType,
            String base64Data,
            int width,
            int height,
            String url
    ) implements ContentBlock {
        /** 向后兼容: 无尺寸信息时使用默认值 */
        public ImageBlock(String mediaType, String base64Data) {
            this(mediaType, base64Data, 0, 0, null);
        }

        /** 向后兼容: 本地图片引用仍可携带尺寸。 */
        public ImageBlock(String mediaType, String base64Data, int width, int height) {
            this(mediaType, base64Data, width, height, null);
        }

        /** 已由服务端验证过的远程图片 URL。 */
        public static ImageBlock fromUrl(String mediaType, String url) {
            return new ImageBlock(mediaType, null, 0, 0, url);
        }
    }

    record ThinkingBlock(
            String thinking
    ) implements ContentBlock {}

    record RedactedThinkingBlock(
            String data
    ) implements ContentBlock {}
}
