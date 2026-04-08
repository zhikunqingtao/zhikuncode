package com.aicodeassistant.plugin;

import com.aicodeassistant.command.Command;
import com.aicodeassistant.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

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
 * @see <a href="SPEC §4.6.5">插件状态管理与热重载</a>
 */
@Service
public class PluginManager {

    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);

    private final PluginLoader pluginLoader;

    /** 已加载的插件 (name → plugin) */
    private final Map<String, LoadedPlugin> loadedPlugins = new ConcurrentHashMap<>();

    /** 插件提供的命令 (commandName → command) */
    private final Map<String, Command> pluginCommands = new ConcurrentHashMap<>();

    /** 插件提供的工具 */
    private final Map<String, Tool> pluginTools = new ConcurrentHashMap<>();

    /** 已注册的钩子 (按事件类型分组) */
    private final Map<HookHandler.HookEventType, List<HookHandler>> registeredHooks = new ConcurrentHashMap<>();

    /** 是否已初始化 */
    private volatile boolean initialized = false;

    public PluginManager(PluginLoader pluginLoader) {
        this.pluginLoader = pluginLoader;
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
        log.info("Reloading plugins...");

        // 1. 卸载所有插件
        unloadAllPlugins();

        // 2. 重新加载
        initialized = false;
        initializePlugins();

        log.info("Plugins reloaded successfully");
    }

    /**
     * 注册单个插件的所有组件。
     */
    private void registerPlugin(LoadedPlugin plugin) {
        loadedPlugins.put(plugin.name(), plugin);

        // 注册命令（带插件前缀）
        for (Command cmd : plugin.commands()) {
            String prefixedName = plugin.name() + ":" + cmd.getName();
            pluginCommands.put(prefixedName, cmd);
            log.debug("Registered plugin command: /{}", prefixedName);
        }

        // 注册工具
        for (Tool tool : plugin.tools()) {
            pluginTools.put(tool.getName(), tool);
            log.debug("Registered plugin tool: {}", tool.getName());
        }

        // 注册钩子
        for (HookHandler hook : plugin.hooks()) {
            registeredHooks
                    .computeIfAbsent(hook.eventType(), k -> new ArrayList<>())
                    .add(hook);
            log.debug("Registered plugin hook: {} (matcher={})",
                    hook.eventType(), hook.matcher());
        }

        log.info("Registered plugin '{}': {} commands, {} tools, {} hooks",
                plugin.name(), plugin.commands().size(),
                plugin.tools().size(), plugin.hooks().size());
    }

    /**
     * 卸载所有插件。
     */
    private void unloadAllPlugins() {
        for (LoadedPlugin plugin : loadedPlugins.values()) {
            if (plugin.extension() != null) {
                try {
                    plugin.extension().onUnload();
                } catch (Exception e) {
                    log.warn("Error unloading plugin '{}': {}", plugin.name(), e.getMessage());
                }
            }
        }
        loadedPlugins.clear();
        pluginCommands.clear();
        pluginTools.clear();
        registeredHooks.clear();
    }

    // ==================== 查询 API ====================

    /**
     * 获取所有已加载插件。
     */
    public Collection<LoadedPlugin> getLoadedPlugins() {
        return Collections.unmodifiableCollection(loadedPlugins.values());
    }

    /**
     * 获取已启用的插件列表。
     */
    public List<LoadedPlugin> getEnabledPlugins() {
        return loadedPlugins.values().stream()
                .filter(LoadedPlugin::enabled)
                .toList();
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
        return Optional.ofNullable(loadedPlugins.get(name));
    }

    /**
     * 执行 PreToolUse 钩子 — 在工具调用前拦截。
     *
     * @param toolName  工具名称
     * @param toolInput 工具输入
     * @return 钩子结果（如果有匹配的钩子）
     */
    public Optional<HookHandler.HookResult> executePreToolUseHooks(String toolName, String toolInput) {
        return executeHooks(HookHandler.HookEventType.PRE_TOOL_USE, toolName, toolInput);
    }

    /**
     * 执行 PostToolUse 钩子 — 在工具调用后处理。
     */
    public Optional<HookHandler.HookResult> executePostToolUseHooks(String toolName, String toolOutput) {
        return executeHooks(HookHandler.HookEventType.POST_TOOL_USE, toolName, toolOutput);
    }

    /**
     * 执行指定类型的钩子。
     */
    private Optional<HookHandler.HookResult> executeHooks(
            HookHandler.HookEventType eventType, String toolName, String data) {
        List<HookHandler> hooks = registeredHooks.get(eventType);
        if (hooks == null || hooks.isEmpty()) {
            return Optional.empty();
        }

        // 按优先级排序
        List<HookHandler> sorted = hooks.stream()
                .sorted(Comparator.comparingInt(HookHandler::priority))
                .toList();

        for (HookHandler hook : sorted) {
            // 匹配器检查
            if (hook.matcher() != null && !hook.matcher().isEmpty()) {
                if (!Pattern.matches(hook.matcher(), toolName)) {
                    continue;
                }
            }

            try {
                HookHandler.HookContext ctx = HookHandler.HookContext.of(toolName, data);
                HookHandler.HookResult result = hook.handler().apply(ctx);

                if (!result.proceed()) {
                    // 钩子拒绝了操作
                    return Optional.of(result);
                }
            } catch (Exception e) {
                log.warn("Hook execution failed for {}: {}", toolName, e.getMessage());
            }
        }

        return Optional.empty();
    }

    /**
     * 获取已加载插件数量。
     */
    public int getPluginCount() {
        return loadedPlugins.size();
    }

    /**
     * 是否已初始化。
     */
    public boolean isInitialized() {
        return initialized;
    }
}
