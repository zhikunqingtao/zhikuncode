package com.aicodeassistant.plugin;

import com.aicodeassistant.command.Command;
import com.aicodeassistant.command.CommandRegistry;
import com.aicodeassistant.hook.HookEvent;
import com.aicodeassistant.hook.HookRegistry;
import com.aicodeassistant.mcp.McpClientManager;
import com.aicodeassistant.mcp.McpServerConfig;
import com.aicodeassistant.mcp.McpServerConnection;
import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.ToolRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 插件管理器 — 管理插件的完整生命周期。
 * <p>
 * 生命周期阶段:
 * <ol>
 *     <li>安装 — 加载 JAR/内置插件</li>
 *     <li>加载 — 初始化组件（命令/工具/钩子/MCP/LSP）</li>
 *     <li>运行时 — 命令执行、MCP 调用、钩子触发</li>
 *     <li>卸载 — 资源清理</li>
 *     <li>热重载 — 刷新插件组件</li>
 * </ol>
 *
 */
@Service
public class PluginManager {

    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);

    private final PluginLoader pluginLoader;
    private final CommandRegistry commandRegistry;
    private final ToolRegistry toolRegistry;
    private final HookRegistry hookRegistry;
    private final McpClientManager mcpClientManager;

    /** 钩子超时执行器 — 共享虚拟线程池 */
    private final java.util.concurrent.ExecutorService hookExecutor =
            java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    /** 热重载读写锁 */
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    /** 已加载的插件 (name → plugin) */
    private final Map<String, LoadedPlugin> loadedPlugins = new ConcurrentHashMap<>();

    /** 插件提供的命令 (commandName → command) */
    private final Map<String, Command> pluginCommands = new ConcurrentHashMap<>();

    /** 插件提供的工具 */
    private final Map<String, Tool> pluginTools = new ConcurrentHashMap<>();

    /** 是否已初始化 */
    private volatile boolean initialized = false;

    public PluginManager(PluginLoader pluginLoader,
                         CommandRegistry commandRegistry,
                         ToolRegistry toolRegistry,
                         HookRegistry hookRegistry,
                         @Lazy McpClientManager mcpClientManager) {
        this.pluginLoader = pluginLoader;
        this.commandRegistry = commandRegistry;
        this.toolRegistry = toolRegistry;
        this.hookRegistry = hookRegistry;
        this.mcpClientManager = mcpClientManager;
    }

    /**
     * 初始化插件系统 — 加载所有插件并注册组件。
     */
    public void initializePlugins() {
        if (initialized) {
            log.warn("Plugin system already initialized, use reloadPlugins() instead");
            return;
        }

        log.info("Initializing plugin system...");

        // 1. 加载所有插件
        PluginLoadResult result = pluginLoader.loadAllPlugins();

        // 2. 注册已启用插件的组件
        for (LoadedPlugin plugin : result.enabled()) {
            registerPlugin(plugin);
        }

        // 3. 记录已禁用插件
        for (LoadedPlugin plugin : result.disabled()) {
            loadedPlugins.put(plugin.name(), plugin);
        }

        // 4. 记录错误
        for (PluginError error : result.errors()) {
            log.error("Plugin load error [{}]: {} - {}",
                    error.errorType(), error.pluginName(), error.message());
        }

        initialized = true;
        log.info("Plugin system initialized: {} enabled, {} disabled, {} errors",
                result.enabled().size(), result.disabled().size(), result.errors().size());
    }

    /**
     * 热重载 — 刷新所有插件组件。
     */
    public void reloadPlugins() {
        rwLock.writeLock().lock();
        try {
            log.info("Reloading plugins...");
            unloadAllPlugins();
            initialized = false;
            initializePlugins();
            log.info("Plugins reloaded successfully");
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /**
     * 注册单个插件的所有组件。
     */
    private void registerPlugin(LoadedPlugin plugin) {
        loadedPlugins.put(plugin.name(), plugin);

        // 1. 注册命令 → CommandRegistry（带插件前缀）
        for (Command cmd : plugin.commands()) {
            String prefixedName = plugin.name() + ":" + cmd.getName();
            pluginCommands.put(prefixedName, cmd);
            Command wrappedCmd = new PluginCommandWrapper(prefixedName, cmd);
            commandRegistry.register(wrappedCmd);
            log.debug("Registered plugin command: /{} → CommandRegistry", prefixedName);
        }

        // 2. 注册工具 → ToolRegistry
        for (Tool tool : plugin.tools()) {
            pluginTools.put(tool.getName(), tool);
            toolRegistry.registerDynamic(tool);
            log.debug("Registered plugin tool: {} → ToolRegistry", tool.getName());
        }

        // 3. 钩子桥接 → HookRegistry
        for (HookHandler hook : plugin.hooks()) {
            HookEvent systemEvent = mapPluginHookEvent(hook.eventType());
            if (systemEvent != null) {
                hookRegistry.register(
                    systemEvent,
                    hook.matcher(),
                    hook.priority(),
                    ctx -> {
                        try {
                            HookHandler.HookContext pluginCtx =
                                new HookHandler.HookContext(
                                    ctx.toolName(), ctx.input(), ctx.output(), ctx.sessionId());
                            HookHandler.HookResult pluginResult =
                                CompletableFuture
                                    .supplyAsync(() -> hook.handler().apply(pluginCtx),
                                        hookExecutor)
                                    .orTimeout(5, TimeUnit.SECONDS)
                                    .join();
                            return new HookRegistry.HookResult(
                                pluginResult.proceed(),
                                pluginResult.modifiedInput(),
                                pluginResult.modifiedOutput(),
                                pluginResult.message());
                        } catch (java.util.concurrent.CompletionException e) {
                            if (systemEvent == HookEvent.PRE_TOOL_USE) {
                                throw new IllegalStateException("Security plugin hook failed", e);
                            }
                            log.warn("Presentation plugin hook failed: plugin={}, event={}",
                                    plugin.name(), systemEvent, e);
                            return HookRegistry.HookResult.allow();
                        } catch (Exception e) {
                            if (systemEvent == HookEvent.PRE_TOOL_USE) {
                                throw new IllegalStateException("Security plugin hook failed", e);
                            }
                            log.warn("Presentation plugin hook failed: plugin={}, event={}",
                                    plugin.name(), systemEvent, e);
                            return HookRegistry.HookResult.allow();
                        }
                    },
                    "plugin:" + plugin.name(),
                    systemEvent == HookEvent.PRE_TOOL_USE
                            ? HookRegistry.HookRole.SECURITY_CONSTRAINT
                            : HookRegistry.HookRole.PRESENTATION
                );
            }
            log.debug("Bridged plugin hook: {} → HookRegistry (source=plugin:{})",
                    hook.eventType(), plugin.name());
        }

        // 4. MCP 服务器注册 → McpClientManager
        if (!plugin.mcpServers().isEmpty() && mcpClientManager != null) {
            for (Map.Entry<String, McpServerConfig> entry : plugin.mcpServers().entrySet()) {
                try {
                    McpServerConnection conn = mcpClientManager.addServer(entry.getValue());
                    log.info("Registered plugin MCP server: plugin:{}:{} (status={})",
                            plugin.name(), entry.getKey(), conn.getStatus());
                } catch (Exception e) {
                    log.warn("Failed to register plugin MCP server: {}", e.getMessage());
                }
            }
        }

        log.info("Registered plugin '{}': {} commands, {} tools, {} hooks, {} MCP servers",
                plugin.name(), plugin.commands().size(), plugin.tools().size(),
                plugin.hooks().size(), plugin.mcpServers().size());
    }

    /**
     * 卸载所有插件。
     */
    private void unloadAllPlugins() {
        for (LoadedPlugin plugin : loadedPlugins.values()) {
            // 从 CommandRegistry 注销
            for (Command cmd : plugin.commands()) {
                String prefixedName = plugin.name() + ":" + cmd.getName();
                commandRegistry.unregister(prefixedName);
            }
            // 从 ToolRegistry 注销
            for (Tool tool : plugin.tools()) {
                toolRegistry.unregister(tool.getName());
            }
            // 从 HookRegistry 注销
            hookRegistry.unregisterBySource("plugin:" + plugin.name());
            // 从 McpClientManager 注销
            if (!plugin.mcpServers().isEmpty() && mcpClientManager != null) {
                for (String key : plugin.mcpServers().keySet()) {
                    mcpClientManager.removeServer(key);
                }
            }
            // 插件卸载回调
            if (plugin.extension() != null) {
                try {
                    plugin.extension().onUnload();
                } catch (Exception e) {
                    log.warn("Plugin unload callback failed: plugin={}", plugin.name(), e);
                }
            }
            // 关闭 ClassLoader
            if (plugin.sourceType() == PluginSourceType.LOCAL) {
                ClassLoader cl = plugin.extension().getClass().getClassLoader();
                if (cl instanceof java.net.URLClassLoader ucl) {
                    try { ucl.close(); } catch (Exception e) {
                        log.warn("Plugin ClassLoader close failed: plugin={}", plugin.name(), e);
                    }
                }
            }
        }
        loadedPlugins.clear();
        pluginCommands.clear();
        pluginTools.clear();
    }

    @PreDestroy
    void shutdown() {
        hookExecutor.close();
        log.info("Plugin system hook executor closed");
    }

    // ==================== 查询 API ====================

    /**
     * 获取所有已加载插件。
     */
    public List<LoadedPlugin> getLoadedPlugins() {
        rwLock.readLock().lock();
        try {
            return List.copyOf(loadedPlugins.values());
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * 获取已启用的插件列表。
     */
    public List<LoadedPlugin> getEnabledPlugins() {
        rwLock.readLock().lock();
        try {
            return loadedPlugins.values().stream()
                    .filter(LoadedPlugin::enabled)
                    .toList();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * 获取插件提供的所有命令。
     */
    public Map<String, Command> getPluginCommands() {
        return Collections.unmodifiableMap(pluginCommands);
    }

    /**
     * 获取插件提供的所有工具。
     */
    public Collection<Tool> getPluginTools() {
        return Collections.unmodifiableCollection(pluginTools.values());
    }

    /**
     * 按名称获取插件。
     */
    public Optional<LoadedPlugin> getPlugin(String name) {
        rwLock.readLock().lock();
        try {
            return Optional.ofNullable(loadedPlugins.get(name));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * 获取已加载插件数量。
     */
    public int getPluginCount() {
        rwLock.readLock().lock();
        try {
            return loadedPlugins.size();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * 是否已初始化。
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * 将插件钩子事件类型映射到系统钩子事件类型。
     */
    private HookEvent mapPluginHookEvent(HookHandler.HookEventType pluginType) {
        return switch (pluginType) {
            case PRE_TOOL_USE       -> HookEvent.PRE_TOOL_USE;
            case POST_TOOL_USE      -> HookEvent.POST_TOOL_USE;
            case NOTIFICATION       -> HookEvent.NOTIFICATION;
            case STOP               -> HookEvent.STOP;
            case USER_PROMPT_SUBMIT -> HookEvent.USER_PROMPT_SUBMIT;
            case SESSION_START      -> HookEvent.SESSION_START;
            case SESSION_END        -> HookEvent.SESSION_END;
            case TASK_COMPLETED     -> HookEvent.TASK_COMPLETED;
        };
    }
}
