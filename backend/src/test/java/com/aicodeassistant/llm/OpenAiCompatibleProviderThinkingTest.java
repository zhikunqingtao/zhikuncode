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
 *   <li>对 qwen3.7-/qwen3.6- 前缀模型走 isQwenThinkingModel 判定</li>
 *   <li>对 deepseek-v4- 前缀模型走 isDeepSeekV4Model 判定</li>
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
                List.of("qwen3.7-max", "qwen3.7-plus", "qwen-coder-plus", "deepseek-v4-pro")
        );
    }

    @Test
    @DisplayName("tc001: supportsThinking(qwen3.7-max) 返回 true")
    void tc001_qwen37Max_supportsThinkingTrue() {
        assertThat(provider.supportsThinking("qwen3.7-max")).isTrue();
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
    @DisplayName("tc004: supportsThinking(qwen-turbo) 返回 false（不匹配 qwen3.7-/qwen3.6- 前缀）")
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

        assertThat((boolean) m.invoke(null, "qwen3.7-max")).isTrue();
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
                () -> provider.getModelCapabilities("qwen3.7-max"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
