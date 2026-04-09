package com.aicodeassistant.llm;

import java.util.List;
import java.util.Map;

/**
 * LLM API 消息参数强类型 — 替代 {@code List<Map<String, Object>>}。
 * <p>
 * sealed interface 利用 Java 21 pattern matching 提供编译期类型安全。
 *
 * @see <a href="SPEC §3.1.3">流式处理</a>
 */
public sealed interface MessageParam {

    String role();

    // ===== ContentPart 子类型 (对齐附录 J adaptMessages()) =====
    sealed interface ContentPart {
        record TextPart(String text) implements ContentPart {}
        record ToolUsePart(String id, String name, Map<String, Object> input) implements ContentPart {}
        record ToolResultPart(String toolUseId, String content, boolean isError) implements ContentPart {}
        record ThinkingPart(String thinking) implements ContentPart {}
        record RedactedThinkingPart(String data) implements ContentPart {}
        record ImagePart(String mediaType, String base64Data) implements ContentPart {}
    }

    // ===== 新版 record (目标结构) =====
    record UserParam(List<ContentPart> content) implements MessageParam {
        @Override public String role() { return "user"; }
    }
    record AssistantParam(List<ContentPart> content) implements MessageParam {
        @Override public String role() { return "assistant"; }
    }

    // ===== 旧版 record (向后兼容，渐进废弃) =====
    @Deprecated
    record UserMessage(String content) implements MessageParam {
        @Override
        public String role() { return "user"; }
    }

    @Deprecated
    record UserMessageWithBlocks(List<Map<String, Object>> contentBlocks) implements MessageParam {
        @Override
        public String role() { return "user"; }
    }

    @Deprecated
    record AssistantMessage(
            String content,
            List<ToolUse> toolUses,
            String thinkingContent
    ) implements MessageParam {
        @Override
        public String role() { return "assistant"; }

        public AssistantMessage(String content, List<ToolUse> toolUses) {
            this(content, toolUses, null);
        }

        public AssistantMessage(String content) {
            this(content, List.of(), null);
        }
    }

    @Deprecated
    record ToolResultMessage(
            String toolUseId,
            String content,
            boolean isError
    ) implements MessageParam {
        @Override
        public String role() { return "user"; }
    }

    /**
     * 工具调用请求 — assistant 消息中的 tool_use 块。
     */
    record ToolUse(String id, String name, Map<String, Object> input) {}
}
