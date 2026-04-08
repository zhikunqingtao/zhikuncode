package com.aicodeassistant.llm;

import java.util.Map;

/**
 * 工具定义强类型 — 替代 {@code Map<String, Object>}。
 *
 * @param name        工具名称
 * @param description 工具描述
 * @param inputSchema JSON Schema 格式的输入定义
 */
public record ToolDefinition(
        String name,
        String description,
        Map<String, Object> inputSchema
) {
    /**
     * 转换为 OpenAI function calling 格式。
     */
    public Map<String, Object> toOpenAiFormat() {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", name,
                        "description", description,
                        "parameters", inputSchema
                )
        );
    }

    /**
     * 转换为 Anthropic tool 格式 (直接使用 name/description/input_schema)。
     */
    public Map<String, Object> toAnthropicFormat() {
        return Map.of(
                "name", name,
                "description", description,
                "input_schema", inputSchema
        );
    }
}
