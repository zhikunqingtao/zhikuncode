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
                "test-provider",
                objectMapper,
                DEFAULT_HTTP_PROPS,
                new ApiKeyRotationManager("sk-test-key"),
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
                List.of("qwen3.7-plus", "qwen-max", "qwen-plus", "qwen-turbo"));

        for (String model : List.of("qwen3.7-plus", "qwen-max", "qwen-plus", "qwen-turbo")) {
            assertThrows(IllegalArgumentException.class,
                    () -> provider.getModelCapabilities(model),
                    model + " should not be in Provider MODEL_CAPABILITIES");
        }
    }

    @Test
    void testProviderConfiguration() {
        OpenAiCompatibleProvider provider = createProvider(
                List.of("qwen3.7-plus"));

        assertEquals("test-provider", provider.getProviderName());
        assertEquals("qwen3.7-plus", provider.getDefaultModel());
        assertTrue(provider.getSupportedModels().contains("qwen3.7-plus"));
    }

    @Test
    void testBailianDeepSeekModelCapabilities() {
        OpenAiCompatibleProvider provider = createProvider(
                List.of("deepseek-v4-pro-0813", "deepseek-v4-flash-0731"));

        ModelCapabilities pro = provider.getModelCapabilities("deepseek-v4-pro-0813");
        assertEquals("DeepSeek V4 Pro 0813（百炼）", pro.displayName());
        assertTrue(pro.supportsThinking());

        ModelCapabilities flash = provider.getModelCapabilities("deepseek-v4-flash-0731");
        assertEquals("DeepSeek V4 Flash 0731（百炼）", flash.displayName());
        assertTrue(flash.supportsThinking());
    }

    @Test
    void testDeepSeekVisionModelCapabilities() {
        OpenAiCompatibleProvider provider = createProvider(
                List.of("deepseek-v4-flash-vision-exp"));

        ModelCapabilities caps = provider.getModelCapabilities("deepseek-v4-flash-vision-exp");
        assertEquals("DeepSeek V4 Flash Vision Exp", caps.displayName());
        assertEquals(1_000_000, caps.contextWindow());
        assertEquals(384_000, caps.maxOutputTokens());
        assertTrue(caps.supportsThinking());
        assertTrue(caps.supportsImages());
        assertEquals(5, caps.maxImages());
        assertTrue(caps.supportsToolUse());
    }

    @Test
    void testModelRegistryBuiltinQwenModels() {
        // 验证 ModelRegistry.BUILTIN_MODELS 中千问模型 contextWindow 已更新为官方最新值
        // 由于 ModelRegistry 需要 LlmProviderRegistry，这里通过构造 mock 的 registry 来测试
        OpenAiCompatibleProvider provider = createProvider(
                List.of("qwen3.7-plus", "qwen3.8-max-0902", "qwen-turbo"));
        LlmProviderRegistry providerRegistry = new LlmProviderRegistry(List.of(provider), null);
        ModelRegistry modelRegistry = new ModelRegistry(providerRegistry);

        // 千问模型应通过 Level 2 抛异常 → fallback 到 Level 3 BUILTIN_MODELS
        assertEquals(1000000, modelRegistry.getCapabilities("qwen3.8-max-0902").contextWindow(),
                "qwen3.8-max-0902 contextWindow should be 1000000");
        assertEquals(1000000, modelRegistry.getCapabilities("qwen3.7-plus").contextWindow(),
                "qwen3.7-plus contextWindow should be 1000000 (official)");
        assertEquals(1000000, modelRegistry.getCapabilities("qwen-turbo").contextWindow(),
                "qwen-turbo contextWindow should be 1000000 (official)");

        ModelCapabilities qwen38 = modelRegistry.getCapabilities("qwen3.8-max");
        assertEquals("qwen3.8-max", qwen38.modelId());
        assertEquals("Qwen 3.8 Max（百炼）", qwen38.displayName());
        assertNotSame(ModelCapabilities.DEFAULT, qwen38);

        // qwen3.8-flash 官方规格（help.aliyun.com/zh/model-studio/qwen3-8-flash）
        ModelCapabilities qwen38Flash = modelRegistry.getCapabilities("qwen3.8-flash");
        assertEquals("qwen3.8-flash", qwen38Flash.modelId());
        assertEquals("Qwen 3.8 Flash（百炼）", qwen38Flash.displayName());
        assertNotSame(ModelCapabilities.DEFAULT, qwen38Flash);
        assertEquals(1_000_000, qwen38Flash.contextWindow(),
                "qwen3.8-flash contextWindow should be 1000000 (official)");
        assertEquals(131_072, qwen38Flash.maxOutputTokens(),
                "qwen3.8-flash maxOutputTokens should be 131072 (official)");
        assertTrue(qwen38Flash.supportsThinking());
        assertTrue(qwen38Flash.supportsImages());
        assertEquals(4, qwen38Flash.maxImages());
        assertTrue(qwen38Flash.supportsToolUse());
        assertTrue(qwen38Flash.supportsStreaming());

        ModelCapabilities deepseekPro = modelRegistry.getCapabilities("deepseek-v4-pro-0813");
        assertEquals("DeepSeek V4 Pro 0813（百炼）", deepseekPro.displayName());
        assertTrue(deepseekPro.supportsThinking());

        ModelCapabilities deepseekFlash = modelRegistry.getCapabilities("deepseek-v4-flash-0731");
        assertEquals("DeepSeek V4 Flash 0731（百炼）", deepseekFlash.displayName());
        assertTrue(deepseekFlash.supportsThinking());

        ModelCapabilities deepseekVision = modelRegistry.getCapabilities("deepseek-v4-flash-vision-exp");
        assertEquals("DeepSeek V4 Flash Vision Exp", deepseekVision.displayName());
        assertTrue(deepseekVision.supportsImages());
        assertEquals(384_000, deepseekVision.maxOutputTokens());

        ModelCapabilities glm53 = modelRegistry.getCapabilities("glm-5.3");
        assertEquals("GLM-5.3", glm53.displayName());
        assertNotSame(ModelCapabilities.DEFAULT, glm53);
    }
}
