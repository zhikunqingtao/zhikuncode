package com.aicodeassistant.llm;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmProviderRegistryModelContractTest {

    @Test
    void fallsBackFromUnavailableConfiguredDefaultToProviderDefault() {
        LlmProvider provider = provider(
                List.of("available-first", "available-default"),
                "available-default");
        LlmProviderRegistry registry = new LlmProviderRegistry(
                List.of(provider), mock(Environment.class));
        ReflectionTestUtils.setField(registry, "configuredDefaultModel", "retired-model");

        assertThat(registry.supportsModel("available-default")).isTrue();
        assertThat(registry.supportsModel("retired-model")).isFalse();
        assertThat(registry.getDefaultModel()).isEqualTo("available-default");
        assertThat(registry.listAvailableModels())
                .containsExactly("available-default", "available-first");
    }

    @Test
    void honorsConfiguredDefaultOnlyWhenProviderSupportsIt() {
        LlmProvider provider = provider(List.of("model-a", "model-b"), "model-b");
        LlmProviderRegistry registry = new LlmProviderRegistry(
                List.of(provider), mock(Environment.class));
        ReflectionTestUtils.setField(registry, "configuredDefaultModel", "model-a");

        assertThat(registry.getDefaultModel()).isEqualTo("model-a");
    }

    @Test
    void invalidTierAliasTargetFallsBackToEffectiveDefault() {
        LlmProvider provider = provider(List.of("available-model"), "available-model");
        Environment environment = mock(Environment.class);
        when(environment.getProperty("agent.model-aliases.light"))
                .thenReturn("retired-fast-model");
        LlmProviderRegistry registry = new LlmProviderRegistry(
                List.of(provider), environment);
        ReflectionTestUtils.setField(registry, "configuredDefaultModel", "retired-default");

        assertThat(registry.resolveModelAlias("light"))
                .isEqualTo("available-model");
    }

    @Test
    void directModelIdPreservesLegacyPassthrough() {
        LlmProvider provider = provider(List.of("available-model"), "available-model");
        LlmProviderRegistry registry = new LlmProviderRegistry(
                List.of(provider), mock(Environment.class));

        assertThat(registry.resolveModelAlias("available-model"))
                .isEqualTo("available-model");
        assertThat(registry.resolveModelAlias("unknown-model"))
                .isEqualTo("unknown-model");
    }

    private static LlmProvider provider(List<String> models, String defaultModel) {
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.getProviderName()).thenReturn("test-provider");
        when(provider.getSupportedModels()).thenReturn(models);
        when(provider.getDefaultModel()).thenReturn(defaultModel);
        return provider;
    }
}
