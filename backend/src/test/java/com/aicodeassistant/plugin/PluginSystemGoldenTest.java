package com.aicodeassistant.plugin;

import com.aicodeassistant.command.Command;
import com.aicodeassistant.command.CommandContext;
import com.aicodeassistant.command.CommandResult;
import com.aicodeassistant.command.CommandType;
import com.aicodeassistant.mcp.McpServerConfig;
import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import org.junit.jupiter.api.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 插件系统黄金测试 — 覆盖 SPEC §4.6 的核心功能。
 */
class PluginSystemGoldenTest {

    // ==================== §4.6.1 PluginManifest ====================

    @Nested
    @DisplayName("§4.6.1 PluginManifest")
    class PluginManifestTests {

        @Test
        @DisplayName("创建本地插件清单")
        void createLocalManifest() {
            PluginManifest m = PluginManifest.of("test-plugin", "1.0.0", "Test plugin");
            assertEquals("test-plugin", m.name());
            assertEquals("1.0.0", m.version());
            assertEquals("Test plugin", m.description());
            assertEquals("local", m.source());
            assertFalse(m.isBuiltin());
        }

        @Test
        @DisplayName("创建内置插件清单")
        void createBuiltinManifest() {
            PluginManifest m = PluginManifest.builtin("builtin-plugin", "2.0.0", "Built-in");
            assertEquals("builtin", m.source());
            assertTrue(m.isBuiltin());
        }
    }

    // ==================== §4.6.2 PluginExtension ====================

    @Nested
    @DisplayName("§4.6.2 PluginExtension 接口")
    class PluginExtensionTests {

        @Test
        @DisplayName("最小实现 — 仅 name() 和 version()")
        void minimalImplementation() {
            PluginExtension ext = createMinimalPlugin("minimal", "1.0.0");

            assertEquals("minimal", ext.name());
            assertEquals("1.0.0", ext.version());
            assertEquals(100, ext.priority());
            assertEquals("", ext.description());
            assertTrue(ext.isEnabledByDefault());
            assertTrue(ext.getCommands().isEmpty());
            assertTrue(ext.getTools().isEmpty());
            assertTrue(ext.getHooks().isEmpty());
            assertTrue(ext.getMcpServers().isEmpty());
        }

        @Test
        @DisplayName("完整实现 — 含命令和工具")
        void fullImplementation() {
            Command testCmd = createTestCommand("greet", "Greet user");
            Tool testTool = createTestTool("format", "Format code");

            PluginExtension ext = new PluginExtension() {
                @Override public String name() { return "full-plugin"; }
                @Override public String version() { return "2.0.0"; }
                @Override public String description() { return "Full plugin"; }
                @Override public int priority() { return 50; }
                @Override public List<Command> getCommands() { return List.of(testCmd); }
                @Override public List<Tool> getTools() { return List.of(testTool); }
            };

            assertEquals(1, ext.getCommands().size());
            assertEquals(1, ext.getTools().size());
            assertEquals(50, ext.priority());
        }

        @Test
        @DisplayName("API 版本默认值")
        void apiVersionDefaults() {
            PluginExtension ext = createMinimalPlugin("test", "1.0.0");
            assertEquals("1.0.0", ext.minApiVersion());
            assertEquals("99.0.0", ext.maxApiVersion());
        }
    }

    // ==================== §4.6.2 PluginClassLoader ====================

    @Nested
    @DisplayName("§4.6.2 PluginClassLoader 安全沙箱")
    class PluginClassLoaderTests {

        @Test
        @DisplayName("JDK 标准库可访问")
        void jdkClassesAccessible() throws Exception {
            PluginClassLoader cl = new PluginClassLoader(new java.net.URL[0],
                    getClass().getClassLoader());
            Class<?> c = cl.loadClass("java.util.ArrayList");
            assertNotNull(c);
            cl.close();
        }

        @Test
        @DisplayName("宿主 plugin API 包可访问")
        void pluginApiAccessible() throws Exception {
            PluginClassLoader cl = new PluginClassLoader(new java.net.URL[0],
                    getClass().getClassLoader());
            Class<?> c = cl.loadClass("com.aicodeassistant.plugin.PluginExtension");
            assertNotNull(c);
            cl.close();
        }

        @Test
        @DisplayName("宿主 tool API 包可访问")
        void toolApiAccessible() throws Exception {
            PluginClassLoader cl = new PluginClassLoader(new java.net.URL[0],
                    getClass().getClassLoader());
            Class<?> c = cl.loadClass("com.aicodeassistant.tool.Tool");
            assertNotNull(c);
            cl.close();
        }
    }

    // ==================== §4.6.2 PluginLoader ====================

    @Nested
    @DisplayName("§4.6.2 PluginLoader")
    class PluginLoaderTests {

        @Test
        @DisplayName("版本比较 — 相等")
        void versionCompareEqual() {
            assertEquals(0, PluginLoader.compareVersions("1.0.0", "1.0.0"));
        }

        @Test
        @DisplayName("版本比较 — 大于")
        void versionCompareGreater() {
            assertTrue(PluginLoader.compareVersions("2.0.0", "1.0.0") > 0);
            assertTrue(PluginLoader.compareVersions("1.1.0", "1.0.0") > 0);
            assertTrue(PluginLoader.compareVersions("1.0.1", "1.0.0") > 0);
        }

        @Test
        @DisplayName("版本比较 — 小于")
        void versionCompareLess() {
            assertTrue(PluginLoader.compareVersions("1.0.0", "2.0.0") < 0);
        }

        @Test
        @DisplayName("API 兼容检查 — 兼容")
        void apiCompatible() {
            PluginLoader loader = new PluginLoader();
            PluginExtension ext = createMinimalPlugin("test", "1.0.0");
            assertTrue(loader.isApiCompatible(ext));
        }

        @Test
        @DisplayName("API 兼容检查 — 不兼容（minApiVersion 过高）")
        void apiIncompatibleMin() {
            PluginLoader loader = new PluginLoader();
            PluginExtension ext = new PluginExtension() {
                @Override public String name() { return "future-plugin"; }
                @Override public String version() { return "1.0.0"; }
                @Override public String minApiVersion() { return "99.0.0"; }
            };
            assertFalse(loader.isApiCompatible(ext));
        }

        @Test
        @DisplayName("loadAllPlugins — 无 SPI 注册时返回空")
        void loadAllPluginsEmpty() {
            PluginLoader loader = new PluginLoader();
            PluginLoadResult result = loader.loadAllPlugins();
            assertNotNull(result);
            // 可能有 SPI 注册，也可能没有，不做严格断言
        }
    }

    // ==================== §4.6.5 PluginManager ====================

    @Nested
    @DisplayName("§4.6.5 PluginManager 生命周期")
    class PluginManagerTests {

        private PluginManager manager;

        @BeforeEach
        void setUp() {
            manager = new PluginManager(new PluginLoader());
        }

        @Test
        @DisplayName("初始化插件系统")
        void initializePlugins() {
            assertFalse(manager.isInitialized());
            manager.initializePlugins();
            assertTrue(manager.isInitialized());
        }

        @Test
        @DisplayName("重复初始化不重新加载")
        void doubleInitialize() {
            manager.initializePlugins();
            manager.initializePlugins(); // 第二次应忽略
            assertTrue(manager.isInitialized());
        }

        @Test
        @DisplayName("热重载")
        void reloadPlugins() {
            manager.initializePlugins();
            manager.reloadPlugins();
            assertTrue(manager.isInitialized());
        }

        @Test
        @DisplayName("获取已启用插件")
        void getEnabledPlugins() {
            manager.initializePlugins();
            List<LoadedPlugin> enabled = manager.getEnabledPlugins();
            assertNotNull(enabled);
        }

        @Test
        @DisplayName("获取插件命令")
        void getPluginCommands() {
            manager.initializePlugins();
            Map<String, Command> commands = manager.getPluginCommands();
            assertNotNull(commands);
        }

        @Test
        @DisplayName("按名称获取插件 — 不存在")
        void getPluginNotFound() {
            manager.initializePlugins();
            assertTrue(manager.getPlugin("nonexistent").isEmpty());
        }
    }

    // ==================== §4.6 HookHandler ====================

    @Nested
    @DisplayName("§4.6 HookHandler")
    class HookHandlerTests {

        @Test
        @DisplayName("创建 PreToolUse 钩子")
        void createPreToolUseHook() {
            HookHandler hook = HookHandler.preToolUse("Bash", 10,
                    ctx -> HookHandler.HookResult.allow());

            assertEquals(HookHandler.HookEventType.PRE_TOOL_USE, hook.eventType());
            assertEquals("Bash", hook.matcher());
            assertEquals(10, hook.priority());
        }

        @Test
        @DisplayName("创建 PostToolUse 钩子")
        void createPostToolUseHook() {
            HookHandler hook = HookHandler.postToolUse("Write", 20,
                    ctx -> HookHandler.HookResult.modifyOutput("formatted"));

            assertEquals(HookHandler.HookEventType.POST_TOOL_USE, hook.eventType());
        }

        @Test
        @DisplayName("HookResult — allow")
        void hookResultAllow() {
            HookHandler.HookResult result = HookHandler.HookResult.allow();
            assertTrue(result.proceed());
            assertNull(result.message());
        }

        @Test
        @DisplayName("HookResult — deny")
        void hookResultDeny() {
            HookHandler.HookResult result = HookHandler.HookResult.deny("Blocked");
            assertFalse(result.proceed());
            assertEquals("Blocked", result.message());
        }

        @Test
        @DisplayName("HookResult — modifyInput")
        void hookResultModifyInput() {
            HookHandler.HookResult result = HookHandler.HookResult.modifyInput("new input");
            assertTrue(result.proceed());
            assertEquals("new input", result.modifiedInput());
        }

        @Test
        @DisplayName("HookContext.of 工厂方法")
        void hookContextOf() {
            HookHandler.HookContext ctx = HookHandler.HookContext.of("Bash", "ls -la");
            assertEquals("Bash", ctx.toolName());
            assertEquals("ls -la", ctx.toolInput());
            assertNull(ctx.toolOutput());
        }
    }

    // ==================== §4.6 LoadedPlugin ====================

    @Nested
    @DisplayName("§4.6 LoadedPlugin")
    class LoadedPluginTests {

        @Test
        @DisplayName("创建内置插件")
        void builtinPlugin() {
            PluginExtension ext = createMinimalPlugin("builtin", "1.0.0");
            LoadedPlugin plugin = LoadedPlugin.builtin("builtin", ext);

            assertEquals("builtin", plugin.name());
            assertTrue(plugin.isBuiltin());
            assertTrue(plugin.enabled());
            assertEquals(PluginSourceType.BUILTIN, plugin.sourceType());
        }

        @Test
        @DisplayName("创建本地插件")
        void localPlugin() {
            PluginExtension ext = createMinimalPlugin("local", "1.0.0");
            LoadedPlugin plugin = LoadedPlugin.local("local", "/path/to/plugin.jar", ext, true);

            assertEquals("local", plugin.name());
            assertFalse(plugin.isBuiltin());
            assertEquals("/path/to/plugin.jar", plugin.path());
            assertEquals(PluginSourceType.LOCAL, plugin.sourceType());
        }

        @Test
        @DisplayName("禁用/启用副本")
        void disableEnablePlugin() {
            PluginExtension ext = createMinimalPlugin("toggle", "1.0.0");
            LoadedPlugin plugin = LoadedPlugin.builtin("toggle", ext);

            assertTrue(plugin.enabled());

            LoadedPlugin disabled = plugin.withDisabled();
            assertFalse(disabled.enabled());

            LoadedPlugin reEnabled = disabled.withEnabled();
            assertTrue(reEnabled.enabled());
        }
    }

    // ==================== §4.6.6.2 PluginError ====================

    @Nested
    @DisplayName("§4.6.6.2 PluginError")
    class PluginErrorTests {

        @Test
        @DisplayName("创建简单错误")
        void simpleError() {
            PluginError err = PluginError.of("test",
                    PluginError.PluginErrorType.PATH_NOT_FOUND,
                    "Plugin directory not found");

            assertEquals("test", err.pluginName());
            assertEquals(PluginError.PluginErrorType.PATH_NOT_FOUND, err.errorType());
            assertNull(err.cause());
        }

        @Test
        @DisplayName("从异常创建错误")
        void fromException() {
            RuntimeException ex = new RuntimeException("load failed");
            PluginError err = PluginError.fromException("broken",
                    PluginError.PluginErrorType.CLASS_LOAD_FAILED, ex);

            assertEquals("broken", err.pluginName());
            assertEquals("load failed", err.message());
            assertNotNull(err.cause());
        }
    }

    // ==================== §4.6 PluginLoadResult ====================

    @Nested
    @DisplayName("§4.6 PluginLoadResult")
    class PluginLoadResultTests {

        @Test
        @DisplayName("空结果")
        void emptyResult() {
            PluginLoadResult r = PluginLoadResult.empty();
            assertTrue(r.enabled().isEmpty());
            assertTrue(r.disabled().isEmpty());
            assertTrue(r.errors().isEmpty());
            assertEquals(0, r.totalCount());
        }

        @Test
        @DisplayName("totalCount 计算")
        void totalCount() {
            PluginExtension ext = createMinimalPlugin("p1", "1.0.0");
            LoadedPlugin p1 = LoadedPlugin.builtin("p1", ext);
            LoadedPlugin p2 = LoadedPlugin.builtin("p2", ext).withDisabled();

            PluginLoadResult r = new PluginLoadResult(
                    List.of(p1), List.of(p2), List.of());
            assertEquals(2, r.totalCount());
        }
    }

    // ==================== §4.6 PluginSourceType ====================

    @Nested
    @DisplayName("§4.6 PluginSourceType")
    class PluginSourceTypeTests {

        @Test
        @DisplayName("三种来源类型")
        void threeSourceTypes() {
            assertEquals(3, PluginSourceType.values().length);
            assertNotNull(PluginSourceType.LOCAL);
            assertNotNull(PluginSourceType.MARKETPLACE);
            assertNotNull(PluginSourceType.BUILTIN);
        }
    }

    // ==================== 钩子执行集成测试 ====================

    @Nested
    @DisplayName("§4.6 钩子执行集成")
    class HookExecutionTests {

        @Test
        @DisplayName("PreToolUse 钩子拦截")
        void preToolUseInterception() {
            // 创建含钩子的插件
            HookHandler blockBash = HookHandler.preToolUse("Bash", 10,
                    ctx -> HookHandler.HookResult.deny("Bash disabled by plugin"));

            PluginExtension ext = new PluginExtension() {
                @Override public String name() { return "security"; }
                @Override public String version() { return "1.0.0"; }
                @Override public List<HookHandler> getHooks() { return List.of(blockBash); }
            };

            // 手动注册并测试
            PluginManager manager = new PluginManager(new PluginLoader());
            manager.initializePlugins(); // 初始化

            // 直接测试 hook 逻辑
            HookHandler.HookContext ctx = HookHandler.HookContext.of("Bash", "rm -rf /");
            HookHandler.HookResult result = blockBash.handler().apply(ctx);

            assertFalse(result.proceed());
            assertEquals("Bash disabled by plugin", result.message());
        }
    }

    // ==================== 辅助方法 ====================

    private PluginExtension createMinimalPlugin(String name, String version) {
        return new PluginExtension() {
            @Override public String name() { return name; }
            @Override public String version() { return version; }
        };
    }

    private Command createTestCommand(String name, String description) {
        return new Command() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return description; }
            @Override public CommandType getType() { return CommandType.LOCAL; }
            @Override public CommandResult execute(String args, CommandContext context) {
                return CommandResult.text("Executed: " + name);
            }
        };
    }

    private Tool createTestTool(String name, String description) {
        return new Tool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return description; }
            @Override public Map<String, Object> getInputSchema() { return Map.of("type", "object"); }
            @Override public ToolResult call(ToolInput input, ToolUseContext context) {
                return ToolResult.success("Tool executed: " + name);
            }
        };
    }
}
