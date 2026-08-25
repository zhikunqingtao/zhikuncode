package com.aicodeassistant.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "RUN_SELECTED_MCP_LIVE_TESTS", matches = "true")
class SelectedMcpLiveSmokeTest {

    @Test
    void enabledSelectedServersAuthenticateAndExposeExactlyTheirAllowlistedTools() {
        String apiKey = requireApiKey();
        McpCapabilityRegistryService registry = loadRegistry();
        MockEnvironment environment = new MockEnvironment()
                .withProperty("llm.providers.dashscope.api-key", apiKey);
        McpClientManager manager = new McpClientManager(
                new McpConfiguration(), null, null, null, registry, environment,
                null, null, null, null, null, null);

        for (String serverKey : List.of("amap-maps", "QwenImage", "WanVideo")) {
            List<McpCapabilityDefinition> definitions = registry.listEnabled().stream()
                    .filter(def -> serverKey.equals(def.extractServerKey()))
                    .toList();
            McpServerConnection connection = new McpServerConnection(
                    manager.buildConfigFromRegistry(definitions.getFirst()));
            try {
                connection.connect();
                assertEquals(McpConnectionStatus.CONNECTED, connection.getStatus(), serverKey);
                Set<String> expected = definitions.stream()
                        .map(McpCapabilityDefinition::toolName)
                        .collect(Collectors.toSet());
                Set<String> actual = connection.getTools().stream()
                        .map(McpServerConnection.McpToolDefinition::name)
                        .collect(Collectors.toSet());
                assertTrue(actual.containsAll(expected),
                        serverKey + " remote tools must contain every allowlisted tool");
                if ("amap-maps".equals(serverKey)) {
                    assertEquals(15, actual.size(), "AMap currently exposes two non-selected extras");
                    assertTrue(actual.contains("maps_schema_take_taxi"));
                    assertTrue(actual.contains("maps_schema_personal_map"));
                } else {
                    assertEquals(expected, actual,
                            serverKey + " remote tools must match the registry allowlist");
                }
                System.out.printf("SELECTED_MCP_DISCOVERY server=%s tools=%d schemas=%s%n",
                        serverKey, actual.size(), connection.getTools());
            } finally {
                connection.close();
            }
        }
    }

    private static McpCapabilityRegistryService loadRegistry() {
        McpConfiguration configuration = new McpConfiguration();
        configuration.setCapabilityRegistryPath(Path.of(System.getProperty("user.dir"))
                .resolve("../configuration/mcp/mcp_capability_registry.json")
                .normalize().toString());
        McpCapabilityRegistryService registry =
                new McpCapabilityRegistryService(new ObjectMapper(), configuration);
        registry.loadRegistry();
        return registry;
    }

    private static String requireApiKey() {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("LLM_PROVIDER_DASHSCOPE_API_KEY");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "LLM_PROVIDER_DASHSCOPE_API_KEY or DASHSCOPE_API_KEY is required for selected MCP live tests");
        }
        return apiKey;
    }
}
