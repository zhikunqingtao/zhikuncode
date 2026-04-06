package com.aicodeassistant.engine;

import com.aicodeassistant.llm.ThinkingConfig;
import com.aicodeassistant.tool.Tool;

import java.util.List;
import java.util.Map;

/**
 * 查询配置 — 不可变配置快照，在 queryLoop 入口构建。
 * <p>
 * 对照源码 buildQueryConfig()，包含模型选择、token 预算、工具列表等。
 *
 * @param model           模型名称
 * @param systemPrompt    系统提示
 * @param tools           可用工具列表
 * @param toolDefinitions 工具 API 定义
 * @param maxTokens       最大输出 token
 * @param contextWindow   模型上下文窗口大小
 * @param thinkingConfig  思考模式配置
 * @param maxTurns        最大循环轮次 (防止无限循环)
 * @param querySource     查询源标识 (用于重试分类)
 * @see <a href="SPEC §3.1.1a">查询主循环实现细节</a>
 */
public record QueryConfig(
        String model,
        String systemPrompt,
        List<Tool> tools,
        List<Map<String, Object>> toolDefinitions,
        int maxTokens,
        int contextWindow,
        ThinkingConfig thinkingConfig,
        int maxTurns,
        String querySource
) {
    /** 默认最大输出 token */
    public static final int DEFAULT_MAX_TOKENS = 8192;

    /** 升级后的最大输出 token */
    public static final int ESCALATED_MAX_TOKENS = 65536;

    /** 最大循环轮次 */
    public static final int DEFAULT_MAX_TURNS = 200;

    /** 最大输出恢复次数 */
    public static final int MAX_OUTPUT_TOKENS_RECOVERY_LIMIT = 3;
}
