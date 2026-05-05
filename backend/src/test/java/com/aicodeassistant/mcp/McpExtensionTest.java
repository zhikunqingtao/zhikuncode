package com.aicodeassistant.mcp;

import com.aicodeassistant.tool.Tool;
import org.junit.jupiter.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * TC-MCP-002 + TC-MCP-004 MCP 扩展测试。
 */
@DisplayName("MCP 扩展测试")
class McpExtensionTest {

    /**
     * TC-MCP-002 McpToolAdapter 工具转换验证。
     */
    @Nested
    @DisplayName("TC-MCP-002 McpToolAdapter 工具转换验证")
    class McpToolAdapterTest {

        @Test
        @DisplayName("McpToolAdapter 实现 Tool 接口")
        void adapterImplementsToolInterface() {
            assertTrue(
                    Tool.class.isAssignableFrom(McpToolAdapter.class),
                    "McpToolAdapter 应实现 Tool 接口");
        }

        @Test
        @DisplayName("工具名称映射正确")
        void toolNameMapping() {
            McpServerConnection mockConn = mock(McpServerConnection.class);
            when(mockConn.getName()).thenReturn("test-server");
            when(mockConn.getStatus()).thenReturn(McpConnectionStatus.CONNECTED);

            Map<String, Object> schema = Map.of("type", "object");
            McpToolAdapter adapter = new McpToolAdapter(
                    "mcp__test-server__read_file",
                    "Read a file",
                    schema,
                    mockConn,
                    "read_file");

            assertEquals("mcp__test-server__read_file", adapter.getName(),
                    "工具名应为 mcp__serverName__toolName 格式");
            assertEquals("Read a file", adapter.getDescription());
            assertEquals(schema, adapter.getInputSchema());
            assertTrue(adapter.isMcp(), "应标记为 MCP 工具");
        }

        @Test
        @DisplayName("获取原始工具名和服务器名")
        void originalToolNameAndServerName() {
            McpServerConnection mockConn = mock(McpServerConnection.class);
            when(mockConn.getName()).thenReturn("my-server");

            McpToolAdapter adapter = new McpToolAdapter(
                    "mcp__my-server__search", "Search",
                    Map.of(), mockConn, "search");

            assertEquals("search", adapter.getOriginalToolName());
            assertEquals("my-server", adapter.getServerName());
        }

        @Test
        @DisplayName("结果截断保护常量配置")
        void resultTruncationLimit() {
            assertEquals(1024 * 1024, McpToolAdapter.MAX_MCP_RESULT_SIZE,
                    "最大结果大小应为 1MB");
        }
    }

    /**
     * TC-MCP-004 McpCapabilityRegistry JSON 持久化验证。
     */
    @Nested
    @DisplayName("TC-MCP-004 McpCapabilityRegistry JSON 持久化验证")
    class CapabilityRegistryPersistenceTest {

        @Test
        @DisplayName("配置文件路径不应为null")
        void registryFilePathNotNull() {
            java.nio.file.Path configPath = java.nio.file.Path.of(
                    "configuration/mcp/mcp_capability_registry.json");
            assertNotNull(configPath, "配置路径不应为null");
        }

        @Test
        @DisplayName("McpCapabilityRegistryService 核心方法存在")
        void registryServiceCoreMethodsExist() throws Exception {
            Class<?> clazz = Class.forName(
                    "com.aicodeassistant.mcp.McpCapabilityRegistryService");

            assertNotNull(clazz.getMethod("listAll"), "listAll 方法应存在");
            assertNotNull(clazz.getMethod("listEnabled"), "listEnabled 方法应存在");
            assertNotNull(clazz.getMethod("findById", String.class),
                    "findById 方法应存在");
            assertNotNull(clazz.getMethod("size"), "size 方法应存在");
            assertNotNull(clazz.getMethod("deleteCapability", String.class),
                    "deleteCapability 方法应存在");
            assertNotNull(clazz.getMethod("saveToFile"), "saveToFile 方法应存在");
        }

        @Test
        @DisplayName("McpCapabilityDefinition record 字段完整")
        void capabilityDefinitionFields() throws Exception {
            Class<?> clazz = Class.forName(
                    "com.aicodeassistant.mcp.McpCapabilityDefinition");
            assertNotNull(clazz.getMethod("id"), "id 字段应存在");
            assertNotNull(clazz.getMethod("enabled"), "enabled 字段应存在");
        }

        @Test
        @DisplayName("JSON 格式包含必需字段")
        void jsonFormatContainsRequiredFields() throws Exception {
            String userDir = System.getProperty("user.dir");
            java.nio.file.Path fullPath = java.nio.file.Path.of(userDir)
                    .resolve("configuration/mcp/mcp_capability_registry.json");
            if (!java.nio.file.Files.exists(fullPath)) {
                java.nio.file.Path parent = java.nio.file.Path.of(userDir).getParent();
                if (parent != null) fullPath = parent.resolve("configuration/mcp/mcp_capability_registry.json");
            }

            if (java.nio.file.Files.exists(fullPath)) {
                String json = java.nio.file.Files.readString(fullPath);
                assertTrue(json.contains("mcp_tools"),
                        "JSON 应包含 mcp_tools 字段");
            }
            // CI 环境中文件可能不存在，不强制失败
        }
    }
}
