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

    record UserMessage(String content) implements MessageParam {
        @Override
        public String role() { return "user"; }
    }

    record UserMessageWithBlocks(List<Map<String, Object>> contentBlocks) implements MessageParam {
        @Override
        public String role() { return "user"; }
    }

    record AssistantMessage(
            String content,
            List<ToolUse> toolUses,
            String thinkingContent  // extended thinking 输出 (可为 null)
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
