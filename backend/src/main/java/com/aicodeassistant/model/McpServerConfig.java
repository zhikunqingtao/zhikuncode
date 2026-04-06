package com.aicodeassistant.model;

import java.util.List;
import java.util.Map;

/**
 * MCP 服务器配置。
 *
 * @see <a href="SPEC §5.6">MCP 模型</a>
 */
public record McpServerConfig(
        String command,
        List<String> args,
        Map<String, String> env,
        String url,
        boolean disabled
) {}
