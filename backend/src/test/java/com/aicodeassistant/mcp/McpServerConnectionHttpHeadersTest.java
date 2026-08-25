package com.aicodeassistant.mcp;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpServerConnectionHttpHeadersTest {

    private MockWebServer server;

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.shutdown();
        }
    }

    @Test
    void httpTransportPropagatesConfiguredHeadersAcrossHandshake() throws Exception {
        server = new MockWebServer();
        server.start();

        server.enqueue(jsonRpcResult(1, "{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"serverInfo\":{\"name\":\"test\",\"version\":\"1\"}}"));
        server.enqueue(new MockResponse().setResponseCode(202));
        server.enqueue(jsonRpcResult(2, "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"serverInfo\":{\"name\":\"test\",\"version\":\"1\"}}"));
        server.enqueue(new MockResponse().setResponseCode(202));
        server.enqueue(jsonRpcResult(3, "{\"tools\":[{\"name\":\"ping\",\"description\":\"Ping\",\"inputSchema\":{\"type\":\"object\"}}]}"));

        McpServerConfig config = new McpServerConfig(
                "one-key-test",
                McpTransportType.HTTP,
                null,
                List.of(),
                Map.of(),
                server.url("/mcp").toString(),
                Map.of("Authorization", "Bearer test-dashscope-key", "X-Test-Header", "present"),
                McpConfigScope.USER
        );

        McpServerConnection connection = new McpServerConnection(config);
        connection.connect();

        assertEquals(McpConnectionStatus.CONNECTED, connection.getStatus());
        assertEquals(List.of("ping"), connection.getTools().stream()
                .map(McpServerConnection.McpToolDefinition::name)
                .toList());

        for (int i = 0; i < 5; i++) {
            RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(request, "expected MCP handshake request " + (i + 1));
            assertEquals("Bearer test-dashscope-key", request.getHeader("Authorization"));
            assertEquals("present", request.getHeader("X-Test-Header"));
        }

        connection.close();
    }

    @Test
    void httpToolCallUsesRequestedTimeoutInsteadOfAFixedReadTimeout() throws Exception {
        server = new MockWebServer();
        server.start();
        server.enqueue(jsonRpcResult(1, "{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},\"serverInfo\":{\"name\":\"test\",\"version\":\"1\"}}"));
        server.enqueue(new MockResponse().setResponseCode(202));
        server.enqueue(jsonRpcResult(2, "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"serverInfo\":{\"name\":\"test\",\"version\":\"1\"}}"));
        server.enqueue(new MockResponse().setResponseCode(202));
        server.enqueue(jsonRpcResult(3, "{\"tools\":[{\"name\":\"slow\",\"description\":\"Slow\",\"inputSchema\":{\"type\":\"object\"}}]}"));
        server.enqueue(jsonRpcResult(4, "{\"content\":[{\"type\":\"text\",\"text\":\"late\"}],\"isError\":false}")
                .setBodyDelay(500, TimeUnit.MILLISECONDS));

        McpServerConnection connection = new McpServerConnection(new McpServerConfig(
                "timeout-test", McpTransportType.HTTP, null, List.of(), Map.of(),
                server.url("/mcp").toString(), Map.of(), McpConfigScope.USER));
        connection.connect();

        long started = System.nanoTime();
        assertThrows(McpProtocolException.class,
                () -> connection.callTool("slow", Map.of(), 50));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        assertTrue(elapsedMs < 400, "requested 50ms timeout should stop the call promptly");
        connection.close();
    }

    private static MockResponse jsonRpcResult(int id, String resultJson) {
        return new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + resultJson + "}");
    }
}
