package com.aicodeassistant.lsp;

import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LSPTool 黄金测试 — 覆盖配置、服务器实例、管理器和工具。
 */
class LSPGoldenTest {

    // ===== 1. LSPServerConfig 测试 =====

    @Nested
    @DisplayName("1. LSPServerConfig")
    class ConfigTests {

        @Test
        @DisplayName("1.1 TypeScript 工厂方法")
        void typescript() {
            var config = LSPServerConfig.typescript();
            assertEquals("tsserver", config.name());
            assertEquals("npx", config.command());
            assertTrue(config.fileExtensions().contains(".ts"));
            assertTrue(config.fileExtensions().contains(".jsx"));
            assertEquals(30000, config.startupTimeoutMs());
        }

        @Test
        @DisplayName("1.2 Python 工厂方法")
        void python() {
            var config = LSPServerConfig.python();
            assertEquals("pyright", config.name());
            assertTrue(config.fileExtensions().contains(".py"));
        }

        @Test
        @DisplayName("1.3 Go 工厂方法")
        void go() {
            var config = LSPServerConfig.go();
            assertEquals("gopls", config.name());
            assertEquals("gopls", config.command());
            assertTrue(config.fileExtensions().contains(".go"));
        }

        @Test
        @DisplayName("1.4 Rust 工厂方法")
        void rust() {
            var config = LSPServerConfig.rust();
            assertEquals("rust-analyzer", config.name());
            assertTrue(config.fileExtensions().contains(".rs"));
        }

        @Test
        @DisplayName("1.5 Java 工厂方法")
        void java() {
            var config = LSPServerConfig.java(Path.of("/tmp/project"));
            assertEquals("jdtls", config.name());
            assertEquals(60000, config.startupTimeoutMs()); // Java 启动慢
            assertTrue(config.fileExtensions().contains(".java"));
        }
    }

    // ===== 2. LSPServerInstance 测试 =====

    @Nested
    @DisplayName("2. LSPServerInstance")
    class InstanceTests {

        @Test
        @DisplayName("2.1 初始状态未运行")
        void initialState() {
            var instance = new LSPServerInstance(LSPServerConfig.typescript());
            assertFalse(instance.isRunning());
        }

        @Test
        @DisplayName("2.2 start/stop 生命周期")
        void lifecycle() {
            var instance = new LSPServerInstance(LSPServerConfig.typescript());
            instance.start();
            assertTrue(instance.isRunning());
            instance.stop();
            assertFalse(instance.isRunning());
        }

        @Test
        @DisplayName("2.3 运行中可发送请求")
        void sendRequest() {
            var instance = new LSPServerInstance(LSPServerConfig.typescript());
            instance.start();
            var result = instance.sendRequest("textDocument/definition", Map.of());
            assertNotNull(result);
            assertEquals("textDocument/definition", result.get("method"));
        }

        @Test
        @DisplayName("2.4 未运行时发送请求抛异常")
        void sendRequestNotRunning() {
            var instance = new LSPServerInstance(LSPServerConfig.typescript());
            assertThrows(IllegalStateException.class,
                    () -> instance.sendRequest("textDocument/definition", Map.of()));
        }
    }

    // ===== 3. LSPServerManager 测试 =====

    @Nested
    @DisplayName("3. LSPServerManager")
    class ManagerTests {

        private LSPServerManager manager;

        @BeforeEach
        void setUp() {
            manager = new LSPServerManager();
        }

        @Test
        @DisplayName("3.1 注册并启动服务器")
        void registerAndStart() {
            manager.registerAndStart(LSPServerConfig.typescript());
            assertEquals(1, manager.serverCount());
            assertTrue(manager.getSupportedExtensions().contains(".ts"));
        }

        @Test
        @DisplayName("3.2 文件扩展名路由")
        void fileRouting() {
            manager.registerAndStart(LSPServerConfig.typescript());
            manager.registerAndStart(LSPServerConfig.python());

            assertNotNull(manager.getServerForFile("/src/app.ts"));
            assertNotNull(manager.getServerForFile("/src/main.py"));
            assertNull(manager.getServerForFile("/src/main.rs"));
        }

        @Test
        @DisplayName("3.3 文件同步协议")
        void fileSync() {
            manager.registerAndStart(LSPServerConfig.typescript());

            assertFalse(manager.isFileOpen("/src/app.ts"));
            manager.openFile("/src/app.ts");
            assertTrue(manager.isFileOpen("/src/app.ts"));

            // 重复打开不应报错
            manager.openFile("/src/app.ts");
            assertTrue(manager.isFileOpen("/src/app.ts"));

            manager.closeFile("/src/app.ts");
            assertFalse(manager.isFileOpen("/src/app.ts"));
        }

        @Test
        @DisplayName("3.4 shutdown 清空所有")
        void shutdown() {
            manager.registerAndStart(LSPServerConfig.typescript());
            manager.registerAndStart(LSPServerConfig.python());
            manager.openFile("/src/app.ts");

            manager.shutdown();
            assertEquals(0, manager.serverCount());
            assertFalse(manager.isFileOpen("/src/app.ts"));
        }

        @Test
        @DisplayName("3.5 getFileExtension 工具方法")
        void fileExtension() {
            assertEquals(".ts", LSPServerManager.getFileExtension("/src/app.ts"));
            assertEquals(".java", LSPServerManager.getFileExtension("Main.java"));
            assertEquals("", LSPServerManager.getFileExtension("Makefile"));
            assertEquals("", LSPServerManager.getFileExtension(null));
        }

        @Test
        @DisplayName("3.6 sendRequest 无服务器返回 null")
        void sendRequestNoServer() {
            assertNull(manager.sendRequest("/src/app.rs", "textDocument/definition", Map.of()));
        }
    }

    // ===== 4. LSPTool 测试 =====

    @Nested
    @DisplayName("4. LSPTool")
    class ToolTests {

        private LSPServerManager manager;
        private LSPTool tool;

        @BeforeEach
        void setUp() {
            manager = new LSPServerManager();
            manager.registerAndStart(LSPServerConfig.typescript());
            tool = new LSPTool(manager);
        }

        @Test
        @DisplayName("4.1 工具名称和标记")
        void nameAndFlags() {
            assertEquals("LSP", tool.getName());
            assertEquals("lsp", tool.getGroup());
            assertTrue(tool.isReadOnly(ToolInput.from(Map.of())));
            assertTrue(tool.isConcurrencySafe(ToolInput.from(Map.of())));
        }

        @Test
        @DisplayName("4.2 无效操作返回错误")
        void invalidOperation() {
            ToolResult result = tool.call(
                    ToolInput.from(Map.of("operation", "invalid")),
                    ToolUseContext.of("/tmp", "s1"));
            assertTrue(result.isError());
            assertTrue(result.content().contains("Unknown LSP operation"));
        }

        @Test
        @DisplayName("4.3 goToDefinition — 缺少 filePath")
        void goToDefNoFile() {
            ToolResult result = tool.call(
                    ToolInput.from(Map.of("operation", "goToDefinition")),
                    ToolUseContext.of("/tmp", "s1"));
            assertTrue(result.isError());
            assertTrue(result.content().contains("filePath"));
        }

        @Test
        @DisplayName("4.4 goToDefinition — 缺少 line/character")
        void goToDefNoPosition() {
            ToolResult result = tool.call(
                    ToolInput.from(Map.of("operation", "goToDefinition",
                            "filePath", "/src/app.ts")),
                    ToolUseContext.of("/tmp", "s1"));
            assertTrue(result.isError());
            assertTrue(result.content().contains("line"));
        }

        @Test
        @DisplayName("4.5 goToDefinition — 成功调用")
        void goToDefSuccess() {
            ToolResult result = tool.call(
                    ToolInput.from(Map.of("operation", "goToDefinition",
                            "filePath", "/src/app.ts", "line", 10, "character", 5)),
                    ToolUseContext.of("/tmp", "s1"));
            assertFalse(result.isError());
            assertTrue(result.content().contains("goToDefinition"));
        }

        @Test
        @DisplayName("4.6 findReferences — 成功调用")
        void findReferences() {
            ToolResult result = tool.call(
                    ToolInput.from(Map.of("operation", "findReferences",
                            "filePath", "/src/app.ts", "line", 5, "character", 3)),
                    ToolUseContext.of("/tmp", "s1"));
            assertFalse(result.isError());
        }

        @Test
        @DisplayName("4.7 hover — 成功调用")
        void hover() {
            ToolResult result = tool.call(
                    ToolInput.from(Map.of("operation", "hover",
                            "filePath", "/src/app.ts", "line", 1, "character", 1)),
                    ToolUseContext.of("/tmp", "s1"));
            assertFalse(result.isError());
        }

        @Test
        @DisplayName("4.8 documentSymbol — 只需 filePath")
        void documentSymbol() {
            ToolResult result = tool.call(
                    ToolInput.from(Map.of("operation", "documentSymbol",
                            "filePath", "/src/app.ts")),
                    ToolUseContext.of("/tmp", "s1"));
            assertFalse(result.isError());
        }

        @Test
        @DisplayName("4.9 workspaceSymbol — 需要 query")
        void workspaceSymbol() {
            ToolResult result = tool.call(
                    ToolInput.from(Map.of("operation", "workspaceSymbol",
                            "query", "MyClass")),
                    ToolUseContext.of("/tmp", "s1"));
            assertFalse(result.isError());
            assertTrue(result.content().contains("MyClass"));
        }

        @Test
        @DisplayName("4.10 workspaceSymbol — 缺少 query")
        void workspaceSymbolNoQuery() {
            ToolResult result = tool.call(
                    ToolInput.from(Map.of("operation", "workspaceSymbol")),
                    ToolUseContext.of("/tmp", "s1"));
            assertTrue(result.isError());
            assertTrue(result.content().contains("query"));
        }

        @Test
        @DisplayName("4.11 无对应语言服务器")
        void noServerForFile() {
            ToolResult result = tool.call(
                    ToolInput.from(Map.of("operation", "goToDefinition",
                            "filePath", "/src/main.rs", "line", 1, "character", 1)),
                    ToolUseContext.of("/tmp", "s1"));
            assertTrue(result.isError());
            assertTrue(result.content().contains("No LSP server available"));
        }

        @Test
        @DisplayName("4.12 incomingCalls — 成功调用")
        void incomingCalls() {
            ToolResult result = tool.call(
                    ToolInput.from(Map.of("operation", "incomingCalls",
                            "filePath", "/src/app.ts", "line", 5, "character", 10)),
                    ToolUseContext.of("/tmp", "s1"));
            assertFalse(result.isError());
        }
    }
}
