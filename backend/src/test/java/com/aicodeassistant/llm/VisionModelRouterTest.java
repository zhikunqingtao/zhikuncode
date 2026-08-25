package com.aicodeassistant.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class VisionModelRouterTest {

    private final LlmProviderRegistry providerRegistry = mock(LlmProviderRegistry.class);
    private final ModelRegistry modelRegistry = mock(ModelRegistry.class);
    private final VisionModelRouter router = new VisionModelRouter(providerRegistry, modelRegistry);

    @Test
    void directDeepSeekTextModelUsesDeepSeekVisionFallback() {
        configureDeepSeekVisionAvailable();
        when(modelRegistry.getCapabilities("deepseek-v4-pro")).thenReturn(textCaps("deepseek-v4-pro"));

        assertEquals(VisionModelRouter.DEEPSEEK_VISION_MODEL,
                router.resolveVisionModel("deepseek-v4-pro"));
    }

    @Test
    void bailianDeepSeekModelStillUsesDirectDeepSeekVisionFallback() {
        configureDeepSeekVisionAvailable();
        when(modelRegistry.getCapabilities("deepseek-v4-pro-0813"))
                .thenReturn(textCaps("deepseek-v4-pro-0813"));

        LlmProvider tokenPlanProvider = mock(LlmProvider.class);
        when(tokenPlanProvider.getSupportedModels())
                .thenReturn(List.of("qwen3.8-max", "deepseek-v4-pro-0813"));
        when(providerRegistry.getProvider("deepseek-v4-pro-0813")).thenReturn(tokenPlanProvider);

        assertEquals(VisionModelRouter.DEEPSEEK_VISION_MODEL,
                router.resolveVisionModel("deepseek-v4-pro-0813"));
    }

    @Test
    void unavailableDeepSeekVisionFallsBackToSameProviderVisionModel() {
        when(modelRegistry.getCapabilities("deepseek-v4-flash-0731"))
                .thenReturn(textCaps("deepseek-v4-flash-0731"));
        when(modelRegistry.getCapabilities(VisionModelRouter.DEEPSEEK_VISION_MODEL))
                .thenReturn(imageCaps(VisionModelRouter.DEEPSEEK_VISION_MODEL));
        when(providerRegistry.getProvider(VisionModelRouter.DEEPSEEK_VISION_MODEL))
                .thenThrow(new IllegalArgumentException("not configured"));

        LlmProvider tokenPlanProvider = mock(LlmProvider.class);
        when(tokenPlanProvider.getSupportedModels())
                .thenReturn(List.of("qwen3.8-max", "deepseek-v4-flash-0731"));
        when(providerRegistry.getProvider("deepseek-v4-flash-0731")).thenReturn(tokenPlanProvider);
        when(modelRegistry.getCapabilities("qwen3.8-max")).thenReturn(imageCaps("qwen3.8-max"));

        assertEquals("qwen3.8-max", router.resolveVisionModel("deepseek-v4-flash-0731"));
    }

    @Test
    void imageCapableCurrentModelDoesNotRoute() {
        when(modelRegistry.getCapabilities(VisionModelRouter.DEEPSEEK_VISION_MODEL))
                .thenReturn(imageCaps(VisionModelRouter.DEEPSEEK_VISION_MODEL));

        assertNull(router.resolveVisionModel(VisionModelRouter.DEEPSEEK_VISION_MODEL));
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
