package com.aicodeassistant.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 内容块 — sealed interface 保证类型穷举。
 * 含 text / tool_use / tool_result / image / thinking / redacted_thinking 六种子类型。
 *
 * @see <a href="SPEC §5.1">消息模型</a>
 */
public sealed interface ContentBlock {

    record TextBlock(
            String text
    ) implements ContentBlock {}

    record ToolUseBlock(
            String id,
            String name,
            JsonNode input
    ) implements ContentBlock {}

    record ToolResultBlock(
            String toolUseId,
            String content,
            boolean isError
    ) implements ContentBlock {}

    record ImageBlock(
            String mediaType,
            String base64Data
    ) implements ContentBlock {}

    record ThinkingBlock(
            String thinking
    ) implements ContentBlock {}

    record RedactedThinkingBlock(
            String data
    ) implements ContentBlock {}
}
