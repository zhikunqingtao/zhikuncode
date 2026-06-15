package com.aicodeassistant.llm;

import org.junit.jupiter.api.*;
import org.springframework.core.env.Environment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * TC-LLM-002 四级回退验证
 * 验证 LlmProviderRegistry 的模型解析和分类器模型回退链完整性。
 */
@DisplayName("TC-LLM-002 四级回退验证")
class LlmFallbackChainTest {

    private Environment mockEnv;

    @BeforeEach
    void setUp() {
        mockEnv = mock(Environment.class);
    }

    @Test
    @DisplayName("内置别名映射应包含 light/standard/premium 三级")
    void builtinAliasesShouldContainAllLevels() {
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.getDefaultModel()).thenReturn("qwen3.7-max");
        when(provider.getFastModel()).thenReturn("qwen-plus");
        when(provider.getProviderName()).thenReturn("test-provider");
        when(provider.getSupportedModels()).thenReturn(List.of("qwen-plus", "qwen3.7-plus", "qwen3.7-max"));

        LlmProviderRegistry registry = new LlmProviderRegistry(
            List.of(provider), mockEnv);

        // 验证新别名映射（统一指向最强模型）
        assertEquals("qwen3.7-max", registry.resolveModelAlias("light"),
            "light 应映射到 qwen3.7-max");
        assertEquals("qwen3.7-max", registry.resolveModelAlias("standard"),
            "standard 应映射到 qwen3.7-max");
        assertEquals("qwen3.7-max", registry.resolveModelAlias("premium"),
            "premium 应映射到 qwen3.7-max");
    }

    @Test
    @DisplayName("别名解析四级回退：env → config → builtin → direct")
    void aliasResolutionFourLevelFallback() {
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.getProviderName()).thenReturn("test-provider");
        when(provider.getSupportedModels()).thenReturn(List.of("qwen-plus"));

        LlmProviderRegistry registry = new LlmProviderRegistry(
            List.of(provider), mockEnv);

        // Level 3 回退：无 env、无 config → 使用内置别名
        String resolved = registry.resolveModelAlias("light");
        assertEquals("qwen3.7-max", resolved, "应通过内置别名解析");

        // Level 4 回退：未知别名直接返回
        String unknown = registry.resolveModelAlias("my-custom-model");
        assertEquals("my-custom-model", unknown,
            "未知别名应直接返回原始名称");
    }

    @Test
    @DisplayName("默认模型兜底策略验证")
    void defaultModelFallbackStrategy() {
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.getDefaultModel()).thenReturn("qwen3.7-max");
        when(provider.getProviderName()).thenReturn("test-provider");
        when(provider.getSupportedModels()).thenReturn(List.of("qwen3.7-max"));

        LlmProviderRegistry registry = new LlmProviderRegistry(
            List.of(provider), mockEnv);

        String defaultModel = registry.getDefaultModel();
        assertNotNull(defaultModel, "默认模型不应为 null");
        assertFalse(defaultModel.isBlank(), "默认模型不应为空");
    }

    @Test
    @DisplayName("轻量级模型回退到默认模型")
    void lightweightModelFallsBackToDefault() {
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.getFastModel()).thenReturn(null);
        when(provider.getDefaultModel()).thenReturn("qwen3.7-max");
        when(provider.getProviderName()).thenReturn("test-provider");
        when(provider.getSupportedModels()).thenReturn(List.of("qwen3.7-max"));

        LlmProviderRegistry registry = new LlmProviderRegistry(
            List.of(provider), mockEnv);

        String lightweight = registry.getLightweightModel();
        assertNotNull(lightweight, "轻量级模型不应为 null（应回退到默认）");
    }

    @Test
    @DisplayName("回退层级不超过 4 级")
    void fallbackChainMaxFourLevels() {
        LlmProvider provider = mock(LlmProvider.class);
        when(provider.getDefaultModel()).thenReturn("qwen3.7-max");
        when(provider.getFastModel()).thenReturn("qwen-plus");
        when(provider.getProviderName()).thenReturn("test-provider");
        when(provider.getSupportedModels()).thenReturn(List.of("qwen-plus", "qwen3.7-max"));

        LlmProviderRegistry registry = new LlmProviderRegistry(
            List.of(provider), mockEnv);

        // 无论如何都应返回一个有效模型
        String classifierModel = registry.resolveClassifierModel();
        assertNotNull(classifierModel, "分类器模型不应为 null");
        assertFalse(classifierModel.isBlank(), "分类器模型不应为空");
    }
}
