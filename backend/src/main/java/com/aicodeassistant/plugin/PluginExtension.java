package com.aicodeassistant.plugin;

import com.aicodeassistant.command.Command;
import com.aicodeassistant.mcp.McpServerConfig;
import com.aicodeassistant.tool.Tool;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 插件扩展接口 — 第三方插件必须实现此接口。
 * <p>
 * 通过 Java SPI 机制注册: META-INF/services/com.aicodeassistant.plugin.PluginExtension
 * <p>
 * 最小实现: 仅需实现 name() 和 version()，其余方法均有默认空实现。
 * <p>
 * 生命周期: onLoad(ctx) → [getCommands/getTools/getHooks 被调用] → onUnload()
 *
 * @see <a href="SPEC §4.6.2">插件扩展接口</a>
 */
public interface PluginExtension {

    /** 插件唯一标识名 (用于日志、配置引用、命令前缀) */
    String name();

    /** 插件版本 (SemVer 格式, 如 "1.0.0") */
    String version();

    /** 加载优先级 — 数值越小越先加载 (默认 100) */
    default int priority() { return 100; }

    /** 插件描述 — 显示在 /plugins 命令输出中 */
    default String description() { return ""; }

    /** 最低兼容宿主 API 版本 */
    default String minApiVersion() { return "1.0.0"; }

    /** 最高兼容宿主 API 版本 */
    default String maxApiVersion() { return "99.0.0"; }

    /**
     * 注册命令 — 插件提供的 Slash 命令列表。
     * 命令名自动添加插件前缀: /pluginName:commandName
     */
    default List<Command> getCommands() { return List.of(); }

    /**
     * 注册工具 — 插件提供的工具列表。
     * 工具会注册到 ToolRegistry，LLM 可在对话中调用。
     */
    default List<Tool> getTools() { return List.of(); }

    /**
     * 注册钩子 — 插件的事件钩子处理器列表。
     */
    default List<HookHandler> getHooks() { return List.of(); }

    /**
     * 注册 MCP 服务器 — 插件提供的 MCP 服务器配置。
     */
    default List<McpServerConfig> getMcpServers() { return List.of(); }

    /**
     * 插件加载回调 — 在所有方法可被调用前执行。
     *
     * @param ctx 插件上下文，提供日志、配置读取、宿主 API 访问
     */
    default void onLoad(PluginContext ctx) {}

    /**
     * 插件卸载回调 — 清理资源。
     */
    default void onUnload() {}

    /**
     * 插件是否默认启用 — 返回 false 时需用户显式启用。
     */
    default boolean isEnabledByDefault() { return true; }
}
