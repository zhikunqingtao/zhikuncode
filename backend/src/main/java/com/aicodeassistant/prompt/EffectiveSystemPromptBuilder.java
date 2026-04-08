package com.aicodeassistant.prompt;

import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.agent.SubAgentExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

/**
 * EffectiveSystemPromptBuilder — 系统提示优先级链构建器。
 * <p>
 * 实现 5 级优先级选择链，决定最终使用的系统提示来源：
 * <p>
 * 优先级（高→低）:
 * <ol>
 *     <li>0. Override 系统提示 — 设置后替换所有其他提示（最高优先级）</li>
 *     <li>1. Coordinator 系统提示 — COORDINATOR_MODE 特性启用时使用</li>
 *     <li>2. Agent 系统提示 — 来自 mainThreadAgentDefinition
 *        <ul>
 *            <li>proactive 模式: 追加到默认提示后面（而非替换）</li>
 *            <li>非 proactive: 替换默认提示</li>
 *        </ul>
 *     </li>
 *     <li>3. Custom 系统提示 — --system-prompt CLI 参数</li>
 *     <li>4. Default 系统提示 — 标准的 Claude Code 默认提示</li>
 * </ol>
 * <p>
 * 另外: appendSystemPrompt 始终追加到最终提示末尾（除非使用 Override）
 * <p>
 * 对照源码: SPEC §3.1.1 (10659-10704行)
 *
 * @see SystemPromptBuilder
 * @see SystemPromptConfig
 */
@Service
public class EffectiveSystemPromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(EffectiveSystemPromptBuilder.class);

    private final SystemPromptBuilder systemPromptBuilder;
    private final FeatureFlagService featureFlags;

    public EffectiveSystemPromptBuilder(SystemPromptBuilder systemPromptBuilder,
                                        FeatureFlagService featureFlags) {
        this.systemPromptBuilder = systemPromptBuilder;
        this.featureFlags = featureFlags;
    }

    /**
     * 构建有效系统提示 — 5级优先级链选择。
     *
     * @param config 系统提示配置
     * @param tools  可用工具列表
     * @param model  模型名称
     * @param cwd    工作目录
     * @return 最终系统提示文本
     */
    public String buildEffectiveSystemPrompt(SystemPromptConfig config,
                                              List<Tool> tools,
                                              String model,
                                              Path cwd) {
        String basePrompt = resolveBasePrompt(config, tools, model, cwd);
        String appendPrompt = config.getAppendSystemPrompt();

        // 追加额外提示（除非使用 Override）
        if (appendPrompt != null && !appendPrompt.isBlank()
                && config.getOverrideSystemPrompt() == null) {
            return basePrompt + "\n\n" + appendPrompt;
        }

        return basePrompt;
    }

    /**
     * 简化的构建方法 — 使用默认配置。
     */
    public String buildEffectiveSystemPrompt(List<Tool> tools, String model, Path cwd) {
        return buildEffectiveSystemPrompt(SystemPromptConfig.defaults(), tools, model, cwd);
    }

    /**
     * 解析基础提示 — 优先级链核心逻辑。
     */
    private String resolveBasePrompt(SystemPromptConfig config,
                                     List<Tool> tools,
                                     String model,
                                     Path cwd) {
        // 优先级 0: Override (完全替换)
        if (config.getOverrideSystemPrompt() != null) {
            log.debug("Using override system prompt");
            return config.getOverrideSystemPrompt();
        }

        // 优先级 1: Coordinator 模式
        if (featureFlags.isEnabled("COORDINATOR_MODE")
                && config.getCoordinatorPrompt() != null) {
            log.debug("Using coordinator system prompt");
            return config.getCoordinatorPrompt();
        }

        // 优先级 2: Agent 模式
        if (config.getAgentDefinition() != null) {
            SubAgentExecutor.AgentDefinition agentDef = config.getAgentDefinition();
            String agentPrompt = agentDef.systemPromptTemplate();

            if (config.isProactive()) {
                // proactive: 追加到默认提示
                log.debug("Using proactive agent system prompt (appended to default)");
                String defaultPrompt = systemPromptBuilder.buildDefaultSystemPrompt(tools, model);
                return defaultPrompt + "\n\n" + agentPrompt;
            } else {
                // 非 proactive: 替换默认提示
                log.debug("Using agent system prompt (replaces default)");
                return agentPrompt;
            }
        }

        // 优先级 3: Custom (--system-prompt)
        if (config.getCustomSystemPrompt() != null) {
            log.debug("Using custom system prompt");
            return config.getCustomSystemPrompt();
        }

        // 优先级 4: Default
        log.debug("Using default system prompt");
        return systemPromptBuilder.buildDefaultSystemPrompt(tools, model);
    }
}
