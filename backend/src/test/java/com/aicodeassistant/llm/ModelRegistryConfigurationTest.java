package com.aicodeassistant.llm;

import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.engine.TokenCounter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ModelRegistryConfigurationTest {
    @Test
    void yamlOverrideWinsOverProviderAndCarriesEveryConfiguredCapability() {
        LlmProviderRegistry providers = new LlmProviderRegistry(List.of(provider()), new MockEnvironment());
        ModelCapabilitiesProperties properties = new ModelCapabilitiesProperties();
        ModelCapabilitiesProperties.Override override = new ModelCapabilitiesProperties.Override();
        override.setContextWindow(400_000);
        override.setOutputMaxTokens(64_000);
        override.setTokenCharRatio(2.5);
        override.setSupportsCache(true);
        override.setSupportsToolUse(false);
        override.setSupportsVision(true);
        override.setSupportsStreaming(false);
        properties.setCapabilities(new LinkedHashMap<>(Map.of("configured", override)));

        ModelCapabilities configured = new ModelRegistry(providers, properties).getCapabilities("configured");
        assertEquals(400_000, configured.contextWindow());
        assertEquals(64_000, configured.maxOutputTokens());
        assertEquals(2.5, configured.tokenCharRatio());
        assertTrue(configured.supportsCache());
        assertFalse(configured.supportsToolUse());
        assertTrue(configured.supportsImages());
        assertFalse(configured.supportsStreaming());
    }

    @Test
    void invalidConfiguredRatioFailsAtRegistryStartup() {
        ModelCapabilitiesProperties properties = new ModelCapabilitiesProperties();
        ModelCapabilitiesProperties.Override override = new ModelCapabilitiesProperties.Override();
        override.setTokenCharRatio(0.1);
        properties.setCapabilities(Map.of("configured", override));
        assertThrows(IllegalArgumentException.class,
                () -> new ModelRegistry(new LlmProviderRegistry(List.of(provider()), new MockEnvironment()), properties));
    }

    @Test
    void qwenAndDeepseekRatiosChangeTokenEstimates() {
        ModelCapabilitiesProperties properties = new ModelCapabilitiesProperties();
        ModelCapabilitiesProperties.Override qwen = new ModelCapabilitiesProperties.Override();
        qwen.setTokenCharRatio(2.5);
        ModelCapabilitiesProperties.Override deepseek = new ModelCapabilitiesProperties.Override();
        deepseek.setTokenCharRatio(2.8);
        properties.setCapabilities(Map.of("qwen3.7-max", qwen, "deepseek-coder", deepseek));
        ModelRegistry registry = new ModelRegistry(
                new LlmProviderRegistry(List.of(), new MockEnvironment()), properties);
        TokenCounter counter = new TokenCounter(registry, null, new FeatureFlagService());
        String text = "a".repeat(350);

        assertEquals(140, counter.estimateTokensForModel(text, "qwen3.7-max"));
        assertEquals(125, counter.estimateTokensForModel(text, "deepseek-coder"));
        assertEquals(100, counter.estimateTokensForModel(text, "unknown-model"));
    }

    @Test
    void providerReturningNullFallsBackToConservativeCapabilities() {
        LlmProvider nullProvider = new LlmProvider() {
            @Override public String getProviderName() { return "null-provider"; }
            @Override public List<String> getSupportedModels() { return List.of("broken-model"); }
            @Override public void streamChat(String model, List<Map<String, Object>> messages,
                                             String systemPrompt, List<Map<String, Object>> tools,
                                             int maxTokens, ThinkingConfig thinkingConfig,
                                             LlmCallContext context, StreamChatCallback callback) {
                throw new UnsupportedOperationException();
            }
            @Override public String getDefaultModel() { return "broken-model"; }
            @Override public ModelCapabilities getModelCapabilities(String model) { return null; }
        };

        ModelRegistry registry = new ModelRegistry(
                new LlmProviderRegistry(List.of(nullProvider), new MockEnvironment()));

        assertSame(ModelCapabilities.DEFAULT, registry.getCapabilities("broken-model"));
        assertFalse(registry.isKnownModel("broken-model"));
    }

    private static LlmProvider provider() {
        return new LlmProvider() {
            @Override public String getProviderName() { return "test"; }
            @Override public List<String> getSupportedModels() { return List.of("configured"); }
            @Override public void streamChat(String model, List<Map<String, Object>> messages, String systemPrompt,
                                             List<Map<String, Object>> tools, int maxTokens,
                                             ThinkingConfig thinkingConfig, LlmCallContext context,
                                             StreamChatCallback callback) { throw new UnsupportedOperationException(); }
            @Override public String getDefaultModel() { return "configured"; }
            @Override public ModelCapabilities getModelCapabilities(String model) {
                return new ModelCapabilities(model, model, 8_000, 100_000,
                        true, false, false, 0, true, 0, 0, 4.4, false);
            }
        };
    }
}
