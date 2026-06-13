package com.aicodeassistant.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ThinkingConfig 单元测试 — 验证 sealed interface 三种实现的 API 契约。
 * 覆盖 Adaptive / Enabled / Disabled 的构造、toApiFormat、requiresThinkingSupport。
 */
@DisplayName("ThinkingConfig sealed interface 测试")
class ThinkingConfigTest {

    @Test
    @DisplayName("tc001: Adaptive 无参构造 computedBudget 为 null")
    void tc001_adaptiveNoArgConstructor_computedBudgetIsNull() {
        ThinkingConfig.Adaptive adaptive = new ThinkingConfig.Adaptive();

        assertThat(adaptive.computedBudget()).isNull();
    }

    @Test
    @DisplayName("tc002: Adaptive 有参构造保留 computedBudget")
    void tc002_adaptiveWithArgConstructor_preservesBudget() {
        ThinkingConfig.Adaptive adaptive = new ThinkingConfig.Adaptive(15000);

        assertThat(adaptive.computedBudget()).isEqualTo(15000);
    }

    @Test
    @DisplayName("tc003: Adaptive.requiresThinkingSupport() 返回 true")
    void tc003_adaptive_requiresThinkingSupport_true() {
        ThinkingConfig config = new ThinkingConfig.Adaptive();

        assertThat(config.requiresThinkingSupport()).isTrue();
    }

    @Test
    @DisplayName("tc004: Enabled.requiresThinkingSupport() 返回 true")
    void tc004_enabled_requiresThinkingSupport_true() {
        ThinkingConfig config = new ThinkingConfig.Enabled(20000);

        assertThat(config.requiresThinkingSupport()).isTrue();
    }

    @Test
    @DisplayName("tc005: Disabled.requiresThinkingSupport() 返回 false")
    void tc005_disabled_requiresThinkingSupport_false() {
        ThinkingConfig config = new ThinkingConfig.Disabled();

        assertThat(config.requiresThinkingSupport()).isFalse();
    }

    @Test
    @DisplayName("tc006: Adaptive(null).toApiFormat() 使用 DEFAULT_BUDGET_TOKENS")
    void tc006_adaptiveNullBudget_useDefaultBudget() {
        ThinkingConfig config = new ThinkingConfig.Adaptive(null);

        Map<String, Object> apiFormat = config.toApiFormat();

        assertThat(apiFormat)
                .isNotNull()
                .containsEntry("type", "enabled")
                .containsEntry("budget_tokens", ThinkingConfig.DEFAULT_BUDGET_TOKENS);
    }

    @Test
    @DisplayName("tc007: Adaptive(25000).toApiFormat() 使用指定预算")
    void tc007_adaptiveWithBudget_useSpecifiedBudget() {
        ThinkingConfig config = new ThinkingConfig.Adaptive(25000);

        Map<String, Object> apiFormat = config.toApiFormat();

        assertThat(apiFormat)
                .isNotNull()
                .containsEntry("type", "enabled")
                .containsEntry("budget_tokens", 25000);
    }

    @Test
    @DisplayName("tc008: Disabled.toApiFormat() 返回 null")
    void tc008_disabled_toApiFormat_returnsNull() {
        ThinkingConfig config = new ThinkingConfig.Disabled();

        Map<String, Object> apiFormat = config.toApiFormat();

        assertThat(apiFormat).isNull();
    }

    @Test
    @DisplayName("tc009: Enabled(30000).toApiFormat() 包含正确 budget")
    void tc009_enabledWithBudget_apiFormatContainsBudget() {
        ThinkingConfig config = new ThinkingConfig.Enabled(30000);

        Map<String, Object> apiFormat = config.toApiFormat();

        assertThat(apiFormat)
                .isNotNull()
                .containsEntry("type", "enabled")
                .containsEntry("budget_tokens", 30000);
    }

    @Test
    @DisplayName("tc010: sealed interface 限制只有 3 种实现")
    void tc010_sealedInterface_onlyThreePermittedImpl() {
        Class<?>[] permitted = ThinkingConfig.class.getPermittedSubclasses();

        assertThat(permitted).isNotNull();
        assertThat(permitted).hasSize(3);
        assertThat(permitted)
                .containsExactlyInAnyOrder(
                        ThinkingConfig.Adaptive.class,
                        ThinkingConfig.Enabled.class,
                        ThinkingConfig.Disabled.class);
    }

    @Test
    @DisplayName("tc011: Enabled(null) toApiFormat 使用 DEFAULT_BUDGET_TOKENS 兜底")
    void tc011_enabledNullBudget_fallbackToDefault() {
        ThinkingConfig config = new ThinkingConfig.Enabled(null);

        Map<String, Object> apiFormat = config.toApiFormat();

        assertThat(apiFormat)
                .containsEntry("budget_tokens", ThinkingConfig.DEFAULT_BUDGET_TOKENS);
    }
}
