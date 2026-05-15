package com.aicodeassistant.llm;

import com.aicodeassistant.config.ModelCapabilityConfig;
import com.aicodeassistant.llm.ModelCapabilityRegistry.ModelCapability;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ModelCapabilityRegistry 单元测试 — TC-MODEL-001 ~ TC-MODEL-006。
 * <p>
 * 不使用 Spring Context，直接构造 + 手动 init()。
 */
class ModelCapabilityRegistryTest {

    private ModelCapabilityRegistry registry;

    @BeforeEach
    void setUp() {
        // 使用空 YAML 配置，只依赖硬编码 BUILTIN_CAPABILITIES
        ModelCapabilityConfig config = new ModelCapabilityConfig();
        registry = new ModelCapabilityRegistry(config);
        registry.init();
    }

    // ==================== TC-MODEL-001 ====================

    @Test
    @DisplayName("TC-MODEL-001: 已注册模型返回正确能力参数")
    void tc001_registeredModelReturnsCorrectCapability() {
        ModelCapability cap = registry.getCapability("claude-sonnet-4-20250514");

        assertThat(cap).isNotNull();
        assertThat(cap.modelId()).isEqualTo("claude-sonnet-4-20250514");
        assertThat(cap.contextWindow()).isEqualTo(200_000);
        assertThat(cap.outputMaxTokens()).isEqualTo(64_000);
        assertThat(cap.tokenCharRatio()).isEqualTo(3.5);
        // 确认不是 DEFAULT（DEFAULT 的 contextWindow=128_000）
        assertThat(cap.contextWindow()).isNotEqualTo(128_000);
    }

    // ==================== TC-MODEL-002 ====================

    @Test
    @DisplayName("TC-MODEL-002: 未注册模型返回 DEFAULT")
    void tc002_unregisteredModelReturnsDefault() {
        ModelCapability cap = registry.getCapability("unknown-model-xyz");

        assertThat(cap).isNotNull();
        assertThat(cap.modelId()).isEqualTo("default");
        assertThat(cap.contextWindow()).isEqualTo(128_000);
        assertThat(cap.outputMaxTokens()).isEqualTo(8_192);
        assertThat(cap.tokenCharRatio()).isEqualTo(3.5);
    }

    // ==================== TC-MODEL-003 ====================

    @Test
    @DisplayName("TC-MODEL-003: null/blank modelId 返回 DEFAULT")
    void tc003_nullOrBlankModelIdReturnsDefault() {
        // null
        ModelCapability capNull = registry.getCapability(null);
        assertThat(capNull.modelId()).isEqualTo("default");
        assertThat(capNull.contextWindow()).isEqualTo(128_000);

        // 空字符串
        ModelCapability capEmpty = registry.getCapability("");
        assertThat(capEmpty.modelId()).isEqualTo("default");
        assertThat(capEmpty.contextWindow()).isEqualTo(128_000);

        // 纯空格
        ModelCapability capBlank = registry.getCapability("   ");
        assertThat(capBlank.modelId()).isEqualTo("default");
        assertThat(capBlank.contextWindow()).isEqualTo(128_000);
    }

    // ==================== TC-MODEL-004 ====================

    @Test
    @DisplayName("TC-MODEL-004: 预定义模型包含关键能力")
    void tc004_builtinModelHasExpectedCapabilities() {
        // claude-sonnet-4-20250514: 支持 toolUse, vision, streaming, cache
        ModelCapability claude = registry.getCapability("claude-sonnet-4-20250514");
        assertThat(claude.supportsToolUse()).isTrue();
        assertThat(claude.supportsVision()).isTrue();
        assertThat(claude.supportsStreaming()).isTrue();
        assertThat(claude.supportsCache()).isTrue();

        // qwen-max: 支持 toolUse, streaming；不支持 vision, cache
        ModelCapability qwen = registry.getCapability("qwen-max");
        assertThat(qwen.supportsToolUse()).isTrue();
        assertThat(qwen.supportsVision()).isFalse();
        assertThat(qwen.supportsStreaming()).isTrue();
        assertThat(qwen.supportsCache()).isFalse();
        assertThat(qwen.tokenCharRatio()).isEqualTo(2.5);

        // deepseek-chat: contextWindow=64_000, tokenCharRatio=2.8
        ModelCapability deepseek = registry.getCapability("deepseek-chat");
        assertThat(deepseek.contextWindow()).isEqualTo(64_000);
        assertThat(deepseek.tokenCharRatio()).isEqualTo(2.8);
        assertThat(deepseek.supportsVision()).isFalse();
        assertThat(deepseek.supportsCache()).isFalse();
    }

    // ==================== TC-MODEL-005 ====================

    @Test
    @DisplayName("TC-MODEL-005: getCompactThreshold 按窗口大小正确分级")
    void tc005_compactThresholdTieredByContextWindow() {
        // claude-sonnet-4-20250514: contextWindow=200_000 → 0.90
        assertThat(registry.getCompactThreshold("claude-sonnet-4-20250514")).isEqualTo(0.90);

        // qwen-max: contextWindow=128_000 → 0.85
        assertThat(registry.getCompactThreshold("qwen-max")).isEqualTo(0.85);

        // deepseek-chat: contextWindow=64_000 → 0.80
        assertThat(registry.getCompactThreshold("deepseek-chat")).isEqualTo(0.80);

        // unknown → DEFAULT contextWindow=128_000 → 0.85
        assertThat(registry.getCompactThreshold("unknown-model")).isEqualTo(0.85);
    }

    // ==================== TC-MODEL-006 ====================

    @Test
    @DisplayName("TC-MODEL-006: getBufferTokens = contextWindow × 10%")
    void tc006_bufferTokensIsTenPercentOfContextWindow() {
        // DEFAULT: 128_000 × 0.10 = 12_800
        assertThat(registry.getBufferTokens("unknown-model")).isEqualTo(12_800);

        // claude-sonnet-4-20250514: 200_000 × 0.10 = 20_000
        assertThat(registry.getBufferTokens("claude-sonnet-4-20250514")).isEqualTo(20_000);

        // deepseek-chat: 64_000 × 0.10 = 6_400
        assertThat(registry.getBufferTokens("deepseek-chat")).isEqualTo(6_400);
    }
}
