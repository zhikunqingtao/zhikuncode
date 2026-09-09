package com.aicodeassistant.llm;

import org.junit.jupiter.api.Test;

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
        assertCaps(registry, "openai/gpt-6-astra", 128_000, 1_050_000,
                true, 4, .010, .050);
        assertCaps(registry, "google/gemini-3.8-flash", 65_536, 1_048_576,
                true, 4, .0015, .0075);
        assertCaps(registry, "x-ai/grok-4.6", 65_536, 500_000,
                true, 4, .004, .012);

        assertThat(registry.getCapabilities("openai/gpt-5.6-sol").supportsThinking())
                .isTrue();
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
