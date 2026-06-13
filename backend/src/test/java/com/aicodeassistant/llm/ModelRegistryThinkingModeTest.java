package com.aicodeassistant.llm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;

/**
 * ModelRegistry M0-a/b 改动单元测试 — 验证 BUILTIN_MODELS 中千问模型参数。
 * <p>
 * 验证目标：
 * <ul>
 *   <li>qwen3.7-max: contextWindow=1000000, maxOutputTokens=65536, supportsThinking=true</li>
 *   <li>qwen3.7-plus: contextWindow=1000000, maxOutputTokens=8192, supportsThinking=true</li>
 *   <li>qwen-turbo (对照): supportsThinking=false</li>
 *   <li>deepseek-v4-pro: supportsThinking=true（在 BUILTIN_MODELS 中）</li>
 * </ul>
 */
@DisplayName("ModelRegistry M0-a/b Thinking 模式参数测试")
class ModelRegistryThinkingModeTest {

    @Mock
    private LlmProviderRegistry providerRegistry;

    private ModelRegistry modelRegistry;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // 关键：让 Provider 查询全部抛异常，强制 fallback 到 Level 3 BUILTIN_MODELS
        lenient().when(providerRegistry.getProvider(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new IllegalArgumentException("no provider in test"));
        modelRegistry = new ModelRegistry(providerRegistry);
    }

    @Test
    @DisplayName("tc001: qwen3.7-max contextWindow = 1000000")
    void tc001_qwen37Max_contextWindow1M() {
        ModelCapabilities caps = modelRegistry.getCapabilities("qwen3.7-max");

        assertThat(caps.contextWindow()).isEqualTo(1_000_000);
    }

    @Test
    @DisplayName("tc002: qwen3.7-max maxOutputTokens = 65536")
    void tc002_qwen37Max_maxOutputTokens65536() {
        ModelCapabilities caps = modelRegistry.getCapabilities("qwen3.7-max");

        assertThat(caps.maxOutputTokens()).isEqualTo(65536);
    }

    @Test
    @DisplayName("tc003: qwen3.7-max supportsThinking = true")
    void tc003_qwen37Max_supportsThinkingTrue() {
        ModelCapabilities caps = modelRegistry.getCapabilities("qwen3.7-max");

        assertThat(caps.supportsThinking()).isTrue();
    }

    @Test
    @DisplayName("tc004: qwen3.7-plus contextWindow = 1000000")
    void tc004_qwen37Plus_contextWindow1M() {
        ModelCapabilities caps = modelRegistry.getCapabilities("qwen3.7-plus");

        assertThat(caps.contextWindow()).isEqualTo(1_000_000);
    }

    @Test
    @DisplayName("tc005: qwen3.7-plus maxOutputTokens = 8192")
    void tc005_qwen37Plus_maxOutputTokens8192() {
        ModelCapabilities caps = modelRegistry.getCapabilities("qwen3.7-plus");

        assertThat(caps.maxOutputTokens()).isEqualTo(8192);
    }

    @Test
    @DisplayName("tc006: qwen3.7-plus supportsThinking = true")
    void tc006_qwen37Plus_supportsThinkingTrue() {
        ModelCapabilities caps = modelRegistry.getCapabilities("qwen3.7-plus");

        assertThat(caps.supportsThinking()).isTrue();
    }

    @Test
    @DisplayName("tc007: qwen-turbo supportsThinking = false（对照组）")
    void tc007_qwenTurbo_supportsThinkingFalse() {
        ModelCapabilities caps = modelRegistry.getCapabilities("qwen-turbo");

        assertThat(caps.supportsThinking()).isFalse();
    }

    @Test
    @DisplayName("tc008: deepseek-v4-pro supportsThinking = true")
    void tc008_deepseekV4Pro_supportsThinkingTrue() {
        ModelCapabilities caps = modelRegistry.getCapabilities("deepseek-v4-pro");

        assertThat(caps.supportsThinking()).isTrue();
    }

    @Test
    @DisplayName("tc009: getContextWindowForModel(qwen3.7-max) 与 capabilities 一致")
    void tc009_getContextWindowForModel_consistent() {
        int ctx = modelRegistry.getContextWindowForModel("qwen3.7-max");

        assertThat(ctx).isEqualTo(1_000_000);
    }

    @Test
    @DisplayName("tc010: getMaxOutputTokensForModel(qwen3.7-plus) 与 capabilities 一致")
    void tc010_getMaxOutputTokens_consistent() {
        int out = modelRegistry.getMaxOutputTokensForModel("qwen3.7-plus");

        assertThat(out).isEqualTo(8192);
    }

    @Test
    @DisplayName("tc011: 未知模型 fallback 到 DEFAULT，supportsThinking=false")
    void tc011_unknownModel_fallbackDefault() {
        ModelCapabilities caps = modelRegistry.getCapabilities("non-existent-model-xyz");

        assertThat(caps).isEqualTo(ModelCapabilities.DEFAULT);
        assertThat(caps.supportsThinking()).isFalse();
    }
}
