package com.aicodeassistant.prompt;

import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.coordinator.CoordinatorPromptBuilder;
import com.aicodeassistant.coordinator.CoordinatorService;
import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.agent.SubAgentExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 *     <li>4. Default 系统提示 — 标准默认提示</li>
 * </ol>
 * <p>
 * 另外（除非使用 Override）:
 * <ul>
 *     <li>记忆段（MEMORY.md / zhikun.md / zhikun.local.md）由 {@link MemoryPromptService}
 *         每次构建时重新读取并追加；实际选中 Custom 时不自动注入记忆</li>
 *     <li>appendSystemPrompt 追加到最终提示末尾</li>
 * </ul>
 * 拼接顺序: base → memory → append
 * <p>
 *
 * @see SystemPromptBuilder
 * @see SystemPromptConfig
 */
@Service
public class EffectiveSystemPromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(EffectiveSystemPromptBuilder.class);

    private final SystemPromptBuilder systemPromptBuilder;
    private final FeatureFlagService featureFlags;
    private final CoordinatorPromptBuilder coordinatorPromptBuilder;
    private final CoordinatorService coordinatorService;
    private final MemoryPromptService memoryPromptService;

    /** 无记忆注入的构造器（兼容既有测试）。 */
    public EffectiveSystemPromptBuilder(SystemPromptBuilder systemPromptBuilder,
                                        FeatureFlagService featureFlags,
                                        CoordinatorPromptBuilder coordinatorPromptBuilder,
                                        CoordinatorService coordinatorService) {
        this(systemPromptBuilder, featureFlags, coordinatorPromptBuilder, coordinatorService, null);
    }

    @Autowired
    public EffectiveSystemPromptBuilder(SystemPromptBuilder systemPromptBuilder,
                                        FeatureFlagService featureFlags,
                                        CoordinatorPromptBuilder coordinatorPromptBuilder,
                                        CoordinatorService coordinatorService,
                                        MemoryPromptService memoryPromptService) {
        this.systemPromptBuilder = systemPromptBuilder;
        this.featureFlags = featureFlags;
        this.coordinatorPromptBuilder = coordinatorPromptBuilder;
        this.coordinatorService = coordinatorService;
        this.memoryPromptService = memoryPromptService;
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
        ResolvedBasePrompt basePrompt = resolveBasePrompt(config, tools, model, cwd);

        // Override 完全替换：不追加记忆和额外提示
        if (config.getOverrideSystemPrompt() != null) {
            return basePrompt.text();
        }

        StringBuilder result = new StringBuilder(basePrompt.text());

        // 由实际胜出的来源决定是否注入，不能仅凭 custom 配置存在就跳过更高优先级来源的记忆。
        if (basePrompt.injectMemory() && memoryPromptService != null) {
            String memoryPrompt = memoryPromptService.buildMemoryPrompt(cwd);
            if (memoryPrompt != null && !memoryPrompt.isBlank()) {
                result.append("\n\n").append(memoryPrompt);
            }
        }

        // 追加额外提示
        String appendPrompt = config.getAppendSystemPrompt();
        if (appendPrompt != null && !appendPrompt.isBlank()) {
            result.append("\n\n").append(appendPrompt);
        }

        return result.toString();
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
    private ResolvedBasePrompt resolveBasePrompt(SystemPromptConfig config,
                                                 List<Tool> tools,
                                                 String model,
                                                 Path cwd) {
        // 优先级 0: Override (完全替换)
        if (config.getOverrideSystemPrompt() != null) {
            log.debug("Using override system prompt");
            return new ResolvedBasePrompt(config.getOverrideSystemPrompt(), false);
        }

        // 优先级 1: Coordinator 模式
        // 必须同时满足 feature flag + 环境变量 ZHIKUN_COORDINATOR_MODE
        // 且当前无 Agent 定义时才激活 Coordinator 模式
        // ERR-2 fix: 使用语义化方法，消除内联条件组合
        if (coordinatorService.isCoordinatorTopLevel(config.getAgentDefinition())) {
            String coordinatorPrompt = config.getCoordinatorPrompt();
            if (coordinatorPrompt == null && coordinatorPromptBuilder != null) {
                coordinatorPrompt = coordinatorPromptBuilder
                        .buildCoordinatorPromptForProject(
                                config.getSessionId(), cwd);
            }
            if (coordinatorPrompt != null) {
                log.debug("Using coordinator system prompt");
                return new ResolvedBasePrompt(coordinatorPrompt, true);
            }
        }

        // 优先级 2: Agent 模式
        if (config.getAgentDefinition() != null) {
            SubAgentExecutor.AgentDefinition agentDef = config.getAgentDefinition();
            String agentPrompt = agentDef.systemPromptTemplate();

            if (config.isProactive()) {
                // proactive: 追加到默认提示
                log.debug("Using proactive agent system prompt (appended to default)");
                String defaultPrompt = systemPromptBuilder
                        .buildDefaultSystemPrompt(
                                tools, model, cwd, config.getSessionId());
                return new ResolvedBasePrompt(defaultPrompt + "\n\n" + agentPrompt, true);
            } else {
                // 非 proactive: 替换默认提示
                log.debug("Using agent system prompt (replaces default)");
                return new ResolvedBasePrompt(agentPrompt, true);
            }
        }

        // 优先级 3: Custom (--system-prompt)
        if (config.getCustomSystemPrompt() != null) {
            log.debug("Using custom system prompt");
            return new ResolvedBasePrompt(config.getCustomSystemPrompt(), false);
        }

        // 优先级 4: Default
        log.debug("Using default system prompt");
        return new ResolvedBasePrompt(systemPromptBuilder.buildDefaultSystemPrompt(
                tools, model, cwd, config.getSessionId()), true);
    }

    private record ResolvedBasePrompt(String text, boolean injectMemory) {}
}
