package com.aicodeassistant.llm;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;

class ZenMuxModelCatalogTest {

    @Test
    void registersConfirmedModelsWithExactCapabilities() {
        LlmProviderRegistry providers = mock(LlmProviderRegistry.class);
        when(providers.getProvider(anyString()))
                .thenThrow(new IllegalArgumentException("not configured"));
        ModelRegistry registry = new ModelRegistry(providers);

        assertCaps(registry, "anthropic/claude-fable-5.1", 64_000, 1_000_000,
                false, 5, .010, .050);
        assertThat(registry.getCapabilities("anthropic/claude-fable-5.1").supportsCache())
                .isTrue();
        assertCaps(registry, "anthropic/claude-opus-5.5", 64_000, 1_000_000,
                false, 5, .004, .020);
        assertCaps(registry, "anthropic/claude-sonnet-5.5", 64_000, 1_000_000,
                false, 5, .002, .010);
        assertCaps(registry, "anthropic/claude-haiku-5.5", 64_000, 1_000_000,
                false, 5, .0001, .0005);
        assertThat(registry.getCapabilities("anthropic/claude-opus-5.5").supportsCache())
                .isTrue();
        assertThat(registry.getCapabilities("anthropic/claude-sonnet-5.5").supportsCache())
                .isTrue();
        assertThat(registry.getCapabilities("anthropic/claude-haiku-5.5").supportsCache())
                .isTrue();
        assertCaps(registry, "openai/gpt-6-astra", 128_000, 1_050_000,
                true, 4, .010, .050);
        assertCaps(registry, "google/gemini-4-argon", 65_536, 1_048_576,
                true, 4, .002, .010);
        assertCaps(registry, "x-ai/grok-4.7", 65_536, 500_000,
                true, 4, .002, .006);
        assertCaps(registry, "meta/muse-wonder-1.0", 128_000, 1_048_576,
                true, 4, .004, .020);

        assertThat(registry.getCapabilities("openai/gpt-5.6-sol").supportsThinking())
                .isTrue();
    }

    @Test
    void applicationYamlPublishesCurrentZenMuxDefaultCatalog() throws Exception {
        MockEnvironment env = new MockEnvironment();
        for (var source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))) {
            env.getPropertySources().addLast(source);
        }
        LlmProvidersProperties configs = Binder.get(env)
                .bind("llm", Bindable.of(LlmProvidersProperties.class)).get();

        assertThat(configs.providers().get("zenmux").models())
                .contains("google/gemini-4-argon", "meta/muse-wonder-1.0", "x-ai/grok-4.7")
                .doesNotContain("google/gemini-3.8-flash", "x-ai/grok-4.6");
    }

    private static void assertCaps(ModelRegistry registry, String id,
                                   int output, int context, boolean thinking,
                                   int images, double inputCost, double outputCost) {
        ModelCapabilities caps = registry.getCapabilities(id);
        assertThat(caps.modelId()).isEqualTo(id);
        assertThat(caps.maxOutputTokens()).isEqualTo(output);
        assertThat(caps.contextWindow()).isEqualTo(context);
        assertThat(caps.supportsStreaming()).isTrue();
        assertThat(caps.supportsThinking()).isEqualTo(thinking);
        assertThat(caps.supportsImages()).isTrue();
        assertThat(caps.maxImages()).isEqualTo(images);
        assertThat(caps.supportsToolUse()).isTrue();
        assertThat(caps.tokenCharRatio()).isEqualTo(3.5);
        assertThat(caps.costPer1kInput()).isEqualTo(inputCost);
        assertThat(caps.costPer1kOutput()).isEqualTo(outputCost);
    }
}
