package com.aicodeassistant.llm;

import com.aicodeassistant.llm.impl.OpenAiCompatibleProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 阿里云百炼配置验证测试 — 无需真实 API 调用，仅验证配置正确性。
 *
 * @see OpenAiCompatibleProvider
 */
class AliyunConfigVerificationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final LlmHttpProperties DEFAULT_HTTP_PROPS = new LlmHttpProperties(
            new LlmHttpProperties.PoolProperties(5, 30), 10, 10, true);

    @Test
    void testQwen36PlusModelCapabilities() {
        // Given
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(
                objectMapper,
                DEFAULT_HTTP_PROPS,
                "sk-test-key",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "qwen3.6-plus",
                List.of("qwen3.6-plus", "qwen-max", "qwen-plus")
        );

        // When & Then
        ModelCapabilities caps = provider.getModelCapabilities("qwen3.6-plus");
        assertNotNull(caps);
        assertEquals("qwen3.6-plus", caps.modelId());
        assertEquals("通义千问 3.6 Plus", caps.displayName());
        assertEquals(131072, caps.contextWindow());
        assertTrue(caps.supportsStreaming());
        assertTrue(caps.supportsToolUse());
        assertTrue(caps.supportsImages());

        System.out.println("✅ qwen3.6-plus 模型能力:");
        System.out.println("   - 上下文窗口: " + caps.contextWindow());
        System.out.println("   - 支持流式: " + caps.supportsStreaming());
        System.out.println("   - 支持工具: " + caps.supportsToolUse());
        System.out.println("   - 支持图片: " + caps.supportsImages());
    }

    @Test
    void testAllQwenModelsSupported() {
        // Given
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(
                objectMapper,
                DEFAULT_HTTP_PROPS,
                "sk-test-key",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "qwen3.6-plus",
                List.of("qwen3.6-plus", "qwen-max", "qwen-plus", "qwen-turbo", "qwen-coder-plus")
        );

        // When & Then
        List<String> models = List.of("qwen3.6-plus", "qwen-max", "qwen-plus", "qwen-turbo", "qwen-coder-plus");

        for (String model : models) {
            ModelCapabilities caps = provider.getModelCapabilities(model);
            assertNotNull(caps, "Model " + model + " should have capabilities");
            assertTrue(caps.supportsStreaming(), model + " should support streaming");
            System.out.println("✅ " + model + ": " + caps.displayName());
        }
    }

    @Test
    void testProviderConfiguration() {
        // Given
        String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
        String apiKey = "sk-93625146d2c343d78735213013794ed5";

        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(
                objectMapper,
                DEFAULT_HTTP_PROPS,
                apiKey,
                baseUrl,
                "qwen3.6-plus",
                List.of("qwen3.6-plus")
        );

        // Then
        assertEquals("openai-compatible", provider.getProviderName());
        assertEquals("qwen3.6-plus", provider.getDefaultModel());
        assertTrue(provider.getSupportedModels().contains("qwen3.6-plus"));

        System.out.println("✅ Provider 配置:");
        System.out.println("   - 名称: " + provider.getProviderName());
        System.out.println("   - 默认模型: " + provider.getDefaultModel());
        System.out.println("   - 支持模型: " + provider.getSupportedModels());
    }

    @Test
    void testModelCostConfiguration() {
        // Given
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider(
                objectMapper,
                DEFAULT_HTTP_PROPS,
                "sk-test-key",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "qwen3.6-plus",
                List.of("qwen3.6-plus")
        );

        // When
        ModelCapabilities caps = provider.getModelCapabilities("qwen3.6-plus");

        // Then
        assertTrue(caps.costPer1kInput() > 0);
        assertTrue(caps.costPer1kOutput() > 0);

        System.out.println("✅ qwen3.6-plus 价格配置:");
        System.out.println("   - 输入: $" + caps.costPer1kInput() + "/1K tokens");
        System.out.println("   - 输出: $" + caps.costPer1kOutput() + "/1K tokens");
    }
}
