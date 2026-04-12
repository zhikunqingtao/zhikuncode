package com.aicodeassistant.llm;

import com.aicodeassistant.llm.impl.OpenAiCompatibleProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 阿里云百炼配置验证测试 — 无需真实 API 调用，仅验证配置正确性。
 * <p>
 * 千问模型能力已迁移至 ModelRegistry.BUILTIN_MODELS，
 * Provider 中仅保留 qwen-coder-plus。
 *
 * @see OpenAiCompatibleProvider
 */
class AliyunConfigVerificationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final LlmHttpProperties DEFAULT_HTTP_PROPS = new LlmHttpProperties(
            new LlmHttpProperties.PoolProperties(5, 30), 10, 10, true);

    private OpenAiCompatibleProvider createProvider(List<String> models) {
        return new OpenAiCompatibleProvider(
                objectMapper,
                DEFAULT_HTTP_PROPS,
                new ApiKeyRotationManager(List.of(), "sk-test-key"),
                "sk-test-key",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                models.get(0),
                models
        );
    }

    @Test
    void testQwenCoderPlusModelCapabilities() {
        // qwen-coder-plus 仍在 Provider MODEL_CAPABILITIES 中
        OpenAiCompatibleProvider provider = createProvider(
                List.of("qwen-coder-plus"));

        ModelCapabilities caps = provider.getModelCapabilities("qwen-coder-plus");
        assertNotNull(caps);
        assertEquals("qwen-coder-plus", caps.modelId());
        assertTrue(caps.supportsStreaming());
        assertTrue(caps.supportsToolUse());
        assertFalse(caps.supportsImages());
    }

    @Test
    void testQwenModelsRemovedFromProvider() {
        // 千问主力模型已从 Provider 迁移至 ModelRegistry.BUILTIN_MODELS
        // Provider.getModelCapabilities() 应抛出 IllegalArgumentException
        OpenAiCompatibleProvider provider = createProvider(
                List.of("qwen3.6-plus", "qwen-max", "qwen-plus", "qwen-turbo"));

        for (String model : List.of("qwen3.6-plus", "qwen-max", "qwen-plus", "qwen-turbo")) {
            assertThrows(IllegalArgumentException.class,
                    () -> provider.getModelCapabilities(model),
                    model + " should not be in Provider MODEL_CAPABILITIES");
        }
    }

    @Test
    void testProviderConfiguration() {
        OpenAiCompatibleProvider provider = createProvider(
                List.of("qwen3.6-plus"));

        assertEquals("openai-compatible", provider.getProviderName());
        assertEquals("qwen3.6-plus", provider.getDefaultModel());
        assertTrue(provider.getSupportedModels().contains("qwen3.6-plus"));
    }

    @Test
    void testModelRegistryBuiltinQwenModels() {
        // 验证 ModelRegistry.BUILTIN_MODELS 中千问模型 contextWindow 已更新为官方最新值
        // 由于 ModelRegistry 需要 LlmProviderRegistry，这里通过构造 mock 的 registry 来测试
        OpenAiCompatibleProvider provider = createProvider(
                List.of("qwen3.6-plus", "qwen-max", "qwen-plus", "qwen-turbo"));
        LlmProviderRegistry providerRegistry = new LlmProviderRegistry(List.of(provider));
        ModelRegistry modelRegistry = new ModelRegistry(providerRegistry);

        // 千问模型应通过 Level 2 抛异常 → fallback 到 Level 3 BUILTIN_MODELS
        assertEquals(262144, modelRegistry.getCapabilities("qwen-max").contextWindow(),
                "qwen-max contextWindow should be 262144 (official)");
        assertEquals(1000000, modelRegistry.getCapabilities("qwen-plus").contextWindow(),
                "qwen-plus contextWindow should be 1000000 (official)");
        assertEquals(1000000, modelRegistry.getCapabilities("qwen-turbo").contextWindow(),
                "qwen-turbo contextWindow should be 1000000 (official)");
        assertEquals(1000000, modelRegistry.getCapabilities("qwen3.6-plus").contextWindow(),
                "qwen3.6-plus contextWindow should be 1000000 (official)");
    }
}
