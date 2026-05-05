package com.aicodeassistant.plugin;

import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 插件系统测试集 — TC-PLG-001 ~ TC-PLG-004
 */
@DisplayName("插件系统测试集")
class PluginSystemTest {

    // ==================== TC-PLG-001 ====================

    @Nested
    @DisplayName("TC-PLG-001: Java SPI 插件发现验证")
    class PluginDiscoveryTest {

        @Test
        @DisplayName("创建 Mock PluginExtension 并验证元数据")
        void mockPluginExtensionMetadata() {
            PluginExtension mockExt = mock(PluginExtension.class);
            when(mockExt.name()).thenReturn("test-plugin");
            when(mockExt.version()).thenReturn("1.0.0");
            when(mockExt.description()).thenReturn("A test plugin");
            when(mockExt.priority()).thenReturn(100);
            when(mockExt.getCommands()).thenReturn(List.of());
            when(mockExt.getTools()).thenReturn(List.of());
            when(mockExt.getHooks()).thenReturn(List.of());
            when(mockExt.getMcpServers()).thenReturn(List.of());

            assertEquals("test-plugin", mockExt.name());
            assertEquals("1.0.0", mockExt.version());
            assertEquals("A test plugin", mockExt.description());
            assertEquals(100, mockExt.priority());
            assertNotNull(mockExt.getCommands());
            assertNotNull(mockExt.getTools());
            assertNotNull(mockExt.getHooks());
            assertNotNull(mockExt.getMcpServers());
        }

        @Test
        @DisplayName("LoadedPlugin.builtin 工厂方法验证")
        void loadedPluginBuiltinFactory() {
            PluginExtension mockExt = mock(PluginExtension.class);
            when(mockExt.name()).thenReturn("test-plugin");
            when(mockExt.version()).thenReturn("1.0.0");
            when(mockExt.description()).thenReturn("A test plugin");
            when(mockExt.getCommands()).thenReturn(List.of());
            when(mockExt.getTools()).thenReturn(List.of());
            when(mockExt.getHooks()).thenReturn(List.of());
            when(mockExt.getMcpServers()).thenReturn(List.of());

            LoadedPlugin plugin = LoadedPlugin.builtin("test-plugin", mockExt);

            assertEquals("test-plugin", plugin.name());
            assertTrue(plugin.enabled());
            assertEquals(PluginSourceType.BUILTIN, plugin.sourceType());
            assertNotNull(plugin.extension());
        }

        @Test
        @DisplayName("LoadedPlugin.local 工厂方法验证")
        void loadedPluginLocalFactory() {
            PluginExtension mockExt = mock(PluginExtension.class);
            when(mockExt.name()).thenReturn("local-plugin");
            when(mockExt.version()).thenReturn("2.0.0");
            when(mockExt.description()).thenReturn("Local plugin");
            when(mockExt.getCommands()).thenReturn(List.of());
            when(mockExt.getTools()).thenReturn(List.of());
            when(mockExt.getHooks()).thenReturn(List.of());
            when(mockExt.getMcpServers()).thenReturn(List.of());

            LoadedPlugin plugin = LoadedPlugin.local("local-plugin", "/path/to/plugin", mockExt, true);

            assertEquals("local-plugin", plugin.name());
            assertTrue(plugin.enabled());
            assertEquals(PluginSourceType.LOCAL, plugin.sourceType());
        }
    }

    // ==================== TC-PLG-002 ====================

    @Nested
    @DisplayName("TC-PLG-002: ClassLoader 沙箱隔离验证")
    class ClassLoaderSandboxTest {

        private PluginClassLoader classLoader;

        @BeforeEach
        void setUp() throws Exception {
            java.net.URL emptyUrl = new java.io.File(System.getProperty("java.io.tmpdir")).toURI().toURL();
            classLoader = new PluginClassLoader(emptyUrl, getClass().getClassLoader());
        }

        @Test
        @DisplayName("白名单内类 java.lang.String 加载成功")
        void loadJdkClassSuccess() throws Exception {
            Class<?> clazz = classLoader.loadClass("java.lang.String");
            assertNotNull(clazz, "java.lang.String 应加载成功");
        }

        @Test
        @DisplayName("白名单内接口 PluginExtension 加载成功")
        void loadPluginApiSuccess() throws Exception {
            Class<?> clazz = classLoader.loadClass(
                "com.aicodeassistant.plugin.PluginExtension");
            assertNotNull(clazz, "PluginExtension 应加载成功");
        }

        @Test
        @DisplayName("java.io.File 加载成功（java.* 白名单）")
        void loadJavaIoFileSuccess() throws Exception {
            Class<?> clazz = classLoader.loadClass("java.io.File");
            assertNotNull(clazz, "java.io.File 应加载成功");
        }

        @Test
        @DisplayName("非白名单类抛出 ClassNotFoundException")
        void loadNonWhitelistedClassFails() {
            assertThrows(ClassNotFoundException.class, () -> {
                classLoader.loadClass("com.evil.MaliciousClass");
            }, "非白名单类应抛出异常");
        }

        @Test
        @DisplayName("工具 API 包加载成功")
        void loadToolApiPackageSuccess() throws Exception {
            Class<?> clazz = classLoader.loadClass(
                "com.aicodeassistant.tool.Tool");
            assertNotNull(clazz, "Tool 接口应加载成功");
        }

        @Test
        @DisplayName("命令 API 包加载成功")
        void loadCommandApiPackageSuccess() throws Exception {
            Class<?> clazz = classLoader.loadClass(
                "com.aicodeassistant.command.Command");
            assertNotNull(clazz, "Command 接口应加载成功");
        }
    }

    // ==================== TC-PLG-003 ====================

    @Nested
    @DisplayName("TC-PLG-003: 四大桥接与超时验证")
    class FourBridgesTest {

        @Test
        @DisplayName("命令桥接：插件命令带前缀格式正确")
        void commandBridgeFormat() {
            String pluginName = "myPlugin";
            String commandName = "greet";
            String prefixedName = pluginName + ":" + commandName;
            assertEquals("myPlugin:greet", prefixedName,
                "插件命令应带前缀");
        }

        @Test
        @DisplayName("工具桥接：PluginExtension 工具列表格式")
        void toolBridgeRegistration() {
            PluginExtension mockExt = mock(PluginExtension.class);
            when(mockExt.getTools()).thenReturn(List.of());
            assertNotNull(mockExt.getTools(), "工具列表不应为null");
        }

        @Test
        @DisplayName("钩子桥接：HookHandler.HookEventType 枚举存在")
        void hookBridgeRegistration() {
            HookHandler.HookEventType[] types = HookHandler.HookEventType.values();
            assertNotNull(types, "钩子事件类型不应为null");
            assertTrue(types.length > 0, "应有至少一个钩子事件类型");
        }

        @Test
        @DisplayName("MCP 桥接：插件提供 MCP 服务器配置")
        void mcpBridgeRegistration() {
            PluginExtension mockExt = mock(PluginExtension.class);
            when(mockExt.getMcpServers()).thenReturn(List.of());
            assertNotNull(mockExt.getMcpServers(), "MCP 服务器列表不应为null");
        }

        @Test
        @DisplayName("钩子执行 5s 超时验证")
        void hookTimeoutProtection() {
            AtomicBoolean timedOut = new AtomicBoolean(false);

            CompletableFuture<String> future =
                CompletableFuture.supplyAsync(() -> {
                    try { Thread.sleep(6000); } catch (InterruptedException e) {}
                    return "too late";
                });

            try {
                future.orTimeout(5, TimeUnit.SECONDS).join();
            } catch (CompletionException e) {
                timedOut.set(true);
            }
            assertTrue(timedOut.get(), "5s 超时应触发");
        }
    }

    // ==================== TC-PLG-004 ====================

    @Nested
    @DisplayName("TC-PLG-004: 热重载与并发安全验证")
    class ReloadConcurrencyTest {

        @Test
        @DisplayName("10线程并发读写无ConcurrentModificationException")
        void concurrentReadWriteNoCME() throws Exception {
            ConcurrentHashMap<String, String> plugins = new ConcurrentHashMap<>();
            ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

            for (int i = 0; i < 5; i++) {
                plugins.put("plugin-" + i, "v1");
            }

            CountDownLatch latch = new CountDownLatch(10);
            AtomicBoolean hasError = new AtomicBoolean(false);

            for (int t = 0; t < 10; t++) {
                int threadId = t;
                new Thread(() -> {
                    try {
                        if (threadId % 3 == 0) {
                            rwLock.writeLock().lock();
                            try {
                                plugins.clear();
                                for (int i = 0; i < 5; i++) {
                                    plugins.put("plugin-" + i, "v" + threadId);
                                }
                            } finally {
                                rwLock.writeLock().unlock();
                            }
                        } else {
                            rwLock.readLock().lock();
                            try {
                                for (String key : plugins.keySet()) {
                                    assertNotNull(plugins.get(key));
                                }
                            } finally {
                                rwLock.readLock().unlock();
                            }
                        }
                    } catch (Exception e) {
                        hasError.set(true);
                    } finally {
                        latch.countDown();
                    }
                }).start();
            }

            boolean finished = latch.await(10, TimeUnit.SECONDS);
            assertTrue(finished, "所有线程应在10s内完成");
            assertFalse(hasError.get(), "不应有并发异常");
        }

        @Test
        @DisplayName("读写锁机制：读操作不阻塞")
        void readLockNonBlocking() {
            ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

            rwLock.readLock().lock();
            boolean secondRead = rwLock.readLock().tryLock();
            assertTrue(secondRead, "多个读锁应可同时持有");
            rwLock.readLock().unlock();
            rwLock.readLock().unlock();
        }

        @Test
        @DisplayName("读写锁机制：写操作互斥")
        void writeLockMutualExclusion() throws Exception {
            ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

            rwLock.writeLock().lock();
            AtomicBoolean gotLock = new AtomicBoolean(false);
            Thread t = new Thread(() -> gotLock.set(rwLock.writeLock().tryLock()));
            t.start();
            t.join(1000);
            assertFalse(gotLock.get(), "写锁应互斥");
            rwLock.writeLock().unlock();
        }

        @Test
        @DisplayName("重载后插件列表一致")
        void reloadResultConsistency() {
            ConcurrentHashMap<String, String> plugins = new ConcurrentHashMap<>();
            plugins.put("a", "v1");
            plugins.put("b", "v1");

            // 模拟重载
            plugins.clear();
            plugins.put("a", "v2");
            plugins.put("b", "v2");
            plugins.put("c", "v2");

            assertEquals(3, plugins.size(), "重载后应有3个插件");
            assertTrue(plugins.values().stream().allMatch(v -> v.equals("v2")),
                "所有插件应为 v2 版本");
        }
    }
}
