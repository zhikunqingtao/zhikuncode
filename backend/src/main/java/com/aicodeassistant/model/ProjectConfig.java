package com.aicodeassistant.model;

import com.aicodeassistant.mcp.McpServerConfig;

import java.util.List;
import java.util.Map;

/**
 * 项目级配置 (.ai-code-assistant/config.json)。
 *
 * @see <a href="SPEC §5.3">配置模型</a>
 */
public record ProjectConfig(
        String lastSessionId,
        String lastModel,
        double lastCost,
        List<PermissionRule> projectAlwaysAllowRules,
        Map<String, McpServerConfig> projectMcpServers,
        Map<String, Object> customSettings
) {}
