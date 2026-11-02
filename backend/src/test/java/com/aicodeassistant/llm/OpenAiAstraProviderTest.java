package com.aicodeassistant.llm;

import com.aicodeassistant.llm.impl.OpenAiCompatibleProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiAstraProviderTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private MockWebServer server;
    private OpenAiCompatibleProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        provider = provider("openai", "gpt-6-astra");
    }

    private OpenAiCompatibleProvider provider(String name, String model) {
        return new OpenAiCompatibleProvider(name, mapper,
                new LlmHttpProperties(new LlmHttpProperties.PoolProperties(2, 30), 10, 10, false),
                new ApiKeyRotationManager("test-key"), "test-key",
                server.url("/v1").toString(), model, List.of(model));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void directAndGatewayModelsHaveIndependentRoutesAndCapabilities() {
        OpenAiCompatibleProvider zenmux = provider("zenmux", "openai/gpt-6-astra");
        LlmProviderRegistry providers = new LlmProviderRegistry(List.of(provider, zenmux), new MockEnvironment());
        ModelRegistry models = new ModelRegistry(providers);

        assertThat(providers.getProvider("gpt-6-astra")).isSameAs(provider);
        assertThat(providers.getProvider("openai/gpt-6-astra")).isSameAs(zenmux);
        assertThat(provider.supportsThinking("gpt-6-astra")).isTrue();
        ModelCapabilities caps = models.getCapabilities("gpt-6-astra");
        assertThat(caps.displayName()).isEqualTo("GPT-6 Astra（OpenAI）");
        assertThat(caps.contextWindow()).isEqualTo(1_050_000);
        assertThat(caps.maxOutputTokens()).isEqualTo(128_000);
        assertThat(caps.supportsImages()).isTrue();
        assertThat(caps.supportsThinking()).isTrue();
        assertThat(caps.supportsToolUse()).isTrue();
    }

    @Test
    void everyApplicationThinkingModeUsesResponsesWithMaxEffort() throws Exception {
        for (ThinkingConfig thinking : Arrays.asList(null, new ThinkingConfig.Disabled(),
                new ThinkingConfig.Adaptive(), new ThinkingConfig.Enabled(1000))) {
            JsonNode body = request(List.of(Map.of("role", "user", "content", "hello")), thinking);
            assertThat(body.path("model").asText()).isEqualTo("gpt-6-astra");
            assertThat(body.path("reasoning").path("effort").asText()).isEqualTo("max");
            assertThat(body.path("max_output_tokens").asInt()).isEqualTo(65_536);
            assertThat(body.has("temperature")).isFalse();
            assertThat(body.has("top_p")).isFalse();
            assertThat(body.has("max_tokens")).isFalse();
        }
    }

    @Test
    void officialRouteEncodesUrlAndBase64Images() throws Exception {
        List<Map<String, Object>> images = List.of(
                Map.of("type", "image", "source", Map.of("type", "url", "url", "https://example.invalid/image.png")),
                Map.of("type", "image", "source", Map.of("type", "base64", "media_type", "image/png", "data", "aW1hZ2U=")));
        JsonNode body = request(List.of(Map.of("role", "user", "content", images)), null);
        JsonNode content = body.path("input").get(0).path("content");
        assertThat(content.findValuesAsText("type")).containsExactly("input_image", "input_image");
        assertThat(content.get(0).path("image_url").asText()).isEqualTo("https://example.invalid/image.png");
        assertThat(content.get(1).path("image_url").asText()).isEqualTo("data:image/png;base64,aW1hZ2U=");
    }

    @Test
    void gatewayPrivateStateIsNeverReplayedToOfficialProvider() throws Exception {
        JsonNode body = request(List.of(Map.of("role", "assistant", "content", List.of(
                Map.of("type", "text", "text", "public answer"),
                Map.of("type", "provider_response_state", "provider", "zenmux", "model", "openai/gpt-6-astra",
                        "output", List.of(Map.of("type", "reasoning", "encrypted_content", "gateway-secret")))))), null);
        assertThat(body.toString()).contains("public answer", "output_text").doesNotContain("gateway-secret");
    }

    private JsonNode request(List<Map<String, Object>> messages, ThinkingConfig thinking) throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"type\":\"response.completed\",\"response\":{\"output\":[]}}\n\n"));
        Capture capture = new Capture();
        provider.streamChat("gpt-6-astra", messages, "system", List.of(), 65_536,
                thinking, LlmCallContext.unscoped(), capture);
        assertThat(capture.error).isNull();
        assertThat(capture.completed).isTrue();
        var recorded = server.takeRequest();
        assertThat(recorded.getPath()).isEqualTo("/v1/responses");
        assertThat(recorded.getHeader("Authorization")).isEqualTo("Bearer test-key");
        return mapper.readTree(recorded.getBody().readUtf8());
    }

    private static final class Capture implements StreamChatCallback {
        Throwable error;
        boolean completed;
        @Override public void onEvent(LlmStreamEvent event) { }
        @Override public void onComplete() { completed = true; }
        @Override public void onError(Throwable error) { this.error = error; }
    }
}
