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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ZenMuxResponsesProviderTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private MockWebServer server;
    private OpenAiCompatibleProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        provider = new OpenAiCompatibleProvider(
                "zenmux", mapper,
                new LlmHttpProperties(new LlmHttpProperties.PoolProperties(2, 30),
                        10, 10, false),
                new ApiKeyRotationManager("key"), "key",
                server.url("/v1").toString(), "anthropic/claude-opus-4.8",
                List.of("anthropic/claude-opus-4.8", "openai/gpt-5.6-sol",
                        "openai/gpt-6-astra", "google/gemini-3.8-flash",
                        "x-ai/grok-4.6"));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void buildsStatelessRequestAndReplaysAuthoritativeOutput() throws Exception {
        JsonNode previous = mapper.readTree("""
                {"type":"reasoning","id":"r0","encrypted_content":"secret","summary":[]}
                """);
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "assistant", "content", List.of(
                        Map.of("type", "text", "text", "duplicate projection"),
                        Map.of("type", "provider_response_state", "provider", "zenmux",
                                "model", "openai/gpt-6-astra", "output", List.of(previous)))),
                Map.of("role", "user", "content", List.of(
                        Map.of("type", "tool_result", "tool_use_id", "call-old",
                                "content", "tool output"),
                        Map.of("type", "text", "text", "continue"),
                        Map.of("type", "image", "source", Map.of(
                                "type", "url", "url", "https://example.invalid/image.png")))));
        List<Map<String, Object>> tools = List.of(Map.of(
                "name", "Read", "description", "read a file",
                "input_schema", Map.of("type", "object", "properties", Map.of())));
        enqueue(completedFixture());

        Capture capture = run("openai/gpt-6-astra", messages, tools,
                new ThinkingConfig.Adaptive());
        RecordedRequest recorded = server.takeRequest();
        JsonNode body = mapper.readTree(recorded.getBody().readUtf8());

        assertThat(recorded.getPath()).isEqualTo("/v1/responses");
        assertThat(body.path("store").asBoolean()).isFalse();
        assertThat(body.path("parallel_tool_calls").asBoolean()).isTrue();
        assertThat(body.path("max_output_tokens").asInt()).isEqualTo(4096);
        assertThat(body.path("instructions").asText()).isEqualTo("system");
        assertThat(body.path("reasoning").path("effort").asText()).isEqualTo("xhigh");
        assertThat(body.path("reasoning").path("summary").asText()).isEqualTo("auto");
        assertThat(body.path("include").get(0).asText())
                .isEqualTo("reasoning.encrypted_content");
        assertThat(body.has("previous_response_id")).isFalse();
        assertThat(body.has("conversation")).isFalse();
        assertThat(body.toString()).doesNotContain("duplicate projection");
        assertThat(body.path("input").get(0).path("encrypted_content").asText())
                .isEqualTo("secret");
        assertThat(body.path("input").toString()).contains("function_call_output", "input_image", "\"detail\":\"auto\"");
        assertThat(body.path("tools").get(0).path("strict").asBoolean()).isFalse();
        assertThat(body.path("tools").get(0).path("function").isMissingNode()).isTrue();

        assertThat(capture.error).isNull();
        assertThat(capture.completed).isTrue();
        assertThat(capture.events).anyMatch(LlmStreamEvent.ThinkingDelta.class::isInstance);
        assertThat(capture.events).anyMatch(LlmStreamEvent.TextDelta.class::isInstance);
        LlmStreamEvent.ProviderResponseState saved = capture.events.stream()
                .filter(LlmStreamEvent.ProviderResponseState.class::isInstance)
                .map(LlmStreamEvent.ProviderResponseState.class::cast)
                .findFirst().orElseThrow();
        assertThat(saved.outputItems()).hasSize(4);
        assertThat(saved.outputItems().getFirst().path("encrypted_content").asText())
                .isEqualTo("encrypted-next");
        assertThat(capture.events.stream()
                .filter(LlmStreamEvent.ToolUseStart.class::isInstance)).hasSize(2);
        LlmStreamEvent.MessageDelta end = capture.events.stream()
                .filter(LlmStreamEvent.MessageDelta.class::isInstance)
                .map(LlmStreamEvent.MessageDelta.class::cast)
                .findFirst().orElseThrow();
        assertThat(end.stopReason()).isEqualTo("tool_use");
        assertThat(end.usage().inputTokens()).isEqualTo(12);
        assertThat(end.usage().cacheReadInputTokens()).isEqualTo(3);
    }

    @Test
    void disabledReasoningUsesNoneAndGeminiUsesHighWhenEnabled() throws Exception {
        enqueue(simpleCompletion());
        run("google/gemini-3.8-flash", List.of(
                Map.of("role", "user", "content", "hello")), List.of(),
                new ThinkingConfig.Disabled());
        JsonNode disabled = mapper.readTree(server.takeRequest().getBody().readUtf8());
        assertThat(disabled.path("reasoning").path("effort").asText()).isEqualTo("none");

        enqueue(simpleCompletion());
        run("x-ai/grok-4.6", List.of(
                Map.of("role", "user", "content", "hello")), List.of(),
                new ThinkingConfig.Enabled(1000));
        JsonNode enabled = mapper.readTree(server.takeRequest().getBody().readUtf8());
        assertThat(enabled.path("reasoning").path("effort").asText()).isEqualTo("high");
    }

    @Test
    void maxOutputIncompleteWithoutCallsIsRecoverableAndPersistsState() {
        enqueue("""
                {"type":"response.incomplete","response":{"output":[{"type":"message","id":"m1","content":[]}],"incomplete_details":{"reason":"max_output_tokens"},"usage":{"input_tokens":2,"output_tokens":4}}}
                """);
        Capture capture = run("openai/gpt-5.6-sol", List.of(), List.of(),
                new ThinkingConfig.Disabled());

        assertThat(capture.error).isNull();
        assertThat(capture.completed).isTrue();
        assertThat(capture.events).anyMatch(LlmStreamEvent.ProviderResponseState.class::isInstance);
        LlmStreamEvent.MessageDelta end = capture.events.stream()
                .filter(LlmStreamEvent.MessageDelta.class::isInstance)
                .map(LlmStreamEvent.MessageDelta.class::cast).findFirst().orElseThrow();
        assertThat(end.stopReason()).isEqualTo("max_tokens");
    }

    @Test
    void incompleteWithFunctionCallFailsWithoutExecutingTools() {
        enqueue("""
                {"type":"response.incomplete","response":{"output":[{"type":"function_call","call_id":"c1","name":"Read","arguments":"{}"}],"incomplete_details":{"reason":"max_output_tokens"}}}
                """);
        Capture capture = run("openai/gpt-6-astra", List.of(), List.of(),
                new ThinkingConfig.Adaptive());

        assertThat(capture.completed).isFalse();
        assertThat(capture.error).isInstanceOf(LlmApiException.class);
        assertThat(capture.events).noneMatch(LlmStreamEvent.ToolUseStart.class::isInstance);
        assertThat(capture.events).noneMatch(LlmStreamEvent.ProviderResponseState.class::isInstance);
    }

    @Test
    void invalidLaterParallelCallPreventsEveryToolFromExecuting() {
        enqueue("""
                {"type":"response.completed","response":{"output":[{"type":"function_call","call_id":"c1","name":"Read","arguments":"{}"},{"type":"function_call","call_id":"c2","name":"Glob","arguments":"not-json"}]}}
                """);
        Capture capture = run("openai/gpt-6-astra", List.of(), List.of(),
                new ThinkingConfig.Adaptive());

        assertThat(capture.completed).isFalse();
        assertThat(capture.error).isInstanceOf(LlmApiException.class);
        assertThat(capture.events).noneMatch(LlmStreamEvent.ToolUseStart.class::isInstance);
        assertThat(capture.events).noneMatch(LlmStreamEvent.ProviderResponseState.class::isInstance);
    }

    @Test
    void failedAndTerminalLessStreamsFailClosed() {
        enqueue("""
                {"type":"response.output_item.added","output_index":0,"item":{"type":"reasoning"}}
                {"type":"response.failed","response":{"error":{"code":"server_error","message":"bad"}}}
                """);
        Capture failed = run("openai/gpt-6-astra", List.of(), List.of(),
                new ThinkingConfig.Adaptive());
        assertThat(failed.completed).isFalse();
        assertThat(failed.error).isInstanceOf(LlmApiException.class);
        assertThat(failed.events).anyMatch(LlmStreamEvent.ProviderProgress.class::isInstance);

        enqueue("""
                : keep-alive

                {"type":"future.extension","value":1}
                """);
        Capture eof = run("openai/gpt-6-astra", List.of(), List.of(),
                new ThinkingConfig.Adaptive());
        assertThat(eof.completed).isFalse();
        assertThat(assertInstanceOf(LlmApiException.class, eof.error).getMessage())
                .contains("INCOMPLETE_STREAM");
    }

    private Capture run(String model, List<Map<String, Object>> messages,
                        List<Map<String, Object>> tools, ThinkingConfig thinking) {
        Capture capture = new Capture();
        provider.streamChat(model, messages, "system", tools, 4096, thinking,
                LlmCallContext.unscoped(), capture);
        return capture;
    }

    private void enqueue(String events) {
        StringBuilder body = new StringBuilder();
        for (String line : events.lines().toList()) {
            if (line.isBlank() || line.startsWith(":")) body.append(line).append("\n");
            else body.append("data: ").append(line).append("\n\n");
        }
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body.toString()));
    }

    private String simpleCompletion() {
        return """
                {"type":"response.completed","response":{"output":[],"usage":{"input_tokens":1,"output_tokens":1}}}
                """;
    }

    private String completedFixture() {
        return """
                {"type":"response.reasoning_summary_text.delta","delta":"summary"}
                {"type":"response.output_text.delta","delta":"answer"}
                {"type":"future.extension","ignored":true}
                {"type":"response.completed","response":{"output":[{"type":"reasoning","id":"r1","encrypted_content":"encrypted-next","summary":[{"type":"summary_text","text":"summary"}]},{"type":"message","id":"m1","role":"assistant","content":[{"type":"output_text","text":"answer"}]},{"type":"function_call","id":"fc1","call_id":"call-1","name":"Read","arguments":"{\\"file_path\\":\\"/tmp/a\\"}"},{"type":"function_call","id":"fc2","call_id":"call-2","name":"Glob","arguments":"{\\"pattern\\":\\"*.java\\"}"}],"usage":{"input_tokens":12,"output_tokens":7,"input_tokens_details":{"cached_tokens":3}}}}
                """;
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
