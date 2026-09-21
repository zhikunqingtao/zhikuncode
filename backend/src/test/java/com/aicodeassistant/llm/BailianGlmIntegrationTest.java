package com.aicodeassistant.llm;

import com.aicodeassistant.controller.ModelController;
import com.aicodeassistant.llm.impl.OpenAiCompatibleProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;
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

class BailianGlmIntegrationTest {
    private static final String MODEL = BailianTokenPlanModels.GLM_53;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final LlmHttpProperties HTTP = new LlmHttpProperties(
            new LlmHttpProperties.PoolProperties(2, 1), 5, 5, false);

    @Test
    void actualConfigExposesIndependentRoutesAndFullContextInEitherRegistrationOrder() throws Exception {
        var providers = configuredProviders("subscription-key");
        assertThat(providers).hasSize(2);
        for (var order : List.of(providers, providers.reversed())) {
            var routes = new LlmProviderRegistry(order, new MockEnvironment());
            assertThat(routes.getProvider(MODEL).getProviderName()).isEqualTo("dashscope-token-plan");
            assertThat(routes.getProvider("glm-5.3").getProviderName()).isEqualTo("zhipu");
            assertThat(routes.getProvider("glm-5.3-flash").getProviderName()).isEqualTo("zhipu");
            assertThat(routes.getProvider("deepseek-v4.1-flash").getProviderName()).isEqualTo("dashscope-token-plan");
            assertThat(routes.getProvider(MODEL).supportsThinking(MODEL)).isTrue();
            var models = new ModelRegistry(routes);
            assertThat(models.isKnownModel(MODEL)).isTrue();
            assertThat(models.getCapabilities("glm-5.3").contextWindow()).isEqualTo(1048576);
            assertThat(models.getCapabilities(MODEL).supportsCache()).isTrue();
            var response = new ModelController(routes, models).listModels(null).getBody();
            assertThat(response).isNotNull();
            assertThat(response.models()).filteredOn(m -> MODEL.equals(m.id())).singleElement().satisfies(m -> {
                assertThat(m.displayName()).isEqualTo("GLM-5.3（百炼）");
                assertThat(m.contextWindow()).isEqualTo(1000000);
                assertThat(m.maxOutputTokens()).isEqualTo(65536);
                assertThat(m.supportsThinking()).isTrue();
                assertThat(m.supportsStreaming()).isTrue();
                assertThat(m.supportsToolUse()).isTrue();
                assertThat(m.supportsImages()).isFalse();
                assertThat(m.costPer1kInput()).isZero();
            });
        }
    }

    @Test
    void rawSubscriptionIdIsNormalizedAndDeduplicatedWithoutChangingZhipu() {
        var subscription = provider("dashscope-token-plan", "http://localhost/v1", "glm-5.3",
                List.of("glm-5.3", MODEL, "deepseek-v4.1-flash"));
        var direct = provider("zhipu", "http://localhost/v1", "glm-5.3", List.of("glm-5.3"));
        assertThat(subscription.getDefaultModel()).isEqualTo(MODEL);
        assertThat(subscription.getSupportedModels()).containsExactly(MODEL, "deepseek-v4.1-flash");
        assertThat(direct.getDefaultModel()).isEqualTo("glm-5.3");
        var routes = new LlmProviderRegistry(List.of(subscription, direct), new MockEnvironment());
        assertThat(routes.getProvider(MODEL)).isSameAs(subscription);
        assertThat(routes.getProvider("glm-5.3")).isSameAs(direct);
    }

    @Test
    void missingSubscriptionKeyLeavesOnlyDirectGlm() throws Exception {
        var routes = new LlmProviderRegistry(configuredProviders(""), new MockEnvironment());
        assertThat(routes.listAvailableModels()).contains("glm-5.3").doesNotContain(MODEL);
    }

    @Test
    void liveDiagnosticsNormalizeBeforeModelSelectionAndDispatch() {
        assertThat(McpModelCompatibilityLiveTest.normalizeModelIds("dashscope-token-plan",
                List.of("glm-5.3", MODEL, "deepseek-v4.1-flash")))
                .containsExactly(MODEL, "deepseek-v4.1-flash");
        assertThat(McpModelCompatibilityLiveTest.normalizeModelIds("zhipu", List.of("glm-5.3")))
                .containsExactly("glm-5.3");
    }

    @Test
    void streamingUsesSubscriptionWireIdAndMaxWhilePreservingToolReasoning() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            var provider = provider("dashscope-token-plan", server.url("/v1").toString(), MODEL, List.of(MODEL));
            var messages = List.<Map<String, Object>>of(
                    Map.of("role", "user", "content", "Add 2 and 3."),
                    Map.of("role", "assistant", "content", List.of(
                            Map.of("type", "thinking", "thinking", "Use the addition tool."),
                            Map.of("type", "tool_use", "id", "add_call", "name", "add", "input", Map.of("a", 2, "b", 3)))),
                    Map.of("role", "user", "content", List.of(
                            Map.of("type", "tool_result", "tool_use_id", "add_call", "content", "5"))));
            for (ThinkingConfig mode : new ThinkingConfig[]{null, new ThinkingConfig.Disabled(), new ThinkingConfig.Adaptive()}) {
                server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                        "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"Check result.\"}}]}\n\n"
                                + "data: {\"choices\":[{\"delta\":{\"content\":\"5\"},\"finish_reason\":\"stop\"}]}\n\n"
                                + "data: [DONE]\n\n"));
                var capture = new Capture();
                provider.streamChat(MODEL, messages, "Test", List.of(), 65536, mode, LlmCallContext.unscoped(), capture);
                assertThat(capture.error).isNull();
                assertThat(capture.complete).isTrue();
                assertThat(capture.events).contains(new LlmStreamEvent.TextDelta("5"), new LlmStreamEvent.ThinkingDelta("Check result."));
                var request = server.takeRequest(2, TimeUnit.SECONDS);
                assertThat(request).isNotNull();
                assertThat(request.getHeader("Authorization")).isEqualTo("Bearer dashscope-token-plan-key");
                var body = MAPPER.readTree(request.getBody().readUtf8());
                assertSubscriptionBody(body);
                assertThat(body.path("stream").asBoolean()).isTrue();
                assertThat(body.path("messages").get(2).path("reasoning_content").asText()).isEqualTo("Use the addition tool.");
                assertThat(body.path("messages").get(3).path("tool_call_id").asText()).isEqualTo("add_call");
            }
        }
    }

    @Test
    void syncUsesSameWireIdAndMaxWithoutAffectingDirectRequestShape() throws Exception {
        try (var subscription = new MockWebServer(); var direct = new MockWebServer()) {
            subscription.start(); direct.start();
            subscription.enqueue(new MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}"));
            var sub = provider("dashscope-token-plan", subscription.url("/v1").toString(), MODEL, List.of(MODEL));
            assertThat(sub.chatSync(MODEL, "", "Reply OK", 65536, null, 5000)).isEqualTo("OK");
            var req = subscription.takeRequest(2, TimeUnit.SECONDS);
            assertThat(req).isNotNull();
            var body = MAPPER.readTree(req.getBody().readUtf8());
            assertSubscriptionBody(body);
            assertThat(body.path("stream").asBoolean()).isFalse();
            direct.enqueue(new MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"));
            var zhipu = provider("zhipu", direct.url("/v4").toString(), "glm-5.3", List.of("glm-5.3"));
            var capture = new Capture();
            zhipu.streamChat("glm-5.3", List.of(Map.of("role", "user", "content", "Reply OK")), "", List.of(), 512,
                    new ThinkingConfig.Disabled(), LlmCallContext.unscoped(), capture);
            assertThat(capture.error).isNull();
            assertThat(capture.complete).isTrue();
            var directReq = direct.takeRequest(2, TimeUnit.SECONDS);
            assertThat(directReq).isNotNull();
            assertThat(directReq.getHeader("Authorization")).isEqualTo("Bearer zhipu-key");
            var directBody = MAPPER.readTree(directReq.getBody().readUtf8());
            assertThat(directBody.path("model").asText()).isEqualTo("glm-5.3");
            assertThat(directBody.path("thinking").path("type").asText()).isEqualTo("enabled");
            assertThat(directBody.path("reasoning_effort").asText()).isEqualTo("max");
            assertThat(directBody.has("enable_thinking")).isFalse();
        }
    }

    private void assertSubscriptionBody(JsonNode body) {
        assertThat(body.path("model").asText()).isEqualTo("glm-5.3");
        assertThat(body.path("enable_thinking").asBoolean()).isTrue();
        assertThat(body.path("reasoning_effort").asText()).isEqualTo("max");
        assertThat(body.path("clear_thinking").asBoolean(true)).isFalse();
        assertThat(body.path("max_tokens").asInt()).isEqualTo(65536);
        assertThat(body.has("thinking")).isFalse();
        assertThat(body.has("tool_stream")).isFalse();
    }

    private List<LlmProvider> configuredProviders(String key) throws Exception {
        var env = new MockEnvironment().withProperty("LLM_PROVIDER_DASHSCOPE_TOKEN_PLAN_API_KEY", key)
                .withProperty("LLM_PROVIDER_ZHIPU_API_KEY", "direct-key");
        for (var source : new YamlPropertySourceLoader().load("app", new ClassPathResource("application.yml"))) env.getPropertySources().addLast(source);
        var config = Binder.get(env).bind("llm", Bindable.of(LlmProvidersProperties.class)).get();
        assertThat(config.providers().get("dashscope-token-plan").baseUrl()).isEqualTo("https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1");
        return new MultiProviderConfiguration().openAiCompatibleProviders(config, HTTP, MAPPER, "", "", "", List.of(), null);
    }

    private OpenAiCompatibleProvider provider(String name, String url, String model, List<String> models) {
        return new OpenAiCompatibleProvider(name, MAPPER, HTTP, new ApiKeyRotationManager(name + "-key"), name + "-key", url, model, models);
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
