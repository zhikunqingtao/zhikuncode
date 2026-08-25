package com.aicodeassistant.mcp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@EnabledIfEnvironmentVariable(named = "RUN_ONEKEY_LIVE_TESTS", matches = "true")
class OneKeyMcpLiveSmokeTest {

    private static final String BASE_URL = "https://dashscope.aliyuncs.com/api/v1/mcps/";

    @Test
    void shortlistedServersAuthenticateAndExposeTools() {
        String apiKey = requireApiKey();
        Map<String, String> servers = new LinkedHashMap<>();
        servers.put("news", "market-cmgjmcp00075019");
        servers.put("content-moderation", "market-cmgjmcp00075121");
        servers.put("legal-data", "market-cmgjmcp00074976");
        servers.put("company-registry", "market-cmgjmcp00074980");

        String requestedService = System.getenv("ONEKEY_SERVICE");
        if (requestedService != null && !requestedService.isBlank()) {
            String serviceId = servers.get(requestedService);
            if (serviceId == null) {
                throw new IllegalArgumentException("Unknown ONEKEY_SERVICE: " + requestedService);
            }
            servers = new LinkedHashMap<>(Map.of(requestedService, serviceId));
        }

        assertAll(servers.entrySet().stream().map(entry -> () -> {
            McpServerConnection connection = connect(entry.getKey(), entry.getValue(), apiKey);
            try {
                System.out.printf("ONEKEY_DISCOVERY name=%s status=%s tools=%s%n",
                        entry.getKey(), connection.getStatus(), connection.getTools());
                assertEquals(McpConnectionStatus.CONNECTED, connection.getStatus(),
                        entry.getKey() + " should complete the MCP handshake");
                assertFalse(connection.getTools().isEmpty(),
                        entry.getKey() + " should expose at least one tool");
            } finally {
                connection.close();
            }
        }));
    }

    private static McpServerConnection connect(String name, String serviceId, String apiKey) {
        McpServerConfig config = new McpServerConfig(
                name,
                McpTransportType.HTTP,
                null,
                List.of(),
                Map.of(),
                BASE_URL + serviceId + "/mcp",
                Map.of("Authorization", "Bearer " + apiKey),
                McpConfigScope.USER
        );
        McpServerConnection connection = new McpServerConnection(config);
        connection.connect();
        return connection;
    }

    private static String requireApiKey() {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("LLM_PROVIDER_DASHSCOPE_API_KEY");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("DASHSCOPE_API_KEY is required for live OneKey tests");
        }
        return apiKey;
    }
}
