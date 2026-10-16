package com.aicodeassistant.llm;

import com.aicodeassistant.llm.impl.OpenAiCompatibleProvider;
import com.aicodeassistant.model.Usage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises actual wire builders and parsers; HTTP fixtures stay on local MockWebServer. */
class OpenAiCompatibleProviderCacheTest {
    private static final String CLAUDE = "anthropic/claude-opus-5.5";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void actualSyncRequestsKeepBaselineShapeAcrossProviders() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            for (String[] target : List.of(new String[]{"custom", CLAUDE},
                    new String[]{"zenmux", CLAUDE},
                    new String[]{"zenmux", "deepseek/deepseek-chat"},
                    new String[]{"zenmux", "openai/gpt-6-astra"})) {
                enqueueSyncCompletion(server);
                assertThat(localProvider(server, target[0]).chatSync(target[1], "system", "user",
                        1024, null, 10000)).isEqualTo("OK");
                RecordedRequest recorded = takeRequest(server);
                JsonNode body = mapper.readTree(recorded.getBody().readUtf8());
                assertThat(recorded.getPath()).isEqualTo("/api/v1/chat/completions");
                assertThat(recorded.getHeader("x-api-key")).isNull();
                assertThat(body.path("stream").asBoolean()).isFalse();
                assertThat(body.path("messages").get(0).path("content").asText()).isEqualTo("system");
                assertThat(body.path("messages").get(1).path("content").asText()).isEqualTo("user");
                assertThat(countMarkers(body)).isZero();
            }
        }
    }

    private static void enqueueSyncCompletion(MockWebServer server) {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}"));
    }

    @Test
    void mainConversationCachesToolsSystemAndRollingHistoryWithoutChangingInputs() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            var provider = localProvider(server, "zenmux");
            List<Map<String, Object>> history = new ArrayList<>();
            history.add(Map.of("role", "user", "content", "Read the file"));
            List<Map<String, Object>> tools = List.of(tool("Read"), tool("Glob"));
            JsonNode originalTools = mapper.valueToTree(tools);
            JsonNode previous = null;
            for (int round = 0; round < 3; round++) {
                if (round > 0) {
                    String id = "call-" + round;
                    history.add(Map.of("role", "assistant", "content", List.of(Map.of(
                            "type", "tool_use", "id", id, "name", "Read", "input", Map.of("path", "file")))));
                    history.add(Map.of("role", "user", "content", List.of(Map.of(
                            "type", "tool_result", "tool_use_id", id, "content", "result " + round))));
                }
                JsonNode originalHistory = mapper.valueToTree(history);
                enqueueChatCompletion(server);
                Capture capture = new Capture();
                provider.streamChat(CLAUDE, history, "system", tools, 1024, new ThinkingConfig.Disabled(),
                        new LlmCallContext("cache-" + round, CancellationSignal.none()), capture);
                assertThat(capture.error).isNull();
                assertThat(capture.completed).isTrue();
                RecordedRequest recorded = takeRequest(server);
                assertThat(recorded.getPath()).isEqualTo("/api/v1/chat/completions");
                assertThat(recorded.getHeader("x-api-key")).isNull();
                JsonNode body = mapper.readTree(recorded.getBody().readUtf8());
                assertMarker(body.at("/tools/1"));
                assertMarker(body.at("/messages/0/content/0"));
                // tools + system + the only user text boundary; no marker on assistant/tool turns.
                assertThat(countMarkers(body)).isEqualTo(3);
                if (round > 0) {
                    int n = body.path("messages").size();
                    assertThat(body.path("messages").get(n - 2).path("tool_calls").get(0).path("id").asText())
                            .isEqualTo("call-" + round);
                    assertThat(body.path("messages").get(n - 2).path("content").isNull()).isTrue();
                    assertThat(body.path("messages").get(n - 1).path("role").asText()).isEqualTo("tool");
                    assertThat(body.path("messages").get(n - 1).path("tool_call_id").asText()).isEqualTo("call-" + round);
                    assertThat(countMarkers(body.path("messages").get(n - 1))).isZero();
                }
                if (previous != null) {
                    for (int i = 0; i < previous.path("messages").size(); i++) {
                        assertThat(withoutMarkers(body.path("messages").get(i)))
                                .isEqualTo(withoutMarkers(previous.path("messages").get(i)));
                    }
                }
                assertThat((JsonNode) mapper.valueToTree(history)).isEqualTo(originalHistory);
                assertThat((JsonNode) mapper.valueToTree(tools)).isEqualTo(originalTools);
                previous = body;
            }
        }
    }

    @Test
    void historyMarkersStayOnLastTwoUserTextBoundaries() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            enqueueChatCompletion(server);
            List<Map<String, Object>> history = new ArrayList<>();
            history.add(Map.of("role", "user", "content", "first question"));
            history.add(Map.of("role", "assistant", "content", List.of(Map.of(
                    "type", "text", "text", "first answer"))));
            history.add(Map.of("role", "user", "content", "second question"));
            history.add(Map.of("role", "assistant", "content", List.of(Map.of(
                    "type", "tool_use", "id", "call-1", "name", "Read", "input", Map.of("path", "file")))));
            history.add(Map.of("role", "user", "content", List.of(Map.of(
                    "type", "tool_result", "tool_use_id", "call-1", "content", "result"))));
            history.add(Map.of("role", "user", "content", "third question"));
            Capture capture = new Capture();
            localProvider(server, "zenmux").streamChat(CLAUDE, history, "system", List.of(tool("Read")), 1024,
                    new ThinkingConfig.Disabled(), new LlmCallContext("boundaries", CancellationSignal.none()), capture);
            assertThat(capture.error).isNull();
            JsonNode body = mapper.readTree(takeRequest(server).getBody().readUtf8());

            assertThat(body.path("messages")).hasSize(7);
            assertMarker(body.at("/tools/0"));
            assertMarker(body.at("/messages/0/content/0"));
            assertThat(body.at("/messages/1/content/0").has("cache_control")).isFalse();
            assertThat(body.at("/messages/2/content/0").has("cache_control")).isFalse();
            assertMarker(body.at("/messages/3/content/0"));
            assertThat(body.at("/messages/5/content/0").has("cache_control")).isFalse();
            assertMarker(body.at("/messages/6/content/0"));
            assertThat(countMarkers(body)).isEqualTo(4);
            assertThat(body.at("/messages/4/tool_calls/0/id").asText()).isEqualTo("call-1");
            assertThat(body.at("/messages/4/tool_calls/0/function/name").asText()).isEqualTo("Read");
        }
    }

    @Test
    void zenmuxClaudeStreamingReportsFullInputAndCacheBreakdownOnce() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody("""
                    data: {"choices":[{"delta":{"content":"OK"},"finish_reason":"stop"}],"usage":{"prompt_tokens":4,"completion_tokens":4,"prompt_tokens_details":{"cached_tokens":3,"cacheCreationInputTokens":5}}}

                    data: {"choices":[],"usage":{"prompt_tokens":4,"completion_tokens":4,"prompt_tokens_details":{"cached_tokens":3,"cacheCreationInputTokens":5}}}

                    data: [DONE]

                    """));
            Capture capture = new Capture();
            localProvider(server, "zenmux").streamChat(CLAUDE, List.of(Map.of("role", "user", "content", "hello")),
                    "system", List.of(), 1024, new ThinkingConfig.Disabled(),
                    new LlmCallContext("usage", CancellationSignal.none()), capture);
            assertThat(capture.error).isNull();
            assertThat(capture.completed).isTrue();
            assertThat(capture.events.stream().filter(LlmStreamEvent.MessageDelta.class::isInstance)
                    .map(LlmStreamEvent.MessageDelta.class::cast)
                    .map(LlmStreamEvent.MessageDelta::usage)
                    .filter(java.util.Objects::nonNull)
                    .toList())
                    .containsExactly(new Usage(12, 4, 3, 5), new Usage(12, 4, 3, 5));
        }
    }

    @Test
    void emptyResultsAndImagesStayIntactWithoutInventingTextOrExtraMessages() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            enqueueChatCompletion(server);
            List<Map<String, Object>> history = List.of(
                    Map.of("role", "user", "content", List.of(Map.of("type", "image", "source", Map.of(
                            "type", "url", "url", "https://example.invalid/image.png")))),
                    Map.of("role", "assistant", "content", List.of(Map.of("type", "tool_use", "id", "c",
                            "name", "Read", "input", Map.of()))),
                    Map.of("role", "user", "content", List.of(Map.of("type", "tool_result", "tool_use_id", "c", "content", ""))));
            Capture capture = new Capture();
            localProvider(server, "zenmux").streamChat(CLAUDE, history, null, List.of(), 1024,
                    new ThinkingConfig.Disabled(), new LlmCallContext("images", CancellationSignal.none()), capture);
            JsonNode body = mapper.readTree(takeRequest(server).getBody().readUtf8());
            assertThat(capture.error).isNull();
            assertThat(body.path("messages")).hasSize(3);
            assertThat(body.at("/messages/0/content/0/type").asText()).isEqualTo("image_url");
            assertThat(body.at("/messages/0/content/0/image_url/url").asText()).isEqualTo("https://example.invalid/image.png");
            assertThat(body.at("/messages/2/content").asText()).isEmpty();
            assertThat(countMarkers(body)).isZero();
        }
    }

    @Test
    void streamingForOtherModelsKeepsOriginalWireShapeAndUsage() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            for (String[] target : List.of(new String[]{"custom", CLAUDE},
                    new String[]{"zenmux", "deepseek/deepseek-chat"})) {
                enqueueChatCompletion(server);
                Capture capture = new Capture();
                localProvider(server, target[0]).streamChat(target[1], List.of(Map.of("role", "user", "content", "hello")),
                        "system", List.of(), 1024, new ThinkingConfig.Disabled(),
                        new LlmCallContext("other", CancellationSignal.none()), capture);
                JsonNode body = mapper.readTree(takeRequest(server).getBody().readUtf8());
                assertThat(capture.error).isNull();
                assertThat(capture.completed).isTrue();
                assertThat(body.at("/messages/0/content").asText()).isEqualTo("system");
                assertThat(body.at("/messages/1/content").asText()).isEqualTo("hello");
                assertThat(countMarkers(body)).isZero();
                Usage usage = capture.events.stream().filter(LlmStreamEvent.MessageDelta.class::isInstance)
                        .map(LlmStreamEvent.MessageDelta.class::cast).map(LlmStreamEvent.MessageDelta::usage)
                        .filter(java.util.Objects::nonNull).findFirst().orElseThrow();
                assertThat(usage).isEqualTo(new Usage(4, 4, 0, 0));
            }
        }
    }

    private OpenAiCompatibleProvider localProvider(MockWebServer server, String name) {
        return new OpenAiCompatibleProvider(name, mapper,
                new LlmHttpProperties(new LlmHttpProperties.PoolProperties(2, 30), 10, 10, false),
                new ApiKeyRotationManager("test-key"), "test-key", server.url("/api/v1").toString(),
                CLAUDE, List.of(CLAUDE));
    }

    private static RecordedRequest takeRequest(MockWebServer server) throws InterruptedException {
        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        return request;
    }

    private static void enqueueChatCompletion(MockWebServer server) {
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody("""
                data: {"choices":[{"delta":{"content":"OK"},"finish_reason":"stop"}],"usage":{"prompt_tokens":4,"completion_tokens":4,"prompt_tokens_details":{"cached_tokens":3}}}

                data: [DONE]

                """));
    }

    private static Map<String, Object> tool(String name) {
        return Map.of("type", "function", "function", Map.of("name", name,
                "parameters", Map.of("type", "object", "properties", Map.of())));
    }

    private static void assertMarker(JsonNode node) {
        assertThat(node.path("cache_control").path("type").asText()).isEqualTo("ephemeral");
    }

    private static int countMarkers(JsonNode node) {
        int count = node.isObject() && node.has("cache_control") ? 1 : 0;
        for (JsonNode child : node) count += countMarkers(child);
        return count;
    }

    private static JsonNode withoutMarkers(JsonNode node) {
        JsonNode copy = node.deepCopy();
        stripMarkers(copy);
        return copy;
    }

    private static void stripMarkers(JsonNode node) {
        if (node instanceof ObjectNode o) o.remove("cache_control");
        for (JsonNode child : node) stripMarkers(child);
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
