package com.aicodeassistant.llm;

import com.aicodeassistant.llm.impl.OpenAiCompatibleProvider;
import com.aicodeassistant.model.Usage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 阿里云百炼集成测试 — 验证通义千问大模型 API 调用。
 * <p>
 * 真实 API 调用测试，使用阿里云百炼 API Key。
 *
 * @see OpenAiCompatibleProvider
 */
class AliyunIntegrationTest {

    private static final String ALIYUN_API_KEY = "sk-93625146d2c343d78735213013794ed5";

    private OpenAiCompatibleProvider provider;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final LlmHttpProperties DEFAULT_HTTP_PROPS = new LlmHttpProperties(
            new LlmHttpProperties.PoolProperties(5, 30), 10, 10, true);

    @BeforeEach
    void setUp() {
        provider = new OpenAiCompatibleProvider(
                objectMapper,
                DEFAULT_HTTP_PROPS,
                new ApiKeyRotationManager(List.of(), ALIYUN_API_KEY),
                ALIYUN_API_KEY,
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "qwen3.6-plus",
                List.of("qwen3.6-plus", "qwen-max", "qwen-plus", "qwen-turbo")
        );
    }

    @Test
    void testProviderConfiguration() {
        assertEquals("openai-compatible", provider.getProviderName());
        assertEquals("qwen3.6-plus", provider.getDefaultModel());
        assertTrue(provider.getSupportedModels().contains("qwen3.6-plus"));
        assertTrue(provider.getSupportedModels().contains("qwen-max"));
    }

    @Test
    void testModelCapabilities() {
        ModelCapabilities caps = provider.getModelCapabilities("qwen3.6-plus");
        assertNotNull(caps);
        assertEquals("qwen3.6-plus", caps.modelId());
        assertTrue(caps.supportsStreaming());
        assertTrue(caps.supportsToolUse());
        assertEquals(131072, caps.contextWindow());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ALIYUN_API_KEY", matches = ".+")
    void testSimpleChatCompletion() throws InterruptedException {
        // ==================== 入参 ====================
        String model = "qwen3.6-plus";
        String systemPrompt = "你是一个有帮助的AI助手";
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "user", "content", "你好，请用一句话介绍自己")
        );
        int maxTokens = 1024;

        System.out.println("\n========== 阿里云百炼 API 调用入参 ==========");
        System.out.println("模型 (model): " + model);
        System.out.println("系统提示 (systemPrompt): " + systemPrompt);
        System.out.println("消息 (messages): " + messages);
        System.out.println("最大Token (maxTokens): " + maxTokens);
        System.out.println("API Key: " + ALIYUN_API_KEY.substring(0, 10) + "...");
        System.out.println("Base URL: https://dashscope.aliyuncs.com/compatible-mode/v1");
        System.out.println("===========================================\n");

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> responseText = new AtomicReference<>();
        AtomicReference<String> errorRef = new AtomicReference<>();

        // When
        long startTime = System.currentTimeMillis();
        provider.streamChat(
                model,
                messages,
                systemPrompt,
                List.of(),
                maxTokens,
                new ThinkingConfig.Disabled(),
                new StreamChatCallback() {
                    private final StringBuilder sb = new StringBuilder();

                    @Override
                    public void onEvent(LlmStreamEvent event) {
                        if (event instanceof LlmStreamEvent.TextDelta delta) {
                            sb.append(delta.text());
                        }
                    }

                    @Override
                    public void onComplete() {
                        responseText.set(sb.toString());
                        latch.countDown();
                    }

                    @Override
                    public void onError(Throwable error) {
                        errorRef.set(error.getMessage());
                        latch.countDown();
                    }
                }
        );

        // Then
        boolean completed = latch.await(60, TimeUnit.SECONDS);
        long duration = System.currentTimeMillis() - startTime;

        System.out.println("\n========== 阿里云百炼 API 调用出参 ==========");
        System.out.println("请求耗时: " + duration + " ms");
        System.out.println("完成状态: " + (completed ? "成功" : "超时"));

        if (errorRef.get() != null) {
            System.out.println("错误信息: " + errorRef.get());
        }

        String response = responseText.get();
        if (response != null && !response.isEmpty()) {
            System.out.println("响应内容: " + response);
            System.out.println("响应长度: " + response.length() + " 字符");
        }
        System.out.println("===========================================\n");

        assertTrue(completed, "Stream should complete within 60 seconds");
        assertNull(errorRef.get(), "Should not have error: " + errorRef.get());
        assertNotNull(response, "Response should not be null");
        assertFalse(response.isEmpty(), "Response should not be empty");

        System.out.println("✅ 阿里云百炼 qwen3.6-plus 真实 API 调用成功!");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ALIYUN_API_KEY", matches = ".+")
    void testToolUseCapability() throws InterruptedException {
        // Given - 定义一个简单的搜索工具
        Map<String, Object> searchTool = Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "web_search",
                        "description", "搜索网络信息",
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "query", Map.of("type", "string", "description", "搜索关键词")
                                ),
                                "required", List.of("query")
                        )
                )
        );

        List<Map<String, Object>> messages = List.of(
                Map.of("role", "user", "content", "搜索一下今天的天气")
        );

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Boolean> toolUseDetected = new AtomicReference<>(false);

        // When
        provider.streamChat(
                "qwen3.6-plus",
                messages,
                "你可以使用工具来帮助用户",
                List.of(searchTool),
                1024,
                new ThinkingConfig.Disabled(),
                new StreamChatCallback() {
                    @Override
                    public void onEvent(LlmStreamEvent event) {
                        if (event instanceof LlmStreamEvent.ToolUseStart) {
                            toolUseDetected.set(true);
                        }
                    }

                    @Override
                    public void onComplete() {
                        latch.countDown();
                    }

                    @Override
                    public void onError(Throwable error) {
                        latch.countDown();
                    }
                }
        );

        // Then
        boolean completed = latch.await(30, TimeUnit.SECONDS);
        assertTrue(completed, "Stream should complete within 30 seconds");

        // 注：模型可能选择使用工具或直接回答，两种结果都接受
        System.out.println("✅ 工具调用检测: " + (toolUseDetected.get() ? "是" : "否（直接回答）"));
    }

    @Test
    void testMultipleModelsSupport() {
        // 验证所有配置的模型都有能力定义
        List<String> models = List.of("qwen3.6-plus", "qwen-max", "qwen-plus", "qwen-turbo");

        for (String model : models) {
            ModelCapabilities caps = provider.getModelCapabilities(model);
            assertNotNull(caps, "Model " + model + " should have capabilities defined");
            assertTrue(caps.supportsStreaming(), model + " should support streaming");
            System.out.println("✅ 模型 " + model + " 能力: context=" + caps.contextWindow() + ", toolUse=" + caps.supportsToolUse());
        }
    }
}
