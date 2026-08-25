package com.aicodeassistant.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Paid, read-only OneKey smoke calls. Kept behind a separate environment gate so
 * normal test runs and discovery-only live tests cannot incur usage charges.
 */
@EnabledIfEnvironmentVariable(named = "RUN_ONEKEY_TOOL_CALLS", matches = "true")
class OneKeyMcpToolCallLiveTest {

    private static final String BASE_URL = "https://dashscope.aliyuncs.com/api/v1/mcps/";

    @Test
    void shortlistedToolsReturnUsableContent() {
        String apiKey = requireApiKey();
        Map<String, ToolCallCase> cases = new LinkedHashMap<>();
        cases.put("news", new ToolCallCase(
                "market-cmgjmcp00075019", "news.hotnews",
                Map.of("need_pinned_news", false)));
        cases.put("content-moderation", new ToolCallCase(
                "market-cmgjmcp00075121", "文本内容审核",
                Map.of("address", "ZhikunCode 是一个开源 AI 编程助手。")));
        cases.put("legal-data", new ToolCallCase(
                "market-cmgjmcp00074976", "ali-law-list",
                Map.of(
                        "query", "个人信息保护法对软件开发者处理用户数据的主要义务",
                        "page_no", 1,
                        "page_size", 3,
                        "sort_field", "correlation",
                        "sort_order", "desc")));
        cases.put("company-registry", new ToolCallCase(
                "market-cmgjmcp00074980", "search_company_id",
                Map.of("keyword", "阿里巴巴（中国）有限公司")));

        assertAll(cases.entrySet().stream().map(entry -> () -> {
            ToolCallCase testCase = entry.getValue();
            McpServerConnection connection = connect(entry.getKey(), testCase.serviceId(), apiKey);
            try {
                JsonNode result = connection.callTool(testCase.toolName(), testCase.arguments(), 30_000);
                assertUsableResult(entry.getKey(), result);
                System.out.printf("ONEKEY_CALL name=%s tool=%s result=%s%n",
                        entry.getKey(), testCase.toolName(), compact(result));
            } finally {
                connection.close();
            }
        }));
    }

    private static void assertUsableResult(String name, JsonNode result) {
        assertNotNull(result, name + " should return a JSON-RPC result");
        assertFalse(result.path("isError").asBoolean(false),
                name + " should not return an MCP tool error: " + compact(result));
        JsonNode content = result.path("content");
        assertTrue(content.isArray() && !content.isEmpty(),
                name + " should return a non-empty MCP content array: " + compact(result));
        boolean hasPayload = false;
        for (JsonNode item : content) {
            if ((item.hasNonNull("text") && !item.path("text").asText().isBlank())
                    || item.hasNonNull("data") || item.hasNonNull("resource")) {
                hasPayload = true;
                break;
            }
        }
        assertTrue(hasPayload, name + " should return usable content: " + compact(result));
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
        assertTrue(connection.getStatus() == McpConnectionStatus.CONNECTED,
                name + " should connect before its tool call");
        return connection;
    }

    private static String compact(JsonNode result) {
        String singleLine = result == null ? "null" : result.toString().replaceAll("\\s+", " ");
        return singleLine.substring(0, Math.min(singleLine.length(), 500));
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

    private record ToolCallCase(String serviceId, String toolName, Map<String, Object> arguments) {}
}
