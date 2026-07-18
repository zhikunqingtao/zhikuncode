package com.aicodeassistant.plugin;

import com.aicodeassistant.command.Command;
import com.aicodeassistant.command.CommandRegistry;
import com.aicodeassistant.hook.HookEvent;
import com.aicodeassistant.hook.HookRegistry;
import com.aicodeassistant.mcp.McpClientManager;
import com.aicodeassistant.tool.ToolRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * PluginManager 钩子执行机制测试 (P1)
 */
class PluginManagerHookTest {

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

    // ==================== mapPluginHookEvent 全覆盖 ====================

    @Nested
    @DisplayName("mapPluginHookEvent 事件映射")
    class EventMappingTests {

        /**
         * 通过注册包含指定事件类型钩子的插件，验证钩子是否被桥接到 HookRegistry 对应的事件类型。
         * 这间接测试了 private mapPluginHookEvent 方法。
         */
        static Stream<Arguments> hookEventMappings() {
            return Stream.of(
                    Arguments.of(HookHandler.HookEventType.PRE_TOOL_USE, HookEvent.PRE_TOOL_USE),
                    Arguments.of(HookHandler.HookEventType.POST_TOOL_USE, HookEvent.POST_TOOL_USE),
                    Arguments.of(HookHandler.HookEventType.NOTIFICATION, HookEvent.NOTIFICATION),
                    Arguments.of(HookHandler.HookEventType.STOP, HookEvent.STOP),
                    Arguments.of(HookHandler.HookEventType.USER_PROMPT_SUBMIT, HookEvent.USER_PROMPT_SUBMIT),
                    Arguments.of(HookHandler.HookEventType.SESSION_START, HookEvent.SESSION_START),
                    Arguments.of(HookHandler.HookEventType.SESSION_END, HookEvent.SESSION_END),
                    Arguments.of(HookHandler.HookEventType.TASK_COMPLETED, HookEvent.TASK_COMPLETED)
            );
        }

        @ParameterizedTest(name = "{0} → {1}")
        @MethodSource("hookEventMappings")
        @DisplayName("插件钩子事件类型应正确映射到系统钩子事件")
        void shouldMapPluginHookEventToSystemEvent(
                HookHandler.HookEventType pluginType, HookEvent expectedSystemEvent) {

            // 构造包含指定事件类型钩子的插件
            HookHandler hook = new HookHandler(pluginType, null, 10,
                    ctx -> HookHandler.HookResult.allow());
            PluginExtension ext = createPluginWithHooks("test-plugin", List.of(hook));

            // 通过 PluginLoader mock 让 PluginManager 加载该插件
            PluginLoader mockLoader = mock(PluginLoader.class);
            LoadedPlugin plugin = LoadedPlugin.builtin("test-plugin", ext);
            when(mockLoader.loadAllPlugins()).thenReturn(
                    new PluginLoadResult(List.of(plugin), List.of(), List.of()));

            PluginManager manager = new PluginManager(
                    mockLoader, commandRegistry, toolRegistry, hookRegistry, mcpClientManager);
            manager.initializePlugins();

            // 验证 hookRegistry.register 被调用时传入了正确的系统事件类型
            HookRegistry.HookRole expectedRole = expectedSystemEvent == HookEvent.PRE_TOOL_USE
                    ? HookRegistry.HookRole.SECURITY_CONSTRAINT
                    : HookRegistry.HookRole.PRESENTATION;
            verify(hookRegistry).register(
                    eq(expectedSystemEvent), any(), eq(10), any(), eq("plugin:test-plugin"),
                    eq(expectedRole));
        }
    }

    // ==================== 辅助方法 ====================

    private static PluginExtension createPluginWithHooks(String name, List<HookHandler> hooks) {
        return new PluginExtension() {
            @Override public String name() { return name; }
            @Override public String version() { return "1.0.0"; }
            @Override public List<HookHandler> getHooks() { return hooks; }
            @Override public List<Command> getCommands() { return List.of(); }
            @Override public List<com.aicodeassistant.tool.Tool> getTools() { return List.of(); }
        };
    }
}
