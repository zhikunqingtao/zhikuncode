package com.aicodeassistant.mcp;

import com.aicodeassistant.mcp.roots.McpRootsProvider;
import com.aicodeassistant.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.Environment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OneKeyRegistryIntegrationTest {

    private static final Set<String> EXPECTED_TOOLS = Set.of(
            "news.channel_query", "news.hotnews", "news.query",
            "ali-case-list", "ali-law-list",
            "search_company_id", "get_company_overview", "get_company_shareholders",
            "get_company_staff", "get_company_participating", "get_company_branches",
            "get_company_contact",
            "文本内容审核"
    );

    @Test
    void registryLoadsAllSelectedOneKeyToolsAsAuthenticatedHttpServers() {
        Path registryPath = Path.of(System.getProperty("user.dir"))
                .resolve("../configuration/mcp/mcp_capability_registry.json")
                .normalize();
        assertTrue(Files.isRegularFile(registryPath), "OneKey registry should exist");

        McpConfiguration configuration = new McpConfiguration();
        configuration.setCapabilityRegistryPath(registryPath.toString());
        McpCapabilityRegistryService registry =
                new McpCapabilityRegistryService(new ObjectMapper(), configuration);
        registry.loadRegistry();

        var selected = registry.listAll().stream()
                .filter(def -> def.id().startsWith("mcp_onekey_"))
                .toList();
        assertEquals(13, selected.size());
        assertEquals(EXPECTED_TOOLS,
                selected.stream().map(McpCapabilityDefinition::toolName).collect(java.util.stream.Collectors.toSet()));
        assertTrue(selected.stream().allMatch(McpCapabilityDefinition::enabled));
        assertTrue(selected.stream().allMatch(def -> def.resolvedTransportType() == McpTransportType.HTTP));
        assertTrue(selected.stream().allMatch(def ->
                "llm.providers.dashscope.api-key".equals(def.apiKeyConfig())));
        assertTrue(selected.stream().map(def -> McpClientManager.buildExternalToolName(
                        def.extractServerKey(), def.toolName(), def))
                .allMatch(name -> name.length() <= 64 && name.matches("[A-Za-z0-9_-]+")));

        McpCapabilityDefinition hotNews = registry.findById("mcp_onekey_news_hot").orElseThrow();
        assertEquals("mcp__market-cmgjmcp00075019__onekey_news_hot",
                McpClientManager.buildExternalToolName(
                        hotNews.extractServerKey(), hotNews.toolName(), hotNews));

        Environment environment = mock(Environment.class);
        when(environment.getProperty("llm.providers.dashscope.api-key"))
                .thenReturn("test-dashscope-key");
        McpClientManager manager = new McpClientManager(
                configuration, null, null, null, registry, environment,
                null, null, null, null, null, null);

        for (McpCapabilityDefinition definition : selected) {
            McpServerConfig server = manager.buildConfigFromRegistry(definition);
            assertEquals(McpTransportType.HTTP, server.type());
            assertTrue(server.url().startsWith("https://dashscope.aliyuncs.com/api/v1/mcps/market-"));
            assertTrue(server.url().endsWith("/mcp"));
            assertEquals(Map.of("Authorization", "Bearer test-dashscope-key"), server.headers());
            assertEquals(definition.extractServerKey(), server.name());
        }
    }

    @Test
    void dockerDashscopeKeyFlowsThroughApplicationYamlIntoAllDashscopeMcpAuthorization() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                "dockerEnvironment",
                Map.of("LLM_PROVIDER_DASHSCOPE_API_KEY", "docker-dashscope-key")));
        YamlPropertySourceLoader yamlLoader = new YamlPropertySourceLoader();
        yamlLoader.load("applicationYaml", new ClassPathResource("application.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));

        assertEquals("docker-dashscope-key",
                environment.getProperty("llm.providers.dashscope.api-key"));

        Path registryPath = Path.of(System.getProperty("user.dir"))
                .resolve("../configuration/mcp/mcp_capability_registry.json")
                .normalize();
        McpConfiguration configuration = new McpConfiguration();
        configuration.setCapabilityRegistryPath(registryPath.toString());
        McpCapabilityRegistryService registry =
                new McpCapabilityRegistryService(new ObjectMapper(), configuration);
        registry.loadRegistry();

        McpCapabilityDefinition oneKeyDefinition =
                registry.findById("mcp_onekey_news_hot").orElseThrow();
        McpClientManager manager = new McpClientManager(
                configuration, null, null, null, registry, environment,
                null, null, null, null, null, null);

        McpServerConfig oneKeyServer = manager.buildConfigFromRegistry(oneKeyDefinition);
        assertEquals(Map.of("Authorization", "Bearer docker-dashscope-key"), oneKeyServer.headers());

        McpCapabilityDefinition zhipuDefinition =
                registry.findById("mcp_web_search_pro").orElseThrow();
        assertEquals("llm.providers.dashscope.api-key", zhipuDefinition.apiKeyConfig());
        assertEquals("https://dashscope.aliyuncs.com/api/v1/mcps/zhipu-websearch/sse",
                zhipuDefinition.url());
        McpServerConfig zhipuServer = manager.buildConfigFromRegistry(zhipuDefinition);
        assertEquals(McpTransportType.SSE, zhipuServer.type());
        assertEquals(Map.of("Authorization", "Bearer docker-dashscope-key"), zhipuServer.headers());
    }

    @Test
    void genericLlmApiKeyDoesNotAuthenticateZhipuDashscopeMcp() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                "genericLlmEnvironment",
                Map.of("LLM_API_KEY", "generic-llm-key")));
        YamlPropertySourceLoader yamlLoader = new YamlPropertySourceLoader();
        yamlLoader.load("applicationYaml", new ClassPathResource("application.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));

        assertEquals("", environment.getProperty("llm.providers.dashscope.api-key"));

        Path registryPath = Path.of(System.getProperty("user.dir"))
                .resolve("../configuration/mcp/mcp_capability_registry.json")
                .normalize();
        McpConfiguration configuration = new McpConfiguration();
        configuration.setCapabilityRegistryPath(registryPath.toString());
        McpCapabilityRegistryService registry =
                new McpCapabilityRegistryService(new ObjectMapper(), configuration);
        registry.loadRegistry();

        McpCapabilityDefinition zhipuDefinition =
                registry.findById("mcp_web_search_pro").orElseThrow();
        McpClientManager manager = new McpClientManager(
                configuration, null, null, null, registry, environment,
                null, null, null, null, null, null);

        assertTrue(manager.buildConfigFromRegistry(zhipuDefinition).headers().isEmpty());
    }

    @Test
    void missingDashscopeKeyCreatesNoConnectionsAndSchedulesNoReconnects() {
        Path registryPath = Path.of(System.getProperty("user.dir"))
                .resolve("../configuration/mcp/mcp_capability_registry.json")
                .normalize();
        McpConfiguration configuration = new McpConfiguration();
        configuration.setCapabilityRegistryPath(registryPath.toString());
        McpCapabilityRegistryService registry =
                new McpCapabilityRegistryService(new ObjectMapper(), configuration);
        registry.loadRegistry();
        assertEquals(83, registry.enabledCount());

        McpConfigurationResolver resolver = mock(McpConfigurationResolver.class);
        when(resolver.resolveAll()).thenReturn(List.of());
        McpApprovalService approval = mock(McpApprovalService.class);
        ToolRegistry tools = mock(ToolRegistry.class);
        McpClientManager manager = new McpClientManager(
                configuration, resolver, tools, approval, registry, new MockEnvironment(),
                null, null, null, mock(McpRootsProvider.class), null, null);

        try {
            manager.start();
            assertTrue(manager.listConnections().isEmpty());

            manager.healthCheck();
            manager.reconnectFailed();
            assertTrue(manager.listConnections().isEmpty());
            verifyNoInteractions(approval, tools);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void manualEnableWithoutDashscopeKeyRemainsInactiveAndDoesNotInstallConnection() {
        Path registryPath = Path.of(System.getProperty("user.dir"))
                .resolve("../configuration/mcp/mcp_capability_registry.json")
                .normalize();
        McpConfiguration configuration = new McpConfiguration();
        configuration.setCapabilityRegistryPath(registryPath.toString());
        McpCapabilityRegistryService registry =
                new McpCapabilityRegistryService(new ObjectMapper(), configuration);
        registry.loadRegistry();

        McpApprovalService approval = mock(McpApprovalService.class);
        ToolRegistry tools = mock(ToolRegistry.class);
        McpClientManager manager = new McpClientManager(
                configuration, null, tools, approval, registry, new MockEnvironment(),
                null, null, null, null, null, null);
        try {
            McpServerConnection connection = manager.enableFromRegistry(
                    registry.findById("mcp_onekey_news_hot").orElseThrow());

            assertEquals(McpConnectionStatus.NEEDS_AUTH, connection.getStatus());
            assertTrue(manager.listConnections().isEmpty());
            verifyNoInteractions(approval, tools);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void legacySseUrlEntriesRemainBackwardCompatible() throws Exception {
        String json = """
                {
                  "id": "legacy",
                  "name": "Legacy",
                  "toolName": "legacy-tool",
                  "sseUrl": "https://example.com/server/sse",
                  "enabled": true
                }
                """;

        McpCapabilityDefinition definition =
                new ObjectMapper().readValue(json, McpCapabilityDefinition.class);

        assertEquals("https://example.com/server/sse", definition.url());
        assertEquals(McpTransportType.SSE, definition.resolvedTransportType());
        assertEquals("server", definition.extractServerKey());
    }

    @Test
    void unresolvedApiKeyPlaceholderIsNotSentAsAuthorization() throws Exception {
        McpCapabilityDefinition definition = new ObjectMapper().readValue("""
                {
                  "id": "missing-key",
                  "name": "Missing key",
                  "toolName": "tool",
                  "url": "https://example.com/server/mcp",
                  "transportType": "HTTP",
                  "apiKeyConfig": "missing.api-key",
                  "apiKeyDefault": "${MISSING_API_KEY}",
                  "enabled": true
                }
                """, McpCapabilityDefinition.class);
        Environment environment = mock(Environment.class);
        when(environment.resolvePlaceholders("${MISSING_API_KEY}"))
                .thenReturn("${MISSING_API_KEY}");
        McpClientManager manager = new McpClientManager(
                new McpConfiguration(), null, null, null, null, environment,
                null, null, null, null, null, null);

        assertTrue(manager.buildConfigFromRegistry(definition).headers().isEmpty());
    }
}
