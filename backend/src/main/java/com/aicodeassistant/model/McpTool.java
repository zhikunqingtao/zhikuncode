package com.aicodeassistant.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * MCP 工具定义。
 *
 * @see <a href="SPEC §5.6">MCP 模型</a>
 */
public record McpTool(
        String name,
        String description,
        JsonNode inputSchema
) {}
