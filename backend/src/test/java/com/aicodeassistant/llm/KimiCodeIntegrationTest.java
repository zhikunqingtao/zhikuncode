package com.aicodeassistant.llm;

import com.aicodeassistant.controller.ModelController;
import com.aicodeassistant.llm.impl.OpenAiCompatibleProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class KimiCodeIntegrationTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final LlmHttpProperties HTTP = new LlmHttpProperties(
            new LlmHttpProperties.PoolProperties(2, 1), 5, 5, false);

    @Test
    void actualConfigurationKeepsSubscriptionAndMoonshotRoutesIndependent() throws Exception {
        var configs = config("subscription-key");
        var subscription = configs.providers().get("kimi-code");
        var moonshot = configs.providers().get("moonshot");
        assertThat(subscription.baseUrl()).isEqualTo("https://api.kimi.com/coding/v1");
        assertThat(subscription.defaultModel()).isEqualTo("k3");
        assertThat(subscription.models()).containsExactly("k3", "kimi-for-coding");
        assertThat(moonshot.baseUrl()).isEqualTo("https://api.moonshot.cn/v1");
        assertThat(moonshot.defaultModel()).isEqualTo("kimi-k3");
        assertThat(moonshot.apiKey()).isEqualTo("moonshot-key");

        var providers = providers(configs);
        assertThat(providers).hasSize(2);
        var reversed = new ArrayList<>(providers.reversed());
        for (var order : List.of(providers, reversed)) {
            var routes = new LlmProviderRegistry(order, new MockEnvironment());
            assertThat(routes.getProvider("kimi-k3").getProviderName()).isEqualTo("moonshot");
            for (String id : List.of("k3", "kimi-for-coding")) {
                assertThat(routes.getProvider(id).getProviderName()).isEqualTo("kimi-code");
                assertThat(routes.getProvider(id).supportsThinking(id)).isTrue();
            }
            var models = new ModelRegistry(routes);
            assertThat(models.getCapabilities("kimi-k3").displayName()).isEqualTo("Kimi K3");
            assertThat(models.getCapabilities("kimi-k3").costPer1kInput()).isEqualTo(0.002);
            var response = new ModelController(routes, models).listModels(null).getBody();
            assertThat(response).isNotNull();
            for (String id : List.of("k3", "kimi-for-coding")) {
                assertThat(response.models()).filteredOn(m -> id.equals(m.id())).singleElement().satisfies(m -> {
                    assertThat(m.displayName()).isEqualTo("k3".equals(id)
                            ? "Kimi K3（订阅）" : "Kimi K2.8 Preview（订阅）");
                    assertThat(m.contextWindow()).isEqualTo(1_048_576);
                    assertThat(m.maxOutputTokens()).isEqualTo(131_072);
                    assertThat(m.supportsThinking()).isTrue();
                    assertThat(m.supportsStreaming()).isTrue();
                    assertThat(m.supportsToolUse()).isTrue();
                    assertThat(m.supportsImages()).isTrue();
                    assertThat(m.maxImages()).isEqualTo(8);
                    assertThat(m.costPer1kInput()).isZero();
                    assertThat(m.costPer1kOutput()).isZero();
                });
                assertThat(models.getCapabilities(id).imageInputMode())
                        .isEqualTo(ModelCapabilities.ImageInputMode.BASE64_ONLY);
            }
        }
    }

    @Test
    void missingSubscriptionKeyDoesNotExposeSubscriptionModels() throws Exception {
        var routes = new LlmProviderRegistry(providers(config("")), new MockEnvironment());
        assertThat(routes.listAvailableModels()).contains("kimi-k3")
                .doesNotContain("k3", "kimi-for-coding");
    }

    @ParameterizedTest
    @ValueSource(strings = {"k3", "kimi-for-coding"})
    void streamingAlwaysUsesMaxAndPreservesReasoningWithToolResults(String model) throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            var provider = provider(server, "kimi-code", model);
            var messages = List.<Map<String, Object>>of(
                    Map.of("role", "user", "content", "Add 2 and 3 using the tool."),
                    Map.of("role", "assistant", "content", List.of(
                            Map.of("type", "thinking", "thinking", "Use the supplied addition tool."),
                            Map.of("type", "tool_use", "id", "call_add", "name", "add",
                                    "input", Map.of("a", 2, "b", 3)))),
                    Map.of("role", "user", "content", List.of(
                            Map.of("type", "tool_result", "tool_use_id", "call_add", "content", "5"))));
            for (ThinkingConfig mode : new ThinkingConfig[]{null, new ThinkingConfig.Disabled(),
                    new ThinkingConfig.Adaptive(), new ThinkingConfig.Enabled(2000)}) {
                server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                        "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"Read the tool result.\"}}]}\n\n"
                                + "data: {\"choices\":[{\"delta\":{\"content\":\"5\"},\"finish_reason\":\"stop\"}]}\n\n"
                                + "data: [DONE]\n\n"));
                var capture = new Capture();
                provider.streamChat(model, messages, "Coding test", List.of(), 131072, mode,
                        LlmCallContext.unscoped(), capture);
                assertThat(capture.error).isNull();
                assertThat(capture.complete).isTrue();
                assertThat(capture.events).contains(new LlmStreamEvent.TextDelta("5"),
                        new LlmStreamEvent.ThinkingDelta("Read the tool result."));
                var request = server.takeRequest(2, TimeUnit.SECONDS);
                assertThat(request).isNotNull();
                assertThat(request.getPath()).isEqualTo("/v1/chat/completions");
                assertThat(request.getHeader("Authorization")).isEqualTo("Bearer test-key");
                JsonNode body = MAPPER.readTree(request.getBody().readUtf8());
                assertMaxRequest(body, model);
                assertThat(body.path("stream").asBoolean()).isTrue();
                assertThat(body.path("stream_options").path("include_usage").asBoolean()).isTrue();
                assertThat(body.path("messages").get(2).path("reasoning_content").asText())
                        .isEqualTo("Use the supplied addition tool.");
                assertThat(body.path("messages").get(3).path("role").asText()).isEqualTo("tool");
                assertThat(body.path("messages").get(3).path("tool_call_id").asText()).isEqualTo("call_add");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"k3", "kimi-for-coding"})
    void synchronousRequestsAlsoUseMax(String model) throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}"));
            String reply = provider(server, "kimi-code", model)
                    .chatSync(model, "Coding test", "Reply OK", 131072, null, 5000);
            assertThat(reply).isEqualTo("OK");
            var request = server.takeRequest(2, TimeUnit.SECONDS);
            assertThat(request).isNotNull();
            var body = MAPPER.readTree(request.getBody().readUtf8());
            assertMaxRequest(body, model);
            assertThat(body.path("stream").asBoolean()).isFalse();
        }
    }

    @Test
    void moonshotKeepsItsExistingRequestProtocol() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                    "data: {\"choices\":[{\"delta\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}\n\n"
                            + "data: [DONE]\n\n"));
            var capture = new Capture();
            provider(server, "moonshot", "kimi-k3").streamChat("kimi-k3",
                    List.of(Map.of("role", "user", "content", "Reply OK")), "", List.of(), 512,
                    new ThinkingConfig.Disabled(), LlmCallContext.unscoped(), capture);
            assertThat(capture.error).isNull();
            assertThat(capture.complete).isTrue();
            var request = server.takeRequest(2, TimeUnit.SECONDS);
            assertThat(request).isNotNull();
            var body = MAPPER.readTree(request.getBody().readUtf8());
            assertThat(body.path("model").asText()).isEqualTo("kimi-k3");
            assertThat(body.path("max_completion_tokens").asInt()).isEqualTo(512);
            assertThat(body.has("max_tokens")).isFalse();
            assertThat(body.path("reasoning_effort").asText()).isEqualTo("max");
            assertThat(body.has("thinking")).isFalse();
        }
    }

    private static void assertMaxRequest(JsonNode body, String model) {
        assertThat(body.path("model").asText()).isEqualTo(model);
        assertThat(body.path("reasoning_effort").asText()).isEqualTo("max");
        assertThat(body.path("thinking").path("type").asText()).isEqualTo("enabled");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(131072);
        assertThat(body.has("max_completion_tokens")).isFalse();
    }

    private LlmProvidersProperties config(String key) throws Exception {
        var env = new MockEnvironment().withProperty("LLM_PROVIDER_KIMI_CODE_API_KEY", key)
                .withProperty("LLM_PROVIDER_MOONSHOT_API_KEY", "moonshot-key");
        for (var source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))) {
            env.getPropertySources().addLast(source);
        }
        return Binder.get(env).bind("llm", Bindable.of(LlmProvidersProperties.class)).get();
    }

    private List<LlmProvider> providers(LlmProvidersProperties configs) {
        return new MultiProviderConfiguration().openAiCompatibleProviders(configs, HTTP,
                MAPPER, "", "https://api.openai.com/v1", "unused", List.of(), null);
    }

    private OpenAiCompatibleProvider provider(MockWebServer server, String name, String model) {
        return new OpenAiCompatibleProvider(name, MAPPER, HTTP, new ApiKeyRotationManager("test-key"),
                "test-key", server.url("/v1").toString(), model, List.of(model));
    }

    private static class Capture implements StreamChatCallback {
        final List<LlmStreamEvent> events = new ArrayList<>();
        boolean complete;
        Throwable error;
        public void onEvent(LlmStreamEvent event) { events.add(event); }
        public void onComplete() { complete = true; }
        public void onError(Throwable error) { this.error = error; }
    }
}
