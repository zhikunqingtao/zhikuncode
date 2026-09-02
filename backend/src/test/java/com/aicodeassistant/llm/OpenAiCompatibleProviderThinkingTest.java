package com.aicodeassistant.llm;

import com.aicodeassistant.llm.impl.OpenAiCompatibleProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAiCompatibleProvider M0-e + P0 修复单元测试 — 验证 supportsThinking override
 * 与 isQwenThinkingModel 边界判定。
 * <p>
 * 验证目标：
 * <ul>
 *   <li>对 MODEL_CAPABILITIES 中存在的模型直接返回其 supportsThinking 值</li>
 *   <li>对 qwen3.8-/qwen3.7-/qwen3.6- 前缀模型走 isQwenThinkingModel 判定</li>
 *   <li>对 deepseek-v4- 前缀模型走 isDeepSeekV4Model 判定</li>
 *   <li>对 glm-5.3 / glm-5.3-flash 走 isGlmForcedThinkingModel 判定（强制思考）</li>
 *   <li>未匹配模型返回 false（不再抛 IllegalArgumentException）</li>
 * </ul>
 */
@DisplayName("OpenAiCompatibleProvider Thinking 模式判定测试")
class OpenAiCompatibleProviderThinkingTest {

    private static final LlmHttpProperties DEFAULT_HTTP_PROPS = new LlmHttpProperties(
            new LlmHttpProperties.PoolProperties(5, 30), 10, 10, true);

    private OpenAiCompatibleProvider provider;

    @BeforeEach
    void setUp() {
        provider = new OpenAiCompatibleProvider(
                "test-provider",
                new ObjectMapper(),
                DEFAULT_HTTP_PROPS,
                new ApiKeyRotationManager("sk-test"),
                "sk-test",
                "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "qwen3.7-plus",
                List.of("qwen3.8-max-0902", "qwen3.7-plus", "qwen-coder-plus", "deepseek-v4-pro",
                        "deepseek-v4-flash-vision-exp")
        );
    }

    @Test
    @DisplayName("tc001: supportsThinking(qwen3.8-max-0902) 返回 true")
    void tc001_qwen37Max_supportsThinkingTrue() {
        assertThat(provider.supportsThinking("qwen3.8-max-0902")).isTrue();
    }

    @Test
    @DisplayName("tc002: supportsThinking(qwen3.7-plus) 返回 true")
    void tc002_qwen37Plus_supportsThinkingTrue() {
        assertThat(provider.supportsThinking("qwen3.7-plus")).isTrue();
    }

    @Test
    @DisplayName("tc003: supportsThinking(qwen3.6-max) 返回 true（前缀匹配）")
    void tc003_qwen36Max_supportsThinkingTrue() {
        assertThat(provider.supportsThinking("qwen3.6-max")).isTrue();
    }

    @Test
    @DisplayName("tc004: supportsThinking(qwen-turbo) 返回 false（不匹配 qwen3.8-/qwen3.7-/qwen3.6- 前缀）")
    void tc004_qwenTurbo_supportsThinkingFalse() {
        assertThat(provider.supportsThinking("qwen-turbo")).isFalse();
    }

    @Test
    @DisplayName("tc005: supportsThinking(deepseek-v4-pro) 返回 true（在 MODEL_CAPABILITIES 中）")
    void tc005_deepseekV4Pro_supportsThinkingTrue() {
        assertThat(provider.supportsThinking("deepseek-v4-pro")).isTrue();
    }

    @Test
    @DisplayName("tc006: supportsThinking(unknown-model) 返回 false（不抛异常）")
    void tc006_unknownModel_supportsThinkingFalse() {
        assertThat(provider.supportsThinking("totally-unknown-model")).isFalse();
    }

    @Test
    @DisplayName("tc007: supportsThinking(null) 返回 false")
    void tc007_nullModel_supportsThinkingFalse() {
        assertThat(provider.supportsThinking(null)).isFalse();
    }

    @Test
    @DisplayName("tc008: isQwenThinkingModel 边界 - qwen3.7- 前缀匹配")
    void tc008_isQwenThinkingModel_qwen37PrefixMatches() throws Exception {
        Method m = OpenAiCompatibleProvider.class.getDeclaredMethod("isQwenThinkingModel", String.class);
        m.setAccessible(true);

        assertThat((boolean) m.invoke(null, "qwen3.8-max-0902")).isTrue();
        assertThat((boolean) m.invoke(null, "qwen3.7-plus")).isTrue();
        assertThat((boolean) m.invoke(null, "qwen3.7-anything-future")).isTrue();
    }

    @Test
    @DisplayName("tc009: isQwenThinkingModel 边界 - qwen3.6- 前缀匹配 / 其他不匹配")
    void tc009_isQwenThinkingModel_qwen36PrefixMatches() throws Exception {
        Method m = OpenAiCompatibleProvider.class.getDeclaredMethod("isQwenThinkingModel", String.class);
        m.setAccessible(true);

        assertThat((boolean) m.invoke(null, "qwen3.6-max")).isTrue();
        assertThat((boolean) m.invoke(null, "qwen3.6-plus")).isTrue();
        assertThat((boolean) m.invoke(null, "qwen3.5-max")).isFalse();
        assertThat((boolean) m.invoke(null, "qwen-turbo")).isFalse();
        assertThat((boolean) m.invoke(null, "qwen-max")).isFalse();
        assertThat((boolean) m.invoke(null, (Object) null)).isFalse();
    }

    @Test
    @DisplayName("tc010: isDeepSeekV4Model 边界 - 仅 deepseek-v4- 前缀匹配")
    void tc010_isDeepSeekV4Model_prefixMatching() throws Exception {
        Method m = OpenAiCompatibleProvider.class.getDeclaredMethod("isDeepSeekV4Model", String.class);
        m.setAccessible(true);

        assertThat((boolean) m.invoke(null, "deepseek-v4-pro")).isTrue();
        assertThat((boolean) m.invoke(null, "deepseek-v4-flash")).isTrue();
        assertThat((boolean) m.invoke(null, "deepseek-v4-pro-0813")).isTrue();
        assertThat((boolean) m.invoke(null, "deepseek-v4-flash-0731")).isTrue();
        assertThat((boolean) m.invoke(null, "deepseek-v3-pro")).isFalse();
        assertThat((boolean) m.invoke(null, "deepseek-chat")).isFalse();
        assertThat((boolean) m.invoke(null, (Object) null)).isFalse();
    }

    @Test
    @DisplayName("tc011: getModelCapabilities 对未注册模型仍抛 IllegalArgumentException（保留契约）")
    void tc011_getModelCapabilities_unknownStillThrows() {
        // supportsThinking 已 fallback，但 getModelCapabilities 的抛异常契约应保留，
        // 以便 ModelRegistry Level 2→3 fallback 链路不受影响
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> provider.getModelCapabilities("qwen3.8-max-0902"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("tc012: DeepSeek Vision 官方能力支持 thinking 与图片")
    void tc012_deepseekVisionCapabilities() {
        ModelCapabilities caps = provider.getModelCapabilities("deepseek-v4-flash-vision-exp");

        assertThat(caps.supportsThinking()).isTrue();
        assertThat(caps.supportsImages()).isTrue();
        assertThat(caps.maxImages()).isEqualTo(5);
    }

    @Test
    @DisplayName("tc013: DeepSeek Vision 从普通 V4 max-thinking 策略中排除")
    void tc013_deepseekVisionUsesDedicatedRequestStrategy() throws Exception {
        Method vision = OpenAiCompatibleProvider.class
                .getDeclaredMethod("isDeepSeekVisionModel", String.class);
        Method v4 = OpenAiCompatibleProvider.class
                .getDeclaredMethod("isDeepSeekV4Model", String.class);
        vision.setAccessible(true);
        v4.setAccessible(true);

        assertThat((boolean) vision.invoke(null, "deepseek-v4-flash-vision-exp")).isTrue();
        assertThat((boolean) v4.invoke(null, "deepseek-v4-flash-vision-exp")).isFalse();
    }

    @Test
    @DisplayName("tc014: supportsThinking(glm-5.3-flash) 返回 true（强制思考，不可关闭）")
    void tc014_glm53Flash_supportsThinkingTrue() {
        assertThat(provider.supportsThinking("glm-5.3-flash")).isTrue();
    }

    @Test
    @DisplayName("tc015: supportsThinking(glm-5.3) 返回 true（强制思考，不可关闭）")
    void tc015_glm53_supportsThinkingTrue() {
        assertThat(provider.supportsThinking("glm-5.3")).isTrue();
    }

    @Test
    @DisplayName("tc016: isGlmForcedThinkingModel 边界 - 仅 glm-5.3 / glm-5.3-flash 匹配")
    void tc016_isGlmForcedThinkingModel_boundary() throws Exception {
        Method m = OpenAiCompatibleProvider.class.getDeclaredMethod("isGlmForcedThinkingModel", String.class);
        m.setAccessible(true);

        assertThat((boolean) m.invoke(null, "glm-5.3")).isTrue();
        assertThat((boolean) m.invoke(null, "glm-5.3-flash")).isTrue();
        assertThat((boolean) m.invoke(null, "glm-5.2")).isFalse();
        assertThat((boolean) m.invoke(null, "glm-5v-turbo")).isFalse();
        assertThat((boolean) m.invoke(null, (Object) null)).isFalse();
    }

    @Test
    @DisplayName("tc017: supportsThinking(qwen3.8-flash / qwen3.8-max) 返回 true（百炼官方支持思考模式）")
    void tc017_qwen38Models_supportsThinkingTrue() {
        assertThat(provider.supportsThinking("qwen3.8-flash")).isTrue();
        assertThat(provider.supportsThinking("qwen3.8-max")).isTrue();
    }

    @Test
    @DisplayName("tc018: isQwenThinkingModel 边界 - qwen3.8- 前缀匹配")
    void tc018_isQwenThinkingModel_qwen38PrefixMatches() throws Exception {
        Method m = OpenAiCompatibleProvider.class.getDeclaredMethod("isQwenThinkingModel", String.class);
        m.setAccessible(true);

        assertThat((boolean) m.invoke(null, "qwen3.8-max")).isTrue();
        assertThat((boolean) m.invoke(null, "qwen3.8-flash")).isTrue();
        assertThat((boolean) m.invoke(null, "qwen3.8-anything-future")).isTrue();
    }
}
