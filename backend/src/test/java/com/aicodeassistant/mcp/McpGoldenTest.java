package com.aicodeassistant.mcp;

import com.aicodeassistant.mcp.McpServerConnection.McpResourceDefinition;
import com.aicodeassistant.mcp.McpServerConnection.McpToolDefinition;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP 集成黄金测试 — 覆盖配置模型、客户端管理、工具适配、资源工具、认证缓存。
 */
class McpGoldenTest {

    // ===== 1. 配置模型测试 =====

    @Nested
    @DisplayName("1. MCP 配置模型")
    class ConfigModelTests {

        @Test
        @DisplayName("1.1 McpTransportType — 8 种传输类型")
        void transportTypes() {
            assertEquals(8, McpTransportType.values().length);
            assertNotNull(McpTransportType.STDIO);
            assertNotNull(McpTransportType.SSE);
            assertNotNull(McpTransportType.HTTP);
            assertNotNull(McpTransportType.WS);
        }

        @Test
        @DisplayName("1.2 McpConfigScope — 7 种作用域")
        void configScopes() {
            assertEquals(7, McpConfigScope.values().length);
            assertNotNull(McpConfigScope.LOCAL);
            assertNotNull(McpConfigScope.USER);
            assertNotNull(McpConfigScope.DYNAMIC);
        }

        @Test
        @DisplayName("1.3 McpServerConfig — stdio 工厂方法")
        void stdioConfig() {
            McpServerConfig config = McpServerConfig.stdio("test-server", "node", List.of("server.js"));
            assertEquals("test-server", config.name());
            assertEquals(McpTransportType.STDIO, config.type());
            assertEquals("node", config.command());
            assertEquals(List.of("server.js"), config.args());
        }

        @Test
        @DisplayName("1.4 McpServerConfig — SSE 工厂方法")
        void sseConfig() {
            McpServerConfig config = McpServerConfig.sse("remote", "http://localhost:3000/sse");
            assertEquals(McpTransportType.SSE, config.type());
            assertEquals("http://localhost:3000/sse", config.url());
        }

        @Test
        @DisplayName("1.5 McpConnectionStatus — 5 种状态")
        void connectionStatuses() {
            assertEquals(5, McpConnectionStatus.values().length);
        }
    }

    // ===== 2. McpClientManager 测试 =====

    @Nested
    @DisplayName("2. McpClientManager")
    class ClientManagerTests {

        private McpClientManager manager;

        @BeforeEach
        void setUp() {
            manager = new McpClientManager(new McpConfiguration());
        }

        @Test
        @DisplayName("2.1 SmartLifecycle — phase = 2")
        void lifecycle() {
            assertEquals(2, manager.getPhase());
            assertFalse(manager.isRunning());
            manager.start();
            assertTrue(manager.isRunning());
            manager.stop();
            assertFalse(manager.isRunning());
        }

        @Test
        @DisplayName("2.2 添加服务器 — 成功连接")
        void addServer() {
            var config = McpServerConfig.stdio("test", "node", List.of());
            var conn = manager.addServer(config);
            assertEquals(McpConnectionStatus.CONNECTED, conn.getStatus());
            assertEquals(1, manager.connectionCount());
        }

        @Test
        @DisplayName("2.3 移除服务器")
        void removeServer() {
            manager.addServer(McpServerConfig.stdio("test", "node", List.of()));
            assertTrue(manager.removeServer("test"));
            assertFalse(manager.removeServer("nonexistent"));
            assertEquals(0, manager.connectionCount());
        }

        @Test
        @DisplayName("2.4 获取连接")
        void getConnection() {
            manager.addServer(McpServerConfig.sse("remote", "http://localhost:3000"));
            assertTrue(manager.getConnection("remote").isPresent());
            assertTrue(manager.getConnection("missing").isEmpty());
        }

        @Test
        @DisplayName("2.5 listConnections 和 getConnectedServers")
        void listAndFilter() {
            manager.addServer(McpServerConfig.stdio("s1", "node", List.of()));
            manager.addServer(McpServerConfig.sse("s2", "http://localhost"));
            assertEquals(2, manager.listConnections().size());
            assertEquals(2, manager.getConnectedServers().size());
        }

        @Test
        @DisplayName("2.6 shutdown 清空所有连接")
        void shutdown() {
            manager.addServer(McpServerConfig.stdio("s1", "node", List.of()));
            manager.shutdown();
            assertEquals(0, manager.connectionCount());
        }

        @Test
        @DisplayName("2.7 指数退避计算")
        void backoffCalculation() {
            assertEquals(1000, McpClientManager.calculateBackoff(1));
            assertEquals(2000, McpClientManager.calculateBackoff(2));
            assertEquals(4000, McpClientManager.calculateBackoff(3));
            assertEquals(8000, McpClientManager.calculateBackoff(4));
            assertEquals(16000, McpClientManager.calculateBackoff(5));
            assertEquals(30000, McpClientManager.calculateBackoff(6)); // 上限
        }

        @Test
        @DisplayName("2.8 工具发现 — 无工具时返回空列表")
        void discoverToolsEmpty() {
            manager.addServer(McpServerConfig.stdio("s1", "node", List.of()));
            assertTrue(manager.discoverAndWrapTools().isEmpty());
        }

        @Test
        @DisplayName("2.9 工具发现 — 有工具时正确包装")
        void discoverToolsWithTools() {
            var config = McpServerConfig.stdio("myserver", "node", List.of());
            var conn = manager.addServer(config);
            conn.setTools(List.of(
                    new McpToolDefinition("read_file", "Read a file", Map.of("type", "object"))
            ));

            var tools = manager.discoverAndWrapTools();
            assertEquals(1, tools.size());
            assertEquals("mcp__myserver__read_file", tools.get(0).getName());
        }
    }

    // ===== 3. McpToolAdapter 测试 =====

    @Nested
    @DisplayName("3. McpToolAdapter")
    class ToolAdapterTests {

        @Test
        @DisplayName("3.1 工具名称格式: mcp__server__tool")
        void toolNaming() {
            var conn = new McpServerConnection(McpServerConfig.stdio("srv", "node", List.of()));
            conn.connect();
            var adapter = new McpToolAdapter("mcp__srv__do_thing", "Does things",
                    Map.of("type", "object"), conn, "do_thing");

            assertEquals("mcp__srv__do_thing", adapter.getName());
            assertEquals("do_thing", adapter.getOriginalToolName());
            assertEquals("srv", adapter.getServerName());
            assertEquals("mcp", adapter.getGroup());
        }

        @Test
        @DisplayName("3.2 已连接时调用成功")
        void callWhenConnected() {
            var conn = new McpServerConnection(McpServerConfig.stdio("srv", "node", List.of()));
            conn.connect();
            var adapter = new McpToolAdapter("mcp__srv__test", "Test",
                    Map.of("type", "object"), conn, "test");

            ToolResult result = adapter.call(
                    ToolInput.from(Map.of("key", "val")),
                    ToolUseContext.of("/tmp", "s1"));
            assertFalse(result.isError());
            assertTrue(result.content().contains("test"));
            assertEquals("srv", result.metadata().get("mcpServer"));
        }

        @Test
        @DisplayName("3.3 未连接时返回错误")
        void callWhenDisconnected() {
            var conn = new McpServerConnection(McpServerConfig.stdio("srv", "node", List.of()));
            // 不调用 connect() — 状态为 PENDING
            var adapter = new McpToolAdapter("mcp__srv__test", "Test",
                    Map.of("type", "object"), conn, "test");

            ToolResult result = adapter.call(
                    ToolInput.from(Map.of()),
                    ToolUseContext.of("/tmp", "s1"));
            assertTrue(result.isError());
            assertTrue(result.content().contains("not connected"));
        }

        @Test
        @DisplayName("3.4 默认不并发安全")
        void concurrencySafety() {
            var conn = new McpServerConnection(McpServerConfig.stdio("srv", "node", List.of()));
            var adapter = new McpToolAdapter("test", "Test", Map.of(), conn, "test");
            assertFalse(adapter.isConcurrencySafe(ToolInput.from(Map.of())));
            assertTrue(adapter.shouldDefer());
        }
    }

    // ===== 4. McpAuthFailureCache 测试 =====

    @Nested
    @DisplayName("4. McpAuthFailureCache")
    class AuthFailureCacheTests {

        private McpAuthFailureCache cache;

        @BeforeEach
        void setUp() {
            cache = new McpAuthFailureCache();
        }

        @Test
        @DisplayName("4.1 初始无缓存")
        void initialEmpty() {
            assertFalse(cache.isAuthFailureCached("server1"));
            assertEquals(0, cache.size());
        }

        @Test
        @DisplayName("4.2 记录失败后缓存生效")
        void recordAndCheck() {
            cache.recordAuthFailure("server1");
            assertTrue(cache.isAuthFailureCached("server1"));
            assertFalse(cache.isAuthFailureCached("server2"));
        }

        @Test
        @DisplayName("4.3 清除单个缓存")
        void clearSingle() {
            cache.recordAuthFailure("server1");
            cache.clearFailure("server1");
            assertFalse(cache.isAuthFailureCached("server1"));
        }

        @Test
        @DisplayName("4.4 清除所有缓存")
        void clearAll() {
            cache.recordAuthFailure("s1");
            cache.recordAuthFailure("s2");
            cache.clearAll();
            assertEquals(0, cache.size());
        }
    }

    // ===== 5. ListMcpResourcesTool 测试 =====

    @Nested
    @DisplayName("5. ListMcpResourcesTool")
    class ListResourcesTests {

        private McpClientManager manager;
        private ListMcpResourcesTool tool;

        @BeforeEach
        void setUp() {
            manager = new McpClientManager(new McpConfiguration());
            tool = new ListMcpResourcesTool(manager);
        }

        @Test
        @DisplayName("5.1 工具名称和标记")
        void nameAndFlags() {
            assertEquals("ListMcpResources", tool.getName());
            assertEquals("mcp", tool.getGroup());
            assertTrue(tool.isReadOnly(ToolInput.from(Map.of())));
            assertTrue(tool.shouldDefer());
        }

        @Test
        @DisplayName("5.2 无连接 — 返回提示")
        void noServers() {
            ToolResult result = tool.call(
                    ToolInput.from(Map.of()),
                    ToolUseContext.of("/tmp", "s1"));
            assertFalse(result.isError());
            assertTrue(result.content().contains("No MCP servers connected"));
        }

        @Test
        @DisplayName("5.3 有资源 — 列出所有")
        void withResources() {
            var conn = manager.addServer(McpServerConfig.stdio("srv", "node", List.of()));
            conn.setResources(List.of(
                    new McpResourceDefinition("file:///data.json", "data", "application/json", "Test data")
            ));

            ToolResult result = tool.call(
                    ToolInput.from(Map.of()),
                    ToolUseContext.of("/tmp", "s1"));
            assertFalse(result.isError());
            assertTrue(result.content().contains("MCP Resources (1)"));
            assertTrue(result.content().contains("data"));
            assertTrue(result.content().contains("file:///data.json"));
        }

        @Test
        @DisplayName("5.4 按服务器名过滤")
        void filterByServer() {
            manager.addServer(McpServerConfig.stdio("srv1", "node", List.of()));
            ToolResult result = tool.call(
                    ToolInput.from(Map.of("server", "nonexistent")),
                    ToolUseContext.of("/tmp", "s1"));
            assertTrue(result.content().contains("No MCP server found"));
        }
    }

    // ===== 6. ReadMcpResourceTool 测试 =====

    @Nested
    @DisplayName("6. ReadMcpResourceTool")
    class ReadResourceTests {

        private McpClientManager manager;
        private ReadMcpResourceTool tool;

        @BeforeEach
        void setUp() {
            manager = new McpClientManager(new McpConfiguration());
            tool = new ReadMcpResourceTool(manager);
        }

        @Test
        @DisplayName("6.1 工具名称和标记")
        void nameAndFlags() {
            assertEquals("ReadMcpResource", tool.getName());
            assertEquals("mcp", tool.getGroup());
            assertTrue(tool.isReadOnly(ToolInput.from(Map.of())));
        }

        @Test
        @DisplayName("6.2 服务器不存在 — 错误")
        void serverNotFound() {
            ToolResult result = tool.call(
                    ToolInput.from(Map.of("server", "missing", "uri", "file:///x")),
                    ToolUseContext.of("/tmp", "s1"));
            assertTrue(result.isError());
            assertTrue(result.content().contains("MCP server not found"));
        }

        @Test
        @DisplayName("6.3 资源不存在 — 错误")
        void resourceNotFound() {
            manager.addServer(McpServerConfig.stdio("srv", "node", List.of()));
            ToolResult result = tool.call(
                    ToolInput.from(Map.of("server", "srv", "uri", "file:///missing")),
                    ToolUseContext.of("/tmp", "s1"));
            assertTrue(result.isError());
            assertTrue(result.content().contains("Resource not found"));
        }

        @Test
        @DisplayName("6.4 资源存在 — 成功读取")
        void readSuccess() {
            var conn = manager.addServer(McpServerConfig.stdio("srv", "node", List.of()));
            conn.setResources(List.of(
                    new McpResourceDefinition("file:///data.json", "data", "application/json", "Test")
            ));

            ToolResult result = tool.call(
                    ToolInput.from(Map.of("server", "srv", "uri", "file:///data.json")),
                    ToolUseContext.of("/tmp", "s1"));
            assertFalse(result.isError());
            assertTrue(result.content().contains("data"));
            assertTrue(result.content().contains("file:///data.json"));
        }
    }

    // ===== 7. McpServerConnection 测试 =====

    @Nested
    @DisplayName("7. McpServerConnection")
    class ConnectionTests {

        @Test
        @DisplayName("7.1 初始状态为 PENDING")
        void initialState() {
            var conn = new McpServerConnection(McpServerConfig.stdio("test", "node", List.of()));
            assertEquals(McpConnectionStatus.PENDING, conn.getStatus());
            assertTrue(conn.getTools().isEmpty());
            assertTrue(conn.getResources().isEmpty());
        }

        @Test
        @DisplayName("7.2 connect 后状态为 CONNECTED")
        void connectState() {
            var conn = new McpServerConnection(McpServerConfig.stdio("test", "node", List.of()));
            conn.connect();
            assertEquals(McpConnectionStatus.CONNECTED, conn.getStatus());
        }

        @Test
        @DisplayName("7.3 close 后状态为 DISABLED")
        void closeState() {
            var conn = new McpServerConnection(McpServerConfig.stdio("test", "node", List.of()));
            conn.connect();
            conn.close();
            assertEquals(McpConnectionStatus.DISABLED, conn.getStatus());
            assertTrue(conn.getTools().isEmpty());
        }

        @Test
        @DisplayName("7.4 重连计数")
        void reconnectAttempts() {
            var conn = new McpServerConnection(McpServerConfig.stdio("test", "node", List.of()));
            assertEquals(0, conn.getReconnectAttempts());
            conn.incrementReconnectAttempts();
            conn.incrementReconnectAttempts();
            assertEquals(2, conn.getReconnectAttempts());
            conn.resetReconnectAttempts();
            assertEquals(0, conn.getReconnectAttempts());
        }
    }
}
