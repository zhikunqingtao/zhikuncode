package com.aicodeassistant.llm;

import com.aicodeassistant.llm.impl.OpenAiCompatibleProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * chatSync 单元测试 — 使用 OkHttp MockWebServer，无需 Spring 上下文。
 * <p>
 * 验证：
 * 1. 请求体包含 stream:false 且不含 stream_options
 * 2. stop 数组正确序列化
 * 3. 429 重试逻辑
 * 4. 正常响应解析
 */
class ChatSyncTest {

    private MockWebServer server;
    private OpenAiCompatibleProvider provider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final LlmHttpProperties DEFAULT_HTTP_PROPS = new LlmHttpProperties(
            new LlmHttpProperties.PoolProperties(5, 30), 10, 10, true);

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();

        String baseUrl = server.url("/v1").toString();
        // 去掉末尾 /，因为 Provider 会拼接 /chat/completions
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }

        provider = new OpenAiCompatibleProvider(
                objectMapper,
                DEFAULT_HTTP_PROPS,
                new ApiKeyRotationManager(List.of(), "sk-test-key"),
                "sk-test-key",
                baseUrl,
                "qwen-plus",
                List.of("qwen-plus", "qwen-max")
        );
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void testChatSyncRequestFormat() throws Exception {
        // 模拟成功响应
        String responseBody = """
                {"choices":[{"message":{"content":"<block>no</block>"},"finish_reason":"stop"}]}
                """;
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(responseBody)
                .setHeader("Content-Type", "application/json"));

        // 执行 chatSync
        String result = provider.chatSync(
                "qwen-plus", "You are a classifier.", "Test input",
                64, new String[]{"</block>"}, 3000);

        // 验证响应
        assertEquals("<block>no</block>", result);

        // 验证请求体
        RecordedRequest request = server.takeRequest();
        assertEquals("POST", request.getMethod());
        assertTrue(request.getPath().endsWith("/chat/completions"));

        JsonNode body = objectMapper.readTree(request.getBody().readUtf8());

        // stream 必须为 false
        assertFalse(body.get("stream").asBoolean(), "stream should be false");
        // 不应包含 stream_options
        assertFalse(body.has("stream_options"), "should not have stream_options");
        // model 正确
        assertEquals("qwen-plus", body.get("model").asText());
        // max_tokens 正确
        assertEquals(64, body.get("max_tokens").asInt());
        // stop 数组正确
        assertTrue(body.has("stop"), "should have stop array");
        JsonNode stopArray = body.get("stop");
        assertTrue(stopArray.isArray());
        assertEquals(1, stopArray.size());
        assertEquals("</block>", stopArray.get(0).asText());
        // messages 正确（system + user）
        JsonNode messages = body.get("messages");
        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).get("role").asText());
        assertEquals("user", messages.get(1).get("role").asText());
    }

    @Test
    void testChatSyncWithoutStopSequences() throws Exception {
        String responseBody = """
                {"choices":[{"message":{"content":"Hello world"},"finish_reason":"stop"}]}
                """;
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody(responseBody)
                .setHeader("Content-Type", "application/json"));

        String result = provider.chatSync(
                "qwen-plus", "System prompt", "User input",
                1024, null, 5000);

        assertEquals("Hello world", result);

        RecordedRequest request = server.takeRequest();
        JsonNode body = objectMapper.readTree(request.getBody().readUtf8());
        assertFalse(body.has("stop"), "should not have stop when stopSequences is null");
    }

    @Test
    void testChatSync429RetrySuccess() throws Exception {
        // 第一次返回 429
        server.enqueue(new MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "1")
                .setBody("{\"error\":{\"message\":\"Rate limited\"}}"));

        // 第二次成功
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"choices\":[{\"message\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}")
                .setHeader("Content-Type", "application/json"));

        String result = provider.chatSync(
                "qwen-plus", "System", "User",
                64, null, 10000);

        assertEquals("OK", result);
        // 应该有 2 次请求
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void testChatSyncHttpError() {
        server.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("{\"error\":{\"message\":\"Internal error\"}}"));

        LlmApiException ex = assertThrows(LlmApiException.class, () ->
                provider.chatSync("qwen-plus", "System", "User", 64, null, 3000));

        assertEquals(500, ex.getHttpStatus());
        assertTrue(ex.isRetryable());
    }
}
