package com.aicodeassistant.llm;

import com.aicodeassistant.llm.impl.OpenAiCompatibleProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

class OpenRouterReasoningTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void strongestReasoningStreamsAndReplaysExactStateOnlyOnSameRoute() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            var provider = provider(server);
            for (String model : List.of(OpenRouterModels.ASTRA, OpenRouterModels.FABLE)) {
                server.enqueue(sse("""
                        data: {"choices":[{"delta":{"reasoning":"Think ","reasoning_details":[{"type":"reasoning.text","index":0,"text":"Think "}]}}]}

                        data: {"choices":[{"delta":{"reasoning":"carefully.","reasoning_details":[{"type":"reasoning.text","index":0,"text":"carefully.","signature":"signature"},{"type":"reasoning.encrypted","index":1,"data":"cipher"}]}}]}

                        data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-1","function":{"name":"lookup","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}

                        data: [DONE]

                        """));
                Capture capture = new Capture();
                provider.streamChat(model, List.of(Map.of("role", "user", "content", "lookup")),
                        "system", List.of(Map.of("type", "function", "function", Map.of("name", "lookup",
                                "parameters", Map.of("type", "object", "properties", Map.of())))),
                        128000, new ThinkingConfig.Disabled(), LlmCallContext.unscoped(), capture);
                assertThat(capture.error).isNull();
                assertThat(capture.completed).isTrue();
                assertThat(capture.thinking()).isEqualTo("Think carefully.");
                var request = server.takeRequest(5, TimeUnit.SECONDS);
                assertThat(request).isNotNull();
                assertThat(request.getPath()).isEqualTo("/api/v1/chat/completions");
                JsonNode body = mapper.readTree(request.getBody().readUtf8());
                assertThat(body.path("model").asText()).isEqualTo(OpenRouterModels.upstreamId(model));
                assertThat(body.path("reasoning").path("effort").asText()).isEqualTo("max");
                assertThat(body.path("reasoning").path("exclude").asBoolean()).isFalse();
                assertThat(body.path("provider").path("require_parameters").asBoolean()).isTrue();
                assertThat(body.path("max_tokens").asInt()).isEqualTo(128000);
                var state = capture.events.stream().filter(LlmStreamEvent.ProviderResponseState.class::isInstance)
                        .map(LlmStreamEvent.ProviderResponseState.class::cast).findFirst().orElseThrow();
                var details = state.outputItems().getFirst().path("reasoning_details");
                assertThat(details.size()).isEqualTo(3);
                assertThat(details.get(1).path("signature").asText()).isEqualTo("signature");
                assertThat(details.get(2).path("data").asText()).isEqualTo("cipher");
                var history = List.<Map<String, Object>>of(
                        Map.of("role", "assistant", "content", List.of(
                                Map.of("type", "thinking", "thinking", "must not leak"),
                                Map.of("type", "tool_use", "id", "call-1", "name", "lookup", "input", Map.of()),
                                Map.of("type", "provider_response_state", "provider", "openrouter", "model", model,
                                        "output", state.outputItems()))),
                        Map.of("role", "user", "content", List.of(Map.of("type", "tool_result",
                                "tool_use_id", "call-1", "content", "42"))));
                for (String target : List.of(model, model.equals(OpenRouterModels.ASTRA)
                        ? OpenRouterModels.FABLE : OpenRouterModels.ASTRA)) {
                    server.enqueue(sse("data: {\"choices\":[{\"delta\":{\"content\":\"42\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"));
                    provider.streamChat(target, history, null, List.of(), 4096,
                            new ThinkingConfig.Adaptive(), LlmCallContext.unscoped(), new Capture());
                    var replay = mapper.readTree(server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8());
                    var assistant = replay.path("messages").get(0);
                    assertThat(assistant.has("reasoning_content")).isFalse();
                    if (target.equals(model)) assertThat(assistant.get("reasoning_details")).isEqualTo(details);
                    else assertThat(assistant.has("reasoning_details")).isFalse();
                    assertThat(replay.path("messages").get(1).path("tool_call_id").asText()).isEqualTo("call-1");
                }
            }
        }
    }

    @Test
    void truncatedAndInBandErrorStreamsNeverReportSuccess() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            for (String response : List.of(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n",
                    "data: {\"error\":{\"code\":429,\"message\":\"rate limited\"}}\n\ndata: [DONE]\n\n")) {
                server.enqueue(sse(response));
                Capture capture = new Capture();
                provider(server).streamChat(OpenRouterModels.ASTRA, List.of(), "system", List.of(),
                        4096, new ThinkingConfig.Adaptive(), LlmCallContext.unscoped(), capture);
                assertThat(capture.completed).isFalse();
                assertThat(capture.error).isInstanceOf(LlmApiException.class);
            }
        }
    }

    @Test
    void synchronousRequestsAlsoUseUpstreamIdAndMaxEffort() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}"));
            assertThat(provider(server).chatSync(OpenRouterModels.ASTRA, "system", "hello", 4096, null, 5000))
                    .isEqualTo("OK");
            var body = mapper.readTree(server.takeRequest(5, TimeUnit.SECONDS).getBody().readUtf8());
            assertThat(body.path("model").asText()).isEqualTo("openai/gpt-6-astra");
            assertThat(body.path("reasoning").path("effort").asText()).isEqualTo("max");
        }
    }

    private OpenAiCompatibleProvider provider(MockWebServer server) {
        return new OpenAiCompatibleProvider("openrouter", mapper,
                new LlmHttpProperties(new LlmHttpProperties.PoolProperties(2, 30), 10, 10, true),
                new ApiKeyRotationManager("key"), "key", server.url("/api/v1").toString(),
                OpenRouterModels.ASTRA, List.of(OpenRouterModels.ASTRA, OpenRouterModels.FABLE));
    }

    private MockResponse sse(String body) {
        return new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body);
    }

    static class Capture implements StreamChatCallback {
        final List<LlmStreamEvent> events = new ArrayList<>();
        Throwable error;
        boolean completed;
        public void onEvent(LlmStreamEvent event) { events.add(event); }
        public void onError(Throwable error) { this.error = error; }
        public void onComplete() { completed = true; }
        String thinking() { return events.stream().filter(LlmStreamEvent.ThinkingDelta.class::isInstance)
                .map(LlmStreamEvent.ThinkingDelta.class::cast).map(LlmStreamEvent.ThinkingDelta::thinking)
                .collect(java.util.stream.Collectors.joining()); }
    }
}
