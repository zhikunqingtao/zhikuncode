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
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BailianDeepSeekConfigurationTest {
    private static final String BAILIAN = "deepseek-v4.1-flash";

    @Test
    void actualYamlDefaultsRouteBailianAndDirectDeepSeekIndependently() throws Exception {
        MockEnvironment env = new MockEnvironment()
                .withProperty("LLM_PROVIDER_DASHSCOPE_TOKEN_PLAN_API_KEY", "test-token-plan")
                .withProperty("LLM_PROVIDER_DEEPSEEK_API_KEY", "test-direct");
        for (var source : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))) {
            env.getPropertySources().addLast(source);
        }
        LlmProvidersProperties configs = Binder.get(env).bind("llm", Bindable.of(LlmProvidersProperties.class)).get();
        var bailianConfig = configs.providers().get("dashscope-token-plan");
        var directConfig = configs.providers().get("deepseek");
        assertEquals(BAILIAN, env.getProperty("app.model.default"));
        assertEquals(BAILIAN, env.getProperty("app.compact.model"));
        assertEquals(BAILIAN, env.getProperty("app.model.vision-fallback"));
        assertEquals(BAILIAN, env.getProperty("app.model.fast"));
        assertEquals(BAILIAN, env.getProperty("app.model.tier-chain[0]"));
        assertFalse(configs.providers().values().stream().anyMatch(p -> p.models().contains("qwen3.7-plus")));
        assertEquals(BAILIAN, bailianConfig.defaultModel());
        assertTrue(bailianConfig.baseUrl().contains("token-plan.cn-beijing.maas.aliyuncs.com"));
        assertEquals("https://api.deepseek.com/v1", directConfig.baseUrl());
        assertEquals("test-token-plan", bailianConfig.apiKey());
        assertEquals("test-direct", directConfig.apiKey());

        var registry = new LlmProviderRegistry(List.of(provider("deepseek", directConfig),
                provider("dashscope-token-plan", bailianConfig)), env);
        ReflectionTestUtils.setField(registry, "configuredDefaultModel", env.getProperty("app.model.default"));
        assertEquals("dashscope-token-plan", registry.getProvider(BAILIAN).getProviderName());
        assertEquals("deepseek", registry.getProvider("deepseek-flash").getProviderName());
        assertEquals(BAILIAN, registry.listAvailableModels().getFirst());
        // Direct DeepSeek is registered first; global fast selection must still use Bailian.
        assertEquals(BAILIAN, registry.getFastModel());
        assertEquals(BAILIAN, registry.getLightweightModel());
        assertEquals(BAILIAN, registry.resolveClassifierModel());
        assertFalse(registry.supportsModel("qwen3.7-plus"));
        var models = new ModelRegistry(registry);
        assertFalse(models.isKnownModel("qwen3.7-plus"));
        assertEquals(ModelCapabilities.DEFAULT, models.getCapabilities("qwen3.7-plus"));
        ModelCapabilities caps = models.getCapabilities(BAILIAN);
        assertEquals(393216, caps.maxOutputTokens());
        assertEquals(1000000, caps.contextWindow());
        assertEquals(4, caps.maxImages());
        assertTrue(caps.supportsImages() && caps.supportsThinking() && caps.supportsToolUse());
        assertEquals(600, models.getCapabilities("deepseek-flash").maxImages());
        assertEquals(BAILIAN, new VisionModelRouter(registry, models).resolveVisionModel("deepseek-v4-pro-0813"));
        var response = new ModelController(registry, models).listModels(null).getBody();
        assertNotNull(response);
        assertEquals(BAILIAN, response.defaultModel());
        assertEquals("DeepSeek V4.1 Flash（百炼）", response.models().getFirst().displayName());
        ReflectionTestUtils.setField(registry, "configuredFastModel", "deepseek-flash");
        assertEquals("deepseek-flash", registry.getFastModel());
    }

    private OpenAiCompatibleProvider provider(String name, LlmProvidersProperties.ProviderConfig config) {
        return new OpenAiCompatibleProvider(name, new ObjectMapper(),
                new LlmHttpProperties(new LlmHttpProperties.PoolProperties(2, 30), 10, 10, true),
                new ApiKeyRotationManager(config.apiKey()), config.apiKey(), config.baseUrl(),
                config.defaultModel(), config.models());
    }
}
