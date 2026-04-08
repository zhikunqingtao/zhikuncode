package com.aicodeassistant.plugin;

import com.aicodeassistant.mcp.McpServerConfig;

import java.util.List;
import java.util.Map;

/**
 * 插件清单 — 定义插件元数据与组件配置。
 * <p>
 * 对应源码: src/utils/plugins/pluginLoader.ts PluginManifest
 *
 * @param name        插件名称
 * @param description 描述
 * @param version     版本号
 * @param repository  Git 仓库 URL
 * @param source      来源标识 (local/builtin/marketplace)
 * @param isBuiltin   是否内置插件
 * @param hooks       钩子配置
 * @param mcpServers  MCP 服务器配置
 * @see <a href="SPEC §4.6.1">插件清单定义</a>
 */
public record PluginManifest(
        String name,
        String description,
        String version,
        String repository,
        String source,
        boolean isBuiltin,
        Map<String, Object> hooks,
        Map<String, McpServerConfig> mcpServers
) {

    /**
     * 创建最小清单。
     */
    public static PluginManifest of(String name, String version, String description) {
        return new PluginManifest(name, description, version, null,
                "local", false, Map.of(), Map.of());
    }

    /**
     * 创建内置插件清单。
     */
    public static PluginManifest builtin(String name, String version, String description) {
        return new PluginManifest(name, description, version, null,
                "builtin", true, Map.of(), Map.of());
    }
}
