package com.aicodeassistant.llm;

import com.aicodeassistant.controller.ModelController;
import com.aicodeassistant.llm.impl.OpenAiCompatibleProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenRouterIntegrationTest {
    private static final String MODEL = "stealth/union-alpha";

    @Test
    void configuredProviderRoutesUnionAndPublishesCapabilitiesToFrontend() throws Exception {
        var config = config("test-openrouter-key");
        assertThat(config.baseUrl()).isEqualTo("https://openrouter.ai/api/v1");
        assertThat(config.defaultModel()).isEqualTo(MODEL);
        var providers = providers(config);
        assertThat(providers).hasSize(1);
        assertThat(providers.getFirst()).isInstanceOf(OpenAiCompatibleProvider.class);
        var registry = new LlmProviderRegistry(providers, new MockEnvironment());
        assertThat(registry.getProvider(MODEL).getProviderName()).isEqualTo("openrouter");
        assertThat(registry.getProvider(MODEL).supportsThinking(MODEL)).isFalse();
        var response = new ModelController(registry, new ModelRegistry(registry))
                .listModels(null).getBody();
        assertThat(response).isNotNull();
        assertThat(response.defaultModel()).isEqualTo(MODEL);
        assertThat(response.models()).filteredOn(model -> MODEL.equals(model.id())).singleElement().satisfies(model -> {
            assertThat(model.id()).isEqualTo(MODEL);
            assertThat(model.displayName()).isEqualTo("Union Alpha（OpenRouter）");
            assertThat(model.contextWindow()).isEqualTo(262144);
            assertThat(model.maxOutputTokens()).isEqualTo(131072);
            assertThat(model.supportsImages()).isTrue();
            assertThat(model.maxImages()).isEqualTo(4);
            assertThat(model.supportsStreaming()).isTrue();
            assertThat(model.supportsToolUse()).isTrue();
            assertThat(model.supportsThinking()).isFalse();
            assertThat(model.costPer1kInput()).isZero();
            assertThat(model.costPer1kOutput()).isZero();
        });
    }

    @Test
    void emptyKeyDoesNotExposeOpenRouterModel() throws Exception {
        assertThat(providers(config(""))).isEmpty();
    }

    private LlmProvidersProperties.ProviderConfig config(String key) throws Exception {
        var environment = new MockEnvironment().withProperty("LLM_PROVIDER_OPENROUTER_API_KEY", key);
        for (var source : new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(source);
        }
        return Binder.get(environment).bind("llm.providers.openrouter",
                Bindable.of(LlmProvidersProperties.ProviderConfig.class)).get();
    }

    private List<LlmProvider> providers(LlmProvidersProperties.ProviderConfig config) {
        return new MultiProviderConfiguration().openAiCompatibleProviders(
                new LlmProvidersProperties(Map.of("openrouter", config)),
                new LlmHttpProperties(new LlmHttpProperties.PoolProperties(2, 30), 10, 10, true),
                new ObjectMapper(), "", "https://api.openai.com/v1", "unused", List.of(), null);
    }

    @Test
    void namespacedRoutesDoNotStealZenMuxModelsRegardlessOfRegistrationOrder() throws Exception {
        var router = providers(config("key")).getFirst();
        var zenmux = new OpenAiCompatibleProvider("zenmux", new ObjectMapper(),
                new LlmHttpProperties(new LlmHttpProperties.PoolProperties(2, 30), 10, 10, true),
                new ApiKeyRotationManager("key"), "key", "https://zenmux.ai/api/v1",
                "openai/gpt-6-astra", List.of("openai/gpt-6-astra", "anthropic/claude-fable-5.1"));
        for (var order : List.of(List.of(router, zenmux), List.of(zenmux, router))) {
            var registry = new LlmProviderRegistry(order, new MockEnvironment());
            var models = new ModelRegistry(registry);
            assertThat(registry.getProvider(OpenRouterModels.ASTRA)).isSameAs(router);
            assertThat(registry.getProvider(OpenRouterModels.FABLE)).isSameAs(router);
            assertThat(registry.getProvider("openai/gpt-6-astra")).isSameAs(zenmux);
            assertThat(registry.getProvider("anthropic/claude-fable-5.1")).isSameAs(zenmux);
            assertThat(models.getContextWindowForModel(OpenRouterModels.ASTRA)).isEqualTo(1050000);
            assertThat(models.getContextWindowForModel(OpenRouterModels.FABLE)).isEqualTo(1000000);
            assertThat(models.getMaxOutputTokensForModel(OpenRouterModels.FABLE)).isEqualTo(128000);
            assertThat(models.getCapabilities(OpenRouterModels.FABLE).supportsThinking()).isTrue();
            assertThat(registry.listAvailableModels()).hasSize(5);
        }
    }

    @Test
    void rawOpenRouterConfigurationIsNormalizedBeforeRegistration() {
        var config = new LlmProvidersProperties.ProviderConfig("key", "https://openrouter.ai/api/v1",
                "openai/gpt-6-astra", List.of("openai/gpt-6-astra", OpenRouterModels.ASTRA,
                "anthropic/claude-fable-5.1"));
        var provider = providers(config).getFirst();
        assertThat(provider.getSupportedModels()).containsExactly(OpenRouterModels.ASTRA, OpenRouterModels.FABLE);
        assertThat(provider.getDefaultModel()).isEqualTo(OpenRouterModels.ASTRA);
    }
}
