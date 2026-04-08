package com.aicodeassistant.llm;

import java.util.*;

/**
 * MessageParam ↔ Map 双向转换器。
 * <p>
 * 提供强类型 MessageParam 与 Map&lt;String, Object&gt; 之间的桥接，
 * 用于过渡期间旧代码和新代码的互操作。
 */
public final class MessageParamConverter {

    private MessageParamConverter() {} // 禁止实例化

    /**
     * 将强类型消息列表转为 Map 列表 (供 Provider 使用)。
     */
    public static List<Map<String, Object>> toMaps(List<MessageParam> messages) {
        return messages.stream()
                .map(MessageParamConverter::toMap)
                .toList();
    }

    /**
     * 将单个 MessageParam 转为 Map。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(MessageParam msg) {
        return switch (msg) {
            case MessageParam.UserMessage u ->
                    new HashMap<>(Map.of("role", "user", "content", u.content()));
            case MessageParam.UserMessageWithBlocks u ->
                    new HashMap<>(Map.of("role", "user", "content", u.contentBlocks()));
            case MessageParam.AssistantMessage a -> {
                Map<String, Object> map = new HashMap<>();
                map.put("role", "assistant");
                List<Map<String, Object>> content = new ArrayList<>();
                if (a.thinkingContent() != null && !a.thinkingContent().isBlank()) {
                    content.add(Map.of("type", "thinking", "thinking", a.thinkingContent()));
                }
                if (a.content() != null && !a.content().isBlank()) {
                    content.add(Map.of("type", "text", "text", a.content()));
                }
                if (a.toolUses() != null) {
                    for (MessageParam.ToolUse tu : a.toolUses()) {
                        Map<String, Object> tuMap = new HashMap<>();
                        tuMap.put("type", "tool_use");
                        tuMap.put("id", tu.id());
                        tuMap.put("name", tu.name());
                        tuMap.put("input", tu.input() != null ? tu.input() : Map.of());
                        content.add(tuMap);
                    }
                }
                map.put("content", content);
                yield map;
            }
            case MessageParam.ToolResultMessage t -> {
                Map<String, Object> resultBlock = new HashMap<>();
                resultBlock.put("type", "tool_result");
                resultBlock.put("tool_use_id", t.toolUseId());
                resultBlock.put("content", t.content() != null ? t.content() : "");
                if (t.isError()) resultBlock.put("is_error", true);
                yield new HashMap<>(Map.of("role", "user", "content", List.of(resultBlock)));
            }
        };
    }

    /**
     * 将 Map 列表转为强类型消息列表 (从旧代码迁移用)。
     */
    @SuppressWarnings("unchecked")
    public static List<MessageParam> fromMaps(List<Map<String, Object>> maps) {
        return maps.stream()
                .map(MessageParamConverter::fromMap)
                .toList();
    }

    /**
     * 将单个 Map 转为 MessageParam。
     */
    @SuppressWarnings("unchecked")
    public static MessageParam fromMap(Map<String, Object> map) {
        String role = (String) map.get("role");
        Object content = map.get("content");

        if ("assistant".equals(role)) {
            if (content instanceof String text) {
                return new MessageParam.AssistantMessage(text);
            }
            if (content instanceof List<?> blocks) {
                String textContent = null;
                String thinkingContent = null;
                List<MessageParam.ToolUse> toolUses = new ArrayList<>();
                for (Object block : blocks) {
                    if (block instanceof Map<?, ?> b) {
                        String type = (String) b.get("type");
                        if ("text".equals(type)) {
                            textContent = (String) b.get("text");
                        } else if ("thinking".equals(type)) {
                            thinkingContent = (String) b.get("thinking");
                        } else if ("tool_use".equals(type)) {
                            toolUses.add(new MessageParam.ToolUse(
                                    (String) b.get("id"),
                                    (String) b.get("name"),
                                    (Map<String, Object>) b.get("input")
                            ));
                        }
                    }
                }
                return new MessageParam.AssistantMessage(textContent, toolUses, thinkingContent);
            }
            return new MessageParam.AssistantMessage(content != null ? content.toString() : "");
        }

        if ("user".equals(role)) {
            if (content instanceof String text) {
                return new MessageParam.UserMessage(text);
            }
            if (content instanceof List<?> blocks) {
                // 检查是否是 tool_result
                for (Object block : blocks) {
                    if (block instanceof Map<?, ?> b && "tool_result".equals(b.get("type"))) {
                        return new MessageParam.ToolResultMessage(
                                (String) b.get("tool_use_id"),
                                b.get("content") != null ? b.get("content").toString() : "",
                                Boolean.TRUE.equals(b.get("is_error"))
                        );
                    }
                }
                return new MessageParam.UserMessageWithBlocks((List<Map<String, Object>>) content);
            }
            return new MessageParam.UserMessage(content != null ? content.toString() : "");
        }

        // fallback
        return new MessageParam.UserMessage(content != null ? content.toString() : "");
    }
}
