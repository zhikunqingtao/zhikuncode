package com.aicodeassistant.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;

/**
 * MCP 服务器连接 — 表示一个已连接/配置的 MCP 服务器实例。
 *
 * @see <a href="SPEC §5.6">MCP 模型</a>
 */
public record McpServerConnection(
        String name,
        McpServerConfig config,
        McpConnectionStatus status,
        List<McpTool> tools,
        List<McpResource> resources,
        JsonNode capabilities,
        Instant connectedAt
) {}
