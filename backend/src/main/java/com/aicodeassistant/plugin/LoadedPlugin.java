package com.aicodeassistant.plugin;

import com.aicodeassistant.command.Command;
import com.aicodeassistant.mcp.McpServerConfig;
import com.aicodeassistant.tool.Tool;

import java.util.List;
import java.util.Map;

/**
 * 已加载插件 — 运行时插件实例。
 * <p>
 * 对应源码: pluginLoader.ts LoadedPlugin
 *
 * @param name         插件名称
 * @param manifest     插件清单
 * @param path         插件目录路径
 * @param sourceType   来源类型
 * @param enabled      是否启用
 * @param isBuiltin    是否内置
 * @param extension    插件扩展实例（SPI 加载）
 * @param commands     注册的命令
 * @param tools        注册的工具
 * @param hooks        注册的钩子
 * @param mcpServers   MCP 服务器配置
 * @see <a href="SPEC §4.6.1">已加载插件</a>
 */
public record LoadedPlugin(
        String name,
        PluginManifest manifest,
        String path,
        PluginSourceType sourceType,
        boolean enabled,
        boolean isBuiltin,
        PluginExtension extension,
        List<Command> commands,
        List<Tool> tools,
        List<HookHandler> hooks,
        Map<String, McpServerConfig> mcpServers
) {

    /**
     * 创建内置插件。
     */
    public static LoadedPlugin builtin(String name, PluginExtension extension) {
        PluginManifest manifest = PluginManifest.builtin(name, extension.version(), extension.description());
        return new LoadedPlugin(
                name, manifest, null, PluginSourceType.BUILTIN,
                true, true, extension,
                extension.getCommands(),
                extension.getTools(),
                extension.getHooks(),
                Map.of()
        );
    }

    /**
     * 创建本地插件。
     */
    public static LoadedPlugin local(String name, String path, PluginExtension extension, boolean enabled) {
        PluginManifest manifest = PluginManifest.of(name, extension.version(), extension.description());
        return new LoadedPlugin(
                name, manifest, path, PluginSourceType.LOCAL,
                enabled, false, extension,
                extension.getCommands(),
                extension.getTools(),
                extension.getHooks(),
                Map.of()
        );
    }

    /**
     * 创建禁用副本。
     */
    public LoadedPlugin withDisabled() {
        return new LoadedPlugin(name, manifest, path, sourceType, false,
                isBuiltin, extension, commands, tools, hooks, mcpServers);
    }

    /**
     * 创建启用副本。
     */
    public LoadedPlugin withEnabled() {
        return new LoadedPlugin(name, manifest, path, sourceType, true,
                isBuiltin, extension, commands, tools, hooks, mcpServers);
    }
}
