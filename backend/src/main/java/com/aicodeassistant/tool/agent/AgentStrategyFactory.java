package com.aicodeassistant.tool.agent;

import com.aicodeassistant.tool.agent.BuiltInAgentDefinition.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Agent 智能路由工厂 — 根据任务描述自动选择最佳 Agent 类型。
 * <p>
 * 核心能力：
 * <ul>
 *   <li>{@link #selectAgent(String)} — 根据任务内容关键词匹配 Agent 类型（智能路由）</li>
 *   <li>{@link #getAgent(String)} — 按类型名称获取 Agent 定义（手动覆盖）</li>
 *   <li>{@link #getAllTypes()} — 获取所有可用 Agent 类型</li>
 *   <li>{@link #toAgentDefinition(BuiltInAgentDefinition)} — 类型安全转换到现有 AgentDefinition</li>
 * </ul>
 * <p>
 * 路由规则使用 {@code \b} 完整词汇边界匹配，避免误匹配（如 "plan" 不会匹配到 "explanation"）。
 * 手动指定类型时不走自动检测逻辑，确保可覆盖性。
 *
 * @see BuiltInAgentDefinition
 * @see SubAgentExecutor.AgentDefinition
 */
@Service
public class AgentStrategyFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentStrategyFactory.class);

    /**
     * 关键词 → Agent 类型路由表。
     * <p>
     * 使用 LinkedHashMap 保证匹配优先级顺序（先匹配的先命中）。
     * Pattern 使用 {@code \b} 词边界 + 大小写不敏感匹配。
     */
    private static final Map<Pattern, BuiltInAgentDefinition> KEYWORD_ROUTES;

    static {
        // LinkedHashMap 保证插入顺序 = 匹配优先级
        KEYWORD_ROUTES = new LinkedHashMap<>();
        KEYWORD_ROUTES.put(
                Pattern.compile("(?i)\\b(search|find|explore|look for|investigate)\\b"),
                new ExploreAgent());
        KEYWORD_ROUTES.put(
                Pattern.compile("(?i)\\b(test|verify|check|validate|lint|build)\\b"),
                new VerificationAgent());
        KEYWORD_ROUTES.put(
                Pattern.compile("(?i)\\b(plan|design|architect|outline)\\b"),
                new PlanAgent());
        KEYWORD_ROUTES.put(
                Pattern.compile("(?i)\\b(guide|help|how to|usage|tutorial)\\b"),
                new GuideAgent());
    }

    /**
     * 所有内置 Agent 类型（按名称索引）。
     */
    private static final Map<String, BuiltInAgentDefinition> TYPE_REGISTRY = Map.of(
            "general-purpose", new GeneralPurposeAgent(),
            "explore", new ExploreAgent(),
            "verification", new VerificationAgent(),
            "plan", new PlanAgent(),
            "guide", new GuideAgent()
    );

    /**
     * 根据任务描述智能选择最合适的 Agent 类型。
     * <p>
     * 按 KEYWORD_ROUTES 优先级顺序匹配任务文本中的关键词，
     * 匹配失败时默认返回 {@link GeneralPurposeAgent}。
     *
     * @param taskDescription 任务描述文本
     * @return 匹配的 Agent 类型定义
     */
    public BuiltInAgentDefinition selectAgent(String taskDescription) {
        if (taskDescription == null || taskDescription.isBlank()) {
            log.debug("selectAgent: empty task description, defaulting to GeneralPurpose");
            return new GeneralPurposeAgent();
        }
        for (var entry : KEYWORD_ROUTES.entrySet()) {
            if (entry.getKey().matcher(taskDescription).find()) {
                BuiltInAgentDefinition matched = entry.getValue();
                log.debug("selectAgent: matched '{}' → {}", taskDescription, matched.type());
                return matched;
            }
        }
        log.debug("selectAgent: no keyword match for '{}', defaulting to GeneralPurpose", taskDescription);
        return new GeneralPurposeAgent();
    }

    /**
     * 按类型名称获取 Agent 定义（手动覆盖模式）。
     * <p>
     * 支持的名称：general-purpose, explore, verification, plan, guide
     *
     * @param typeName Agent 类型名称
     * @return 匹配的 Agent 定义，不存在时返回 empty
     */
    public Optional<BuiltInAgentDefinition> getAgent(String typeName) {
        if (typeName == null) return Optional.empty();
        return Optional.ofNullable(TYPE_REGISTRY.get(typeName.toLowerCase()));
    }

    /**
     * 获取所有可用的 Agent 类型。
     *
     * @return 不可变的类型名称 → Agent 定义映射
     */
    public Map<String, BuiltInAgentDefinition> getAllTypes() {
        return TYPE_REGISTRY;
    }

    /**
     * 获取所有 Agent 类型的有序列表。
     *
     * @return 所有内置 Agent 类型列表
     */
    public List<BuiltInAgentDefinition> getAllTypesList() {
        return List.of(
                new GeneralPurposeAgent(),
                new ExploreAgent(),
                new VerificationAgent(),
                new PlanAgent(),
                new GuideAgent()
        );
    }

    /**
     * Java 21 sealed interface + switch pattern matching 类型安全路由。
     * <p>
     * 将 {@link BuiltInAgentDefinition} 转换为现有的 {@link SubAgentExecutor.AgentDefinition}，
     * 确保与已有系统兼容。编译器保证穷尽性检查，新增 Agent 类型时自动提示未处理的分支。
     *
     * @param builtIn sealed interface 类型的 Agent 定义
     * @return 对应的 SubAgentExecutor.AgentDefinition 实例
     */
    public SubAgentExecutor.AgentDefinition toAgentDefinition(BuiltInAgentDefinition builtIn) {
        return switch (builtIn) {
            case ExploreAgent e -> SubAgentExecutor.AgentDefinition.EXPLORE;
            case VerificationAgent v -> SubAgentExecutor.AgentDefinition.VERIFICATION;
            case PlanAgent p -> SubAgentExecutor.AgentDefinition.PLAN;
            case GeneralPurposeAgent g -> SubAgentExecutor.AgentDefinition.GENERAL_PURPOSE;
            case GuideAgent gu -> SubAgentExecutor.AgentDefinition.GUIDE;
        };  // 编译器强制穷尽性检查 — 新增 permits 类型时此处报错
    }

    /**
     * 智能路由 + 手动覆盖组合方法。
     * <p>
     * 如果指定了 typeName 且有效，直接返回对应类型（手动覆盖）；
     * 否则根据 taskDescription 智能路由。
     *
     * @param typeName        手动指定的类型名称（可为 null）
     * @param taskDescription 任务描述（用于智能路由）
     * @return 选中的 Agent 类型定义
     */
    public BuiltInAgentDefinition resolve(String typeName, String taskDescription) {
        // 手动覆盖优先
        if (typeName != null && !typeName.isBlank()) {
            Optional<BuiltInAgentDefinition> manual = getAgent(typeName);
            if (manual.isPresent()) {
                log.debug("resolve: manual override '{}' → {}", typeName, manual.get().type());
                return manual.get();
            }
            log.warn("resolve: unknown type '{}', falling back to smart routing", typeName);
        }
        // 智能路由
        return selectAgent(taskDescription);
    }
}
