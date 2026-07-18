package com.aicodeassistant.plugin;

import com.aicodeassistant.command.CommandRegistry;
import com.aicodeassistant.hook.HookEvent;
import com.aicodeassistant.hook.HookRegistry;
import com.aicodeassistant.mcp.McpClientManager;
import com.aicodeassistant.tool.ToolRegistry;
import org.junit.jupiter.api.*;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 插件系统集成测试 — 验证端到端链路。
 */
class PluginIntegrationTest {

    private PluginManager pluginManager;
    private CommandRegistry commandRegistry;
    private ToolRegistry toolRegistry;
    private HookRegistry hookRegistry;
    private McpClientManager mcpClientManager;

    private static Environment mockEnvironment() {
        Environment env = mock(Environment.class);
        when(env.getProperty("plugin.max-jar-size", Long.class, 50 * 1024 * 1024L))
                .thenReturn(50 * 1024 * 1024L);
        return env;
    }

    @BeforeEach
    void setUp() {
        commandRegistry = mock(CommandRegistry.class);
        toolRegistry = mock(ToolRegistry.class);
        hookRegistry = mock(HookRegistry.class);
        mcpClientManager = mock(McpClientManager.class);

        PluginLoader loader = new PluginLoader(mockEnvironment());
        pluginManager = new PluginManager(
                loader, commandRegistry, toolRegistry, hookRegistry, mcpClientManager);
    }

    @Test
    @DisplayName("插件系统初始化 — 加载内置 HelloPlugin")
    void shouldInitializeAndLoadBuiltinPlugin() {
        pluginManager.initializePlugins();

        assertTrue(pluginManager.isInitialized());
        assertTrue(pluginManager.getPluginCount() > 0, "Should have at least 1 builtin plugin");
    }

    @Test
    @DisplayName("初始化后 HelloPlugin 的命令注册到 CommandRegistry")
    void shouldRegisterCommandsToCommandRegistry() {
        pluginManager.initializePlugins();

        // HelloPlugin 提供 /hello:greet 命令，应调用 commandRegistry.register()
        verify(commandRegistry, atLeastOnce()).register(any());
    }

    @Test
    @DisplayName("初始化后 HelloPlugin 的工具注册到 ToolRegistry")
    void shouldRegisterToolsToToolRegistry() {
        pluginManager.initializePlugins();

        // HelloPlugin 提供 hello_echo 工具
        verify(toolRegistry, atLeastOnce()).registerDynamic(any());
    }

    @Test
    @DisplayName("初始化后 HelloPlugin 的钩子桥接到 HookRegistry")
    void shouldBridgeHooksToHookRegistry() {
        pluginManager.initializePlugins();

        // HelloPlugin 提供 PostToolUse 钩子 → 桥接到 HookRegistry.register(event, matcher, priority, handler, source)
        verify(hookRegistry, atLeastOnce()).register(
                any(HookEvent.class), any(), anyInt(), any(), any(),
                eq(HookRegistry.HookRole.PRESENTATION));
    }

    @Test
    @DisplayName("热重载 — 先卸载再加载")
    void shouldReloadPlugins() {
        pluginManager.initializePlugins();
        int countBefore = pluginManager.getPluginCount();

        pluginManager.reloadPlugins();

        assertEquals(countBefore, pluginManager.getPluginCount(),
                "Plugin count should be same after reload");
    }

    @Test
    @DisplayName("热重载 — 注销旧钩子")
    void shouldUnregisterHooksOnReload() {
        pluginManager.initializePlugins();

        pluginManager.reloadPlugins();

        // unregisterBySource 应被调用（卸载旧插件的钩子）
        verify(hookRegistry, atLeastOnce()).unregisterBySource(argThat(s -> s.startsWith("plugin:")));
    }

    @Test
    @DisplayName("重复初始化 — 幂等性")
    void shouldBeIdempotent() {
        pluginManager.initializePlugins();
        int count1 = pluginManager.getPluginCount();

        pluginManager.initializePlugins(); // 第二次调用应忽略
        int count2 = pluginManager.getPluginCount();

        assertEquals(count1, count2, "Double init should not duplicate plugins");
    }

    @Test
    @DisplayName("Feature Flag — plugin.enabled=false 时不初始化")
    void pluginSystemInitializerRespectsFeatureFlag() {
        // 这个测试验证 PluginSystemInitializer 的行为
        Environment env = mock(Environment.class);
        when(env.getProperty("plugin.enabled", Boolean.class, true)).thenReturn(false);

        PluginSystemInitializer initializer = new PluginSystemInitializer(pluginManager, env);
        initializer.onApplicationReady();

        assertFalse(pluginManager.isInitialized(),
                "Plugin system should not initialize when disabled");
    }
}
