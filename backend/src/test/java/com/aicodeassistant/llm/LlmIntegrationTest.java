package com.aicodeassistant.llm;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TC-LLM-003 + TC-LLM-004 LLM 集成测试。
 */
@DisplayName("LLM 集成测试")
class LlmIntegrationTest {

    /**
     * TC-LLM-003 错误分类与重试验证。
     */
    @Nested
    @DisplayName("TC-LLM-003 错误分类与重试验证")
    class ErrorClassificationTest {

        private LlmErrorClassifier classifier;

        @BeforeEach
        void setUp() {
            classifier = new LlmErrorClassifier();
        }

        @Test
        @DisplayName("429 分类为 RATE_LIMITED")
        void classify429AsRateLimited() {
            LlmApiException ex = new LlmApiException(
                    "Rate limit exceeded", true, 429, "rate_limit_error", 2000);
            LlmErrorClassifier.ErrorCategory cat = classifier.classify(ex);
            assertEquals(LlmErrorClassifier.ErrorCategory.RATE_LIMITED, cat);
        }

        @Test
        @DisplayName("529 分类为 OVERLOADED")
        void classify529AsOverloaded() {
            LlmApiException ex = new LlmApiException(
                    "Service overloaded", true, 529, "overloaded_error", 0);
            LlmErrorClassifier.ErrorCategory cat = classifier.classify(ex);
            assertEquals(LlmErrorClassifier.ErrorCategory.OVERLOADED, cat);
        }

        @Test
        @DisplayName("401 分类为 AUTH_FAILED")
        void classify401AsAuthFailed() {
            LlmApiException ex = new LlmApiException(
                    "Invalid API key", false, 401, "authentication_error", 0);
            LlmErrorClassifier.ErrorCategory cat = classifier.classify(ex);
            assertEquals(LlmErrorClassifier.ErrorCategory.AUTH_FAILED, cat);
        }

        @Test
        @DisplayName("RATE_LIMITED 可重试")
        void rateLimitedIsRetryable() {
            assertTrue(classifier.isRetryable(LlmErrorClassifier.ErrorCategory.RATE_LIMITED),
                    "RATE_LIMITED 应可重试");
        }

        @Test
        @DisplayName("AUTH_FAILED 不可重试")
        void authFailedNotRetryable() {
            assertFalse(classifier.isRetryable(LlmErrorClassifier.ErrorCategory.AUTH_FAILED),
                    "AUTH_FAILED 不应重试");
        }

        @Test
        @DisplayName("PROMPT_TOO_LONG 不可重试")
        void promptTooLongNotRetryable() {
            assertFalse(classifier.isRetryable(LlmErrorClassifier.ErrorCategory.PROMPT_TOO_LONG),
                    "PROMPT_TOO_LONG 不应重试");
        }

        @Test
        @DisplayName("指数退避间隔递增")
        void exponentialBackoffIncreases() {
            long delay1 = classifier.getRetryDelayMs(LlmErrorClassifier.ErrorCategory.RATE_LIMITED, 0);
            long delay2 = classifier.getRetryDelayMs(LlmErrorClassifier.ErrorCategory.RATE_LIMITED, 1);
            long delay3 = classifier.getRetryDelayMs(LlmErrorClassifier.ErrorCategory.RATE_LIMITED, 2);
            assertTrue(delay1 > 0, "第一次延迟应 > 0");
            assertTrue(delay3 >= delay1, "第三次延迟应 >= 第一次");
        }

        @Test
        @DisplayName("最大重试次数配置正确")
        void maxRetriesConfiguration() {
            assertEquals(5, classifier.getMaxRetries(LlmErrorClassifier.ErrorCategory.RATE_LIMITED));
            assertEquals(3, classifier.getMaxRetries(LlmErrorClassifier.ErrorCategory.OVERLOADED));
            assertEquals(0, classifier.getMaxRetries(LlmErrorClassifier.ErrorCategory.AUTH_FAILED));
        }
    }

    /**
     * TC-LLM-004 SystemPromptBuilder 模板渲染验证。
     */
    @Nested
    @DisplayName("TC-LLM-004 SystemPromptBuilder 模板渲染验证")
    class SystemPromptRenderTest {

        @Test
        @DisplayName("SystemPromptBuilder 存在且有常量")
        void systemPromptBuilderExists() throws Exception {
            Class<?> clazz = Class.forName(
                    "com.aicodeassistant.prompt.SystemPromptBuilder");
            assertNotNull(clazz, "SystemPromptBuilder 应存在");

            // 验证核心常量
            var toolBreakpoint = clazz.getField("TOOL_CACHE_BREAKPOINT");
            assertNotNull(toolBreakpoint.get(null), "TOOL_CACHE_BREAKPOINT 不应为null");

            var dynamicBoundary = clazz.getField("SYSTEM_PROMPT_DYNAMIC_BOUNDARY");
            assertNotNull(dynamicBoundary.get(null), "SYSTEM_PROMPT_DYNAMIC_BOUNDARY 不应为null");
        }

        @Test
        @DisplayName("缓存标记常量定义正确")
        void cacheMarkersDefinedCorrectly() throws Exception {
            Class<?> clazz = Class.forName(
                    "com.aicodeassistant.prompt.SystemPromptBuilder");
            String toolBreakpoint = (String) clazz.getField("TOOL_CACHE_BREAKPOINT").get(null);
            String dynamicBoundary = (String) clazz.getField("SYSTEM_PROMPT_DYNAMIC_BOUNDARY").get(null);

            assertEquals("__TOOL_CACHE_BREAKPOINT__", toolBreakpoint);
            assertEquals("__SYSTEM_PROMPT_DYNAMIC_BOUNDARY__", dynamicBoundary);
        }

        @Test
        @DisplayName("SystemPrompt toPlainText 转换")
        void systemPromptToPlainText() {
            SystemPrompt prompt = SystemPrompt.of("You are a helpful assistant.");
            assertNotNull(prompt, "SystemPrompt 不应为null");
            String text = prompt.toPlainText();
            assertTrue(text.contains("helpful assistant"),
                    "纯文本应包含原始内容");
        }

        @Test
        @DisplayName("相同参数多次调用返回相同结果")
        void cacheConsistency() {
            SystemPrompt p1 = SystemPrompt.of("Consistent prompt");
            SystemPrompt p2 = SystemPrompt.of("Consistent prompt");
            assertEquals(p1.toPlainText(), p2.toPlainText(),
                    "相同参数应返回相同结果");
        }
    }
}
