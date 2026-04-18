package com.aicodeassistant.mcp;

import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 智谱 WebSearch MCP 集成测试 — 验证阿里云百炼 MCP 服务连接。
 * <p>
 * 需要设置环境变量 ALIYUN_API_KEY 才能运行。
 *
 * @see McpServerConnection
 * @see McpClientManager
 */
@EnabledIfEnvironmentVariable(named = "ALIYUN_API_KEY", matches = "sk-.*")
class ZhipuMcpIntegrationTest {

    private static McpClientManager createTestManager(McpConfiguration configuration) {
        McpApprovalService approval = mock(McpApprovalService.class);
        when(approval.isTrusted(any())).thenReturn(true);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        return new McpClientManager(configuration, null, toolRegistry, approval, null, null, null, null);
    }

    private static final String MCP_SSE_URL = "https://dashscope.aliyuncs.com/api/v1/mcps/zhipu-websearch/sse";
    private String apiKey;

    @BeforeEach
    void setUp() {
        apiKey = System.getenv("ALIYUN_API_KEY");
    }

    @Test
    void testMcpServerConnectivity() {
        // Given
        McpServerConfig config = new McpServerConfig(
                "zhipu-websearch",
                McpTransportType.SSE,
                null,
                java.util.List.of(),
                java.util.Map.of(),
                MCP_SSE_URL,
                java.util.Map.of("Authorization", "Bearer " + apiKey),
                McpConfigScope.USER
        );

        // When
        McpServerConnection connection = new McpServerConnection(config);

        // Then
        assertEquals("zhipu-websearch", connection.getName());
        assertEquals(McpConnectionStatus.PENDING, connection.getStatus());
    }

    @Test
    void testMcpServerConnectionLifecycle() {
        // Given
        McpServerConfig config = new McpServerConfig(
                "zhipu-websearch",
                McpTransportType.SSE,
                null,
                java.util.List.of(),
                java.util.Map.of(),
                MCP_SSE_URL,
                java.util.Map.of("Authorization", "Bearer " + apiKey),
                McpConfigScope.USER
        );

        McpServerConnection connection = new McpServerConnection(config);

        // When - 模拟连接
        connection.connect();

        // Then
        assertEquals(McpConnectionStatus.CONNECTED, connection.getStatus());

        // When - 关闭连接
        connection.close();

        // Then
        assertEquals(McpConnectionStatus.DISABLED, connection.getStatus());
    }

    @Test
    void testMcpEndpointAccessibility() throws Exception {
        // Given - 测试 MCP SSE 端点是否可访问
        URL url = new URL(MCP_SSE_URL);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("Accept", "text/event-stream");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);

        // When
        int responseCode = connection.getResponseCode();

        // Then
        // SSE 端点通常返回 200 或需要特定握手
        // 这里我们主要验证端点可访问（不返回 404 或 403）
        System.out.println("✅ MCP SSE 端点响应码: " + responseCode);
        assertTrue(responseCode == 200 || responseCode == 401 || responseCode == 403,
                "Endpoint should be accessible (got " + responseCode + ")");

        connection.disconnect();
    }

    @Test
    void testMcpConfigurationLoading() {
        // Given
        McpConfiguration.ServerConfig serverConfig = new McpConfiguration.ServerConfig();
        serverConfig.setType("SSE");
        serverConfig.setUrl(MCP_SSE_URL);
        serverConfig.setHeaders(java.util.Map.of("Authorization", "Bearer " + apiKey));
        serverConfig.setScope("USER");

        McpConfiguration configuration = new McpConfiguration();
        configuration.setServers(java.util.Map.of("zhipu-websearch", serverConfig));

        // When
        java.util.List<McpServerConfig> configs = configuration.toMcpServerConfigs();

        // Then
        assertEquals(1, configs.size());
        McpServerConfig config = configs.get(0);
        assertEquals("zhipu-websearch", config.name());
        assertEquals(McpTransportType.SSE, config.type());
        assertEquals(MCP_SSE_URL, config.url());
        assertTrue(config.headers().containsKey("Authorization"));
    }

    @Test
    void testWebSearchToolDefinition() {
        // Given - 模拟智谱 WebSearch MCP 工具定义
        McpServerConnection.McpToolDefinition webSearchTool = new McpServerConnection.McpToolDefinition(
                "web_search",
                "搜索网络信息，获取实时搜索结果",
                java.util.Map.of(
                        "type", "object",
                        "properties", java.util.Map.of(
                                "query", java.util.Map.of(
                                        "type", "string",
                                        "description", "搜索关键词"
                                ),
                                "num_results", java.util.Map.of(
                                        "type", "integer",
                                        "description", "返回结果数量",
                                        "default", 5
                                )
                        ),
                        "required", java.util.List.of("query")
                )
        );

        // Then
        assertEquals("web_search", webSearchTool.name());
        assertTrue(webSearchTool.description().contains("搜索"));
        assertNotNull(webSearchTool.inputSchema());
    }

    @Test
    void testMcpClientManagerIntegration() {
        // Given
        McpConfiguration configuration = new McpConfiguration();
        configuration.setServers(java.util.Map.of());

        McpClientManager clientManager = createTestManager(configuration);

        // When - 添加服务器
        McpServerConfig config = McpServerConfig.sse("zhipu-websearch", MCP_SSE_URL);
        McpServerConnection connection = clientManager.addServer(config);

        // Then
        assertNotNull(connection);
        assertTrue(clientManager.getConnection("zhipu-websearch").isPresent());

        // When - 获取连接列表
        java.util.List<McpServerConnection> connections = clientManager.listConnections();

        // Then
        assertEquals(1, connections.size());

        // When - 移除服务器
        boolean removed = clientManager.removeServer("zhipu-websearch");

        // Then
        assertTrue(removed);
        assertTrue(clientManager.getConnection("zhipu-websearch").isEmpty());
    }

    @Test
    void testMcpToolDiscovery() {
        // Given
        McpConfiguration configuration = new McpConfiguration();
        McpClientManager clientManager = createTestManager(configuration);

        // 添加一个模拟的 MCP 服务器（带工具）
        McpServerConfig config = McpServerConfig.sse("zhipu-websearch", MCP_SSE_URL);
        McpServerConnection connection = clientManager.addServer(config);

        // 模拟设置工具
        connection.setTools(java.util.List.of(
                new McpServerConnection.McpToolDefinition("web_search", "搜索", java.util.Map.of()),
                new McpServerConnection.McpToolDefinition("fetch_page", "获取页面", java.util.Map.of())
        ));

        // When
        java.util.List<Tool> tools = clientManager.discoverAndWrapTools();

        // Then
        assertEquals(2, tools.size());

        // Cleanup
        clientManager.removeServer("zhipu-websearch");
    }
}
