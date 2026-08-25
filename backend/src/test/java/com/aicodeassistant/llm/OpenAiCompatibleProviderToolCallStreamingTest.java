package com.aicodeassistant.llm;

import com.aicodeassistant.llm.impl.OpenAiCompatibleProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiCompatibleProviderToolCallStreamingTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private MockWebServer server;
    private OpenAiCompatibleProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        String baseUrl = server.url("/v1").toString().replaceAll("/$", "");
        LlmHttpProperties http = new LlmHttpProperties(
                new LlmHttpProperties.PoolProperties(2, 30), 10, 10, true);
        provider = new OpenAiCompatibleProvider(
                "test", mapper, http, new ApiKeyRotationManager("key"),
                "key", baseUrl, "qwen3.8-max",
                List.of("qwen3.8-max", "kimi-k3"));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void acceptsQwenEmptyIdentityContinuation() {
        assertAccepted("qwen3.8-max", "", "", false);
    }

    @Test
    void preservesKimiSparseContinuation() {
        assertAccepted("kimi-k3", null, null, false);
    }

    @Test
    void usageOnlyTailDoesNotReplaceToolUseStopReason() {
        assertAccepted("qwen3.8-max", "", "", true);
    }

    @Test
    void repeatedFinishChunkDoesNotEmitDuplicateBlockStop() {
        Capture capture = run(
                toolChunk("call-1", "Brief", "{}"),
                finishChunk(), finishChunk());

        assertNull(capture.error);
        assertEquals(1, capture.events.stream()
                .filter(LlmStreamEvent.BlockStop.class::isInstance)
                .count());
    }

    @Test
    void rejectsEmptyIdentityBeforeItIsEstablished() {
        Capture capture = run(toolChunk("", "", "{}"), finishChunk());

        assertFalse(capture.completed);
        assertTrue(capture.events.isEmpty());
        LlmApiException error = assertInstanceOf(LlmApiException.class, capture.error);
        assertFalse(error.isRetryable());
        assertTrue(error.getMessage().startsWith("INVALID_TOOL_CALL_STREAM:"));
    }

    private void assertAccepted(
            String model,
            String continuationId, String continuationName,
            boolean includeUsageTail) {
        List<String> chunks = new ArrayList<>(List.of(
                toolChunk("call-1", "Brief", "{\"query\":\""),
                toolChunk(continuationId, continuationName, "hello\"}"),
                finishChunk()));
        if (includeUsageTail) chunks.add(usageChunk());
        Capture capture = runForModel(model, chunks.toArray(String[]::new));

        assertNull(capture.error);
        assertTrue(capture.completed);
        assertEquals(includeUsageTail ? 6 : 5, capture.events.size());
        LlmStreamEvent.ToolUseStart start = assertInstanceOf(
                LlmStreamEvent.ToolUseStart.class, capture.events.get(0));
        LlmStreamEvent.ToolInputDelta first = assertInstanceOf(
                LlmStreamEvent.ToolInputDelta.class, capture.events.get(1));
        LlmStreamEvent.ToolInputDelta second = assertInstanceOf(
                LlmStreamEvent.ToolInputDelta.class, capture.events.get(2));
        assertInstanceOf(LlmStreamEvent.BlockStop.class, capture.events.get(3));
        LlmStreamEvent.MessageDelta end = assertInstanceOf(
                LlmStreamEvent.MessageDelta.class, capture.events.get(4));

        assertEquals("call-1", start.id());
        assertEquals("Brief", start.name());
        assertEquals("call-1", first.toolUseId());
        assertEquals("call-1", second.toolUseId());
        assertEquals("{\"query\":\"hello\"}", first.jsonDelta() + second.jsonDelta());
        assertEquals("tool_use", end.stopReason());
        if (includeUsageTail) {
            LlmStreamEvent.MessageDelta usage = assertInstanceOf(
                    LlmStreamEvent.MessageDelta.class, capture.events.get(5));
            assertNull(usage.stopReason());
            assertEquals(7, usage.usage().inputTokens());
        }
    }

    private Capture run(String... chunks) {
        return runForModel("qwen3.8-max", chunks);
    }

    private Capture runForModel(String model, String... chunks) {
        StringBuilder body = new StringBuilder();
        for (String chunk : chunks) {
            body.append("data: ").append(chunk).append("\n\n");
        }
        body.append("data: [DONE]\n\n");
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body.toString()));

        Capture capture = new Capture();
        provider.streamChat(
                model,
                List.of(Map.of("role", "user", "content", "test")),
                "system", List.of(), 1024, new ThinkingConfig.Disabled(),
                LlmCallContext.unscoped(), capture);
        return capture;
    }

    private String toolChunk(String id, String name, String arguments) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode choice = root.putArray("choices").addObject();
        choice.putNull("finish_reason");
        ObjectNode toolCall = choice.putObject("delta")
                .putArray("tool_calls").addObject();
        toolCall.put("index", 0);
        if (id != null) toolCall.put("id", id);
        ObjectNode function = toolCall.putObject("function");
        if (name != null) function.put("name", name);
        function.put("arguments", arguments);
        return root.toString();
    }

    private String finishChunk() {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode choice = root.putArray("choices").addObject();
        choice.putObject("delta");
        choice.put("finish_reason", "tool_calls");
        return root.toString();
    }

    private String usageChunk() {
        ObjectNode root = mapper.createObjectNode();
        root.putArray("choices");
        ObjectNode usage = root.putObject("usage");
        usage.put("prompt_tokens", 7);
        usage.put("completion_tokens", 3);
        return root.toString();
    }

    private static final class Capture implements StreamChatCallback {
        final List<LlmStreamEvent> events = new ArrayList<>();
        Throwable error;
        boolean completed;

        @Override public void onEvent(LlmStreamEvent event) { events.add(event); }
        @Override public void onComplete() { completed = true; }
        @Override public void onError(Throwable error) { this.error = error; }
    }
}
