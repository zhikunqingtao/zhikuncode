package com.aicodeassistant.llm;

import java.util.Map;

/**
 * 思考模式配置 — 控制 LLM 的扩展思考行为。
 * <p>
 * 三种模式:
 * <ul>
 *   <li>Adaptive: 模型自行决定是否/多少思考（Opus 4.6+ 推荐）</li>
 *   <li>Enabled: 显式启用扩展思考，可选 budgetTokens 控制预算</li>
 *   <li>Disabled: 不使用扩展思考</li>
 * </ul>
 *
 * @see <a href="SPEC §3.1.2">Java 接口定义</a>
 */
public sealed interface ThinkingConfig {

    /** 自适应模式 — 模型自行决定思考量（Opus 4.6+ 推荐） */
    record Adaptive() implements ThinkingConfig {}

    /** 启用模式 — 固定思考 token 预算 */
    record Enabled(Integer budgetTokens) implements ThinkingConfig {}

    /** 禁用模式 — 无扩展思考 */
    record Disabled() implements ThinkingConfig {}

    /** 默认思考 token 预算 — 用于 Adaptive 模式的降级和默认值 */
    int DEFAULT_BUDGET_TOKENS = 10000;
    int MIN_BUDGET = 2000;
    int MAX_BUDGET = 50000;

    /**
     * 转换为 LLM API 请求格式 — 各 Provider 在构建请求体时调用。
     *
     * @return API 请求参数 Map；返回 null 表示不发送 thinking 参数
     */
    default Map<String, Object> toApiFormat() {
        return switch (this) {
            case Adaptive a -> Map.of(
                    "type", "enabled",
                    "budget_tokens", DEFAULT_BUDGET_TOKENS
            );
            case Enabled e -> Map.of(
                    "type", "enabled",
                    "budget_tokens", e.budgetTokens() != null ? e.budgetTokens() : DEFAULT_BUDGET_TOKENS
            );
            case Disabled d -> null;
        };
    }

    /** 是否需要 Provider 支持思考能力 */
    default boolean requiresThinkingSupport() {
        return this instanceof Adaptive || this instanceof Enabled;
    }
}
