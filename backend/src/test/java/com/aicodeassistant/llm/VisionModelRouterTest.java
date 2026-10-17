package com.aicodeassistant.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VisionModelRouterTest {

    private final LlmProviderRegistry providerRegistry = mock(LlmProviderRegistry.class);
    private final ModelRegistry modelRegistry = mock(ModelRegistry.class);
    private final VisionModelRouter router = new VisionModelRouter(providerRegistry, modelRegistry);

    @Test
    void textOnlyTokenPlanModelFallsBackToAvailableDeepSeekVisionModel() {
        configureDeepSeekVisionAvailable();
        when(modelRegistry.getCapabilities("bailian/glm-5.3"))
                .thenReturn(textCaps("bailian/glm-5.3"));
        when(modelRegistry.getCapabilities("qwen3.8-max"))
                .thenReturn(textCaps("qwen3.8-max"));
        when(providerRegistry.listAvailableModels())
                .thenReturn(List.of(VisionModelRouter.DEEPSEEK_VISION_MODEL));

        LlmProvider tokenPlanProvider = mock(LlmProvider.class);
        when(tokenPlanProvider.getSupportedModels())
                .thenReturn(List.of("qwen3.8-max", "bailian/glm-5.3"));
        when(providerRegistry.getProvider("bailian/glm-5.3")).thenReturn(tokenPlanProvider);

        assertEquals(VisionModelRouter.DEEPSEEK_VISION_MODEL,
                router.resolveVisionModel("bailian/glm-5.3"));
    }

    @Test
    void unavailableDeepSeekVisionFallsBackToSameProviderVisionModel() {
        when(modelRegistry.getCapabilities("bailian/glm-5.3"))
                .thenReturn(textCaps("bailian/glm-5.3"));
        when(modelRegistry.getCapabilities(VisionModelRouter.DEEPSEEK_VISION_MODEL))
                .thenReturn(imageCaps(VisionModelRouter.DEEPSEEK_VISION_MODEL));
        when(providerRegistry.getProvider(VisionModelRouter.DEEPSEEK_VISION_MODEL))
                .thenThrow(new IllegalArgumentException("not configured"));

        LlmProvider tokenPlanProvider = mock(LlmProvider.class);
        when(tokenPlanProvider.getSupportedModels())
                .thenReturn(List.of("qwen3.8-max", "bailian/glm-5.3"));
        when(providerRegistry.getProvider("bailian/glm-5.3")).thenReturn(tokenPlanProvider);
        when(modelRegistry.getCapabilities("qwen3.8-max")).thenReturn(imageCaps("qwen3.8-max"));

        assertEquals("qwen3.8-max", router.resolveVisionModel("bailian/glm-5.3"));
    }

    @Test
    void imageCapableCurrentModelDoesNotRoute() {
        when(modelRegistry.getCapabilities(VisionModelRouter.DEEPSEEK_VISION_MODEL))
                .thenReturn(imageCaps(VisionModelRouter.DEEPSEEK_VISION_MODEL));

        assertNull(router.resolveVisionModel(VisionModelRouter.DEEPSEEK_VISION_MODEL));
    }

    @Test
    void configuredBailianFallbackTakesPriorityOverDirectDeepSeekAndSameProvider() {
        configureDeepSeekVisionAvailable();
        when(modelRegistry.getCapabilities("bailian/glm-5.3"))
                .thenReturn(textCaps("bailian/glm-5.3"));
        when(modelRegistry.getCapabilities("deepseek-v4.1-flash"))
                .thenReturn(imageCaps("deepseek-v4.1-flash"));
        when(providerRegistry.getProvider("deepseek-v4.1-flash")).thenReturn(mock(LlmProvider.class));
        assertEquals("deepseek-v4.1-flash", router.resolveVisionModel("bailian/glm-5.3"));
    }

    @Test
    void configuredFallbackAlsoHandlesOtherTextOnlyModelFamilies() {
        ReflectionTestUtils.setField(router, "fallbackVisionModel", "custom-vision");
        when(modelRegistry.getCapabilities("text-model")).thenReturn(textCaps("text-model"));
        when(modelRegistry.getCapabilities("custom-vision")).thenReturn(imageCaps("custom-vision"));
        when(providerRegistry.getProvider("custom-vision")).thenReturn(mock(LlmProvider.class));
        assertEquals("custom-vision", router.resolveVisionModel("text-model"));
    }

    @Test
    void bailianCurrentModelAlreadySupportsImages() {
        when(modelRegistry.getCapabilities("deepseek-v4.1-flash"))
                .thenReturn(imageCaps("deepseek-v4.1-flash"));
        assertNull(router.resolveVisionModel("deepseek-v4.1-flash"));
    }

    @Test
    void deepseekFamilyTextOnlyModelRoutesToFamilyVisionModelWithoutConfiguredFallback() {
        // 家族分支专用覆盖：deepseek- 前缀的 synthetic 文本模型 + deepseek-flash 可用，
        // 且 fallbackVisionModel（deepseek-v4.1-flash）未 mock 能力 → 配置兜底分支不可用
        // （getCapabilities 返回 null → isAvailableVisionModel=false）。
        configureDeepSeekVisionAvailable();
        when(modelRegistry.getCapabilities("deepseek-text-synthetic"))
                .thenReturn(textCaps("deepseek-text-synthetic"));
        // 同一套 mock 下的非 deepseek- 前缀对照：家族分支不适用，而未 mock 的
        // getProvider(currentModel) 返回 null、listAvailableModels() 返回空列表，
        // 说明除家族分支外没有任何路径能返回 deepseek-flash。
        when(modelRegistry.getCapabilities("plain-text-synthetic"))
                .thenReturn(textCaps("plain-text-synthetic"));

        assertEquals(VisionModelRouter.DEEPSEEK_VISION_MODEL,
                router.resolveVisionModel("deepseek-text-synthetic"));
        assertNull(router.resolveVisionModel("plain-text-synthetic"));
    }

    @Test
    void findsAvailableGlobalModelInsteadOfReturningAnUnconfiguredFallback() {
        when(modelRegistry.getCapabilities("text-model")).thenReturn(textCaps("text-model"));
        when(providerRegistry.listAvailableModels()).thenReturn(List.of("other-vision"));
        when(modelRegistry.getCapabilities("other-vision")).thenReturn(imageCaps("other-vision"));
        when(providerRegistry.getProvider("other-vision")).thenReturn(mock(LlmProvider.class));
        assertEquals("other-vision", router.resolveVisionModel("text-model"));
    }

    @Test
    void returnsNoTargetWhenNoVisionProviderIsConfigured() {
        when(modelRegistry.getCapabilities("text-model")).thenReturn(textCaps("text-model"));
        assertNull(router.resolveVisionModel("text-model"));
    }

    private void configureDeepSeekVisionAvailable() {
        when(modelRegistry.getCapabilities(VisionModelRouter.DEEPSEEK_VISION_MODEL))
                .thenReturn(imageCaps(VisionModelRouter.DEEPSEEK_VISION_MODEL));
        when(providerRegistry.getProvider(VisionModelRouter.DEEPSEEK_VISION_MODEL))
                .thenReturn(mock(LlmProvider.class));
    }

    private static ModelCapabilities textCaps(String model) {
        return new ModelCapabilities(model, model, 8_192, 128_000,
                true, true, false, 0, true, 0.001, 0.002);
    }

    private static ModelCapabilities imageCaps(String model) {
        return new ModelCapabilities(model, model, 8_192, 128_000,
                true, true, true, 5, true, 0.001, 0.002);
    }
}
