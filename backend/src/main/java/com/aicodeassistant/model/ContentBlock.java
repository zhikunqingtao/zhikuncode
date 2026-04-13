package com.aicodeassistant.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 内容块 — sealed interface 保证类型穷举。
 * 含 text / tool_use / tool_result / image / thinking / redacted_thinking 六种子类型。
 *
 * ★ 审查修复 [S2]：Jackson 多态序列化配置 ★
 * sealed interface 需要 @JsonTypeInfo + @JsonSubTypes 才能正确序列化/反序列化。
 * 使用 NAME 策略将类型信息写入 JSON 的 "type" 字段。
 *
 * @see <a href="SPEC §5.1">消息模型</a>
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
            @JsonProperty("is_error") boolean isError
    ) implements ContentBlock {}

    record ImageBlock(
            String mediaType,
            String base64Data,
            int width,
            int height
    ) implements ContentBlock {
        /** 向后兼容: 无尺寸信息时使用默认值 */
        public ImageBlock(String mediaType, String base64Data) {
            this(mediaType, base64Data, 0, 0);
        }
    }

    record ThinkingBlock(
            String thinking
    ) implements ContentBlock {}

    record RedactedThinkingBlock(
            String data
    ) implements ContentBlock {}
}
