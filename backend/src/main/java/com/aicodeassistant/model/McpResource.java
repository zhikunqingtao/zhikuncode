package com.aicodeassistant.model;

/**
 * MCP 资源定义。
 *
 * @see <a href="SPEC §5.6">MCP 模型</a>
 */
public record McpResource(
        String uri,
        String name,
        String description,
        String mimeType
) {}
