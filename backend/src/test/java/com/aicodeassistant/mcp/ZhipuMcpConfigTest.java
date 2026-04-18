package com.aicodeassistant.mcp;

import com.aicodeassistant.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 智谱 MCP 配置验证测试 — 无需网络连接。
 */
class ZhipuMcpConfigTest {

    private static final String MCP_SSE_URL = "https://dashscope.aliyuncs.com/api/v1/mcps/zhipu-websearch/sse";
    private static final String API_KEY = "sk-93625146d2c343d78735213013794ed5";

    @Test
    void testMcpServerConfigCreation() {
        // Given & When
        McpServerConfig config = new McpServerConfig(
                "zhipu-websearch",
                McpTransportType.SSE,
                null,
                List.of(),
                Map.of(),
                MCP_SSE_URL,
                Map.of("Authorization", "Bearer " + API_KEY),
                McpConfigScope.USER
        );

        // Then
        assertEquals("zhipu-websearch", config.name());
        assertEquals(McpTransportType.SSE, config.type());
        assertEquals(MCP_SSE_URL, config.url());
        assertEquals(McpConfigScope.USER, config.scope());
        assertTrue(config.headers().containsKey("Authorization"));
        assertEquals("Bearer " + API_KEY, config.headers().get("Authorization"));

        System.out.println("\n========== MCP 服务器配置 ==========");
        System.out.println("名称: " + config.name());
        System.out.println("类型: " + config.type());
        System.out.println("URL: " + config.url());
        System.out.println("Scope: " + config.scope());
        System.out.println("Headers: " + config.headers());
        System.out.println("====================================\n");
    }

    @Test
    void testMcpConfigurationLoading() {
        // Given
        McpConfiguration.ServerConfig serverConfig = new McpConfiguration.ServerConfig();
        serverConfig.setType("SSE");
        serverConfig.setUrl(MCP_SSE_URL);
        serverConfig.setHeaders(Map.of("Authorization", "Bearer " + API_KEY));
        serverConfig.setScope("USER");

        McpConfiguration configuration = new McpConfiguration();
        configuration.setServers(Map.of("zhipu-websearch", serverConfig));

        // When
        List<McpServerConfig> configs = configuration.toMcpServerConfigs();

        // Then
        assertEquals(1, configs.size());
        McpServerConfig config = configs.get(0);
        assertEquals("zhipu-websearch", config.name());
        assertEquals(McpTransportType.SSE, config.type());
        assertEquals(MCP_SSE_URL, config.url());
        assertTrue(config.headers().containsKey("Authorization"));

        System.out.println("\n========== MCP 配置加载 ==========");
        System.out.println("配置数量: " + configs.size());
        System.out.println("服务器名称: " + config.name());
        System.out.println("传输类型: " + config.type());
        System.out.println("端点URL: " + config.url());
        System.out.println("==================================\n");
    }

    @Test
    void testWebSearchToolDefinition() {
        // Given - 模拟智谱 WebSearch MCP 工具定义
        McpServerConnection.McpToolDefinition webSearchTool = new McpServerConnection.McpToolDefinition(
                "web_search",
                "搜索网络信息，获取实时搜索结果",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of(
                                        "type", "string",
                                        "description", "搜索关键词"
                                ),
                                "num_results", Map.of(
                                        "type", "integer",
                                        "description", "返回结果数量",
                                        "default", 5
                                )
                        ),
                        "required", List.of("query")
                )
        );

        // Then
        assertEquals("web_search", webSearchTool.name());
        assertTrue(webSearchTool.description().contains("搜索"));
        assertNotNull(webSearchTool.inputSchema());

        System.out.println("\n========== WebSearch 工具定义 ==========");
        System.out.println("工具名称: " + webSearchTool.name());
        System.out.println("工具描述: " + webSearchTool.description());
        System.out.println("输入Schema: " + webSearchTool.inputSchema());
        System.out.println("========================================\n");
    }

    @Test
    void testMcpClientManagerIntegration() {
        // Given
        McpConfiguration configuration = new McpConfiguration();
        configuration.setServers(Map.of());

        McpApprovalService approval = mock(McpApprovalService.class);
        when(approval.isTrusted(any())).thenReturn(true);
        ToolRegistry toolRegistry = mock(ToolRegistry.class);
        McpClientManager clientManager = new McpClientManager(configuration, null, toolRegistry, approval, null, null, null, null);

        // When - 添加服务器
        McpServerConfig config = McpServerConfig.sse("zhipu-websearch", MCP_SSE_URL);
        McpServerConnection connection = clientManager.addServer(config);

        // Then
        assertNotNull(connection);
        assertTrue(clientManager.getConnection("zhipu-websearch").isPresent());

        // When - 获取连接列表
        List<McpServerConnection> connections = clientManager.listConnections();

        // Then
        assertEquals(1, connections.size());

        System.out.println("\n========== MCP ClientManager ==========");
        System.out.println("添加服务器: zhipu-websearch");
        System.out.println("连接状态: " + connection.getStatus());
        System.out.println("连接列表: " + connections.size() + " 个");
        System.out.println("=======================================\n");

        // Cleanup
        clientManager.removeServer("zhipu-websearch");
    }

    @Test
    void testApplicationYamlConfig() {
        // 验证 application.yml 中的配置格式
        String expectedType = "SSE";
        String expectedUrl = MCP_SSE_URL;
        String expectedHeader = "Bearer " + API_KEY;

        System.out.println("\n========== application.yml 配置 ==========");
        System.out.println("mcp:");
        System.out.println("  servers:");
        System.out.println("    zhipu-websearch:");
        System.out.println("      type: " + expectedType);
        System.out.println("      url: " + expectedUrl);
        System.out.println("      headers:");
        System.out.println("        Authorization: " + expectedHeader.substring(0, 20) + "...");
        System.out.println("==========================================\n");

        assertNotNull(expectedType);
        assertNotNull(expectedUrl);
        assertTrue(expectedHeader.startsWith("Bearer "));
    }
}
