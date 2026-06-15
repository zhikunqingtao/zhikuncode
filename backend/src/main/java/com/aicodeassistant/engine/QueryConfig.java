package com.aicodeassistant.engine;

import com.aicodeassistant.llm.ModelRegistry;
import com.aicodeassistant.llm.ThinkingConfig;
import com.aicodeassistant.tool.Tool;

import java.util.List;
import java.util.Map;

/**
 * 查询配置 — 不可变配置快照，在 queryLoop 入口构建。
 * <p>
 *
 * @param model           模型名称
 * @param fallbackModel   降级模型（可为 null
 * @param systemPrompt    系统提示
 * @param tools           可用工具列表
 * @param toolDefinitions 工具 API 定义
 * @param maxTokens       最大输出 token
 * @param contextWindow   模型上下文窗口大小
 * @param thinkingConfig  思考模式配置
 * @param maxTurns        最大循环轮次 (防止无限循环)
 * @param querySource     查询源标识 (用于重试分类)
 * @param tokenBudget     Token 预算（null 表示不限
 * @param modelTierChain  模型降级链（有序列表，index 0 = 最优）
 */
public record QueryConfig(
        String model,
        String fallbackModel,
        String systemPrompt,
        List<Tool> tools,
        List<Map<String, Object>> toolDefinitions,
        int maxTokens,
        int contextWindow,
        ThinkingConfig thinkingConfig,
        int maxTurns,
        String querySource,
        Integer tokenBudget,
        List<String> modelTierChain
) {
    /** 默认最大输出 token */
    public static final int DEFAULT_MAX_TOKENS = 8192;

    /** 升级后的最大输出 token */
    public static final int ESCALATED_MAX_TOKENS = 65536;

    /** 最大循环轮次 */
    public static final int DEFAULT_MAX_TURNS = 200;

    /** 最大输出恢复次数 */
    public static final int MAX_OUTPUT_TOKENS_RECOVERY_LIMIT = 3;

    /**
     * 根据模型返回推荐的 max_tokens 值。
     * <p>
     * 直接读取 {@link ModelRegistry} 中该模型的真实 maxOutputTokens 配置，
     * 并在 {@link #ESCALATED_MAX_TOKENS} 上限内做安全裁剪，避免对超大输出能力
     * 模型（如 deepseek-v4-pro=384k）一次性请求超过链路实际承载量。
     *
     * @param registry 模型注册表（必填，禁止 null；调用方应从 Spring 容器注入）
     * @param model    模型 ID
     * @return 推荐 max_tokens；找不到能力或参数缺失时回退到 {@link #DEFAULT_MAX_TOKENS}
     */
    public static int getRecommendedMaxTokens(ModelRegistry registry, String model) {
        if (registry == null || model == null || model.isBlank()) {
            return DEFAULT_MAX_TOKENS;
        }
        int maxOutput = registry.getMaxOutputTokensForModel(model);
        if (maxOutput <= 0) {
            return DEFAULT_MAX_TOKENS;
        }
        // 安全裁剪：不超过 ESCALATED_MAX_TOKENS（65536），避免链路超载
        return Math.min(maxOutput, ESCALATED_MAX_TOKENS);
    }

    /**
     * 向后兼容工厂方法 — 无 fallbackModel 和 tokenBudget。
     * 所有现有 9 参数构造点使用此方法，避免逐个修改。
     */
    public static QueryConfig withDefaults(
            String model, String systemPrompt, List<Tool> tools,
            List<Map<String, Object>> toolDefinitions, int maxTokens,
            int contextWindow, ThinkingConfig thinkingConfig,
            int maxTurns, String querySource) {
        return new QueryConfig(model, null, systemPrompt, tools,
                toolDefinitions, maxTokens, contextWindow,
                thinkingConfig, maxTurns, querySource, null, List.of());
    }
}
