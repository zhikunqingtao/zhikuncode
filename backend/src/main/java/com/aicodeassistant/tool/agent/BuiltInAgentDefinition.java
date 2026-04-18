package com.aicodeassistant.tool.agent;

import java.util.Set;

/**
 * 内置代理类型定义 — sealed interface 类型化系统。
 * <p>
 * 将 {@link SubAgentExecutor.AgentDefinition} 中的 5 种静态配置升级为类型安全的 sealed interface，
 * 利用 Java 21 的 sealed classes + pattern matching switch 实现编译期穷尽性检查。
 * <p>
 * 每种实现对齐 {@code SubAgentExecutor.AgentDefinition} 中已有的配置：
 * <ul>
 *   <li>{@link GeneralPurposeAgent} — 通用Agent，完整工具集，默认模型</li>
 *   <li>{@link ExploreAgent} — 探索Agent，禁止编辑工具，偏好轻量模型（haiku）</li>
 *   <li>{@link VerificationAgent} — 验证Agent，禁止编辑工具，继承父级模型</li>
 *   <li>{@link PlanAgent} — 规划Agent，禁止编辑工具，省略 CLAUDE.md</li>
 *   <li>{@link GuideAgent} — 引导Agent，仅只读工具，偏好轻量模型（haiku）</li>
 * </ul>
 *
 * @see SubAgentExecutor.AgentDefinition
 * @see AgentStrategyFactory
 */
public sealed interface BuiltInAgentDefinition permits
        BuiltInAgentDefinition.GeneralPurposeAgent,
        BuiltInAgentDefinition.ExploreAgent,
        BuiltInAgentDefinition.VerificationAgent,
        BuiltInAgentDefinition.PlanAgent,
        BuiltInAgentDefinition.GuideAgent {

    /** Agent 类型标识（用于 switch 路由和 API 输出） */
    String type();

    /** 类型描述（用于 UI 展示） */
    String description();

    /** 最大对话轮次 */
    int maxTurns();

    /** 允许的工具集；null 表示不限制（由 deniedTools 控制） */
    Set<String> allowedTools();

    /** 禁止的工具集；null 表示不禁止 */
    Set<String> deniedTools();

    /**
     * 模型别名覆盖；null 表示继承父级模型。
     * 值为 application.yml 中 agent.model-aliases 的键（如 "haiku"、"sonnet"、"opus"）。
     */
    String modelOverride();

    /** 是否省略 CLAUDE.md 注入 */
    boolean omitClaudeMd();

    /** 系统提示模板 */
    String systemPromptTemplate();

    // ═══ 5 种内置 Agent 类型 ═══

    /**
     * 通用Agent — 完整工具集，无限制。
     * <p>对齐 {@code SubAgentExecutor.AgentDefinition.GENERAL_PURPOSE}（L897-899）
     */
    record GeneralPurposeAgent() implements BuiltInAgentDefinition {
        @Override public String type() { return "general-purpose"; }
        @Override public String description() { return "Full-capability agent for implementation tasks"; }
        @Override public int maxTurns() { return 30; }
        @Override public Set<String> allowedTools() { return Set.of("*"); }
        @Override public Set<String> deniedTools() { return null; }
        @Override public String modelOverride() { return null; }
        @Override public boolean omitClaudeMd() { return false; }
        @Override public String systemPromptTemplate() { return SubAgentExecutor.GENERAL_PURPOSE_AGENT_PROMPT; }

        /** 创建默认配置实例 */
        public static GeneralPurposeAgent createDefault() { return new GeneralPurposeAgent(); }
    }

    /**
     * 探索Agent — 只读搜索，禁止编辑工具，偏好轻量模型。
     * <p>对齐 {@code SubAgentExecutor.AgentDefinition.EXPLORE}（L885-888）
     */
    record ExploreAgent() implements BuiltInAgentDefinition {
        private static final Set<String> DENIED = Set.of(
                "Agent", "ExitPlanMode", "FileEdit", "FileWrite", "NotebookEdit");

        @Override public String type() { return "explore"; }
        @Override public String description() { return "Search and read code, files, documentation"; }
        @Override public int maxTurns() { return 30; }
        @Override public Set<String> allowedTools() { return null; }
        @Override public Set<String> deniedTools() { return DENIED; }
        @Override public String modelOverride() { return "haiku"; }
        @Override public boolean omitClaudeMd() { return true; }
        @Override public String systemPromptTemplate() { return SubAgentExecutor.EXPLORE_AGENT_PROMPT; }

        /** 创建默认配置实例 */
        public static ExploreAgent createDefault() { return new ExploreAgent(); }
    }

    /**
     * 验证Agent — 测试验证，禁止编辑工具，继承父级模型。
     * <p>对齐 {@code SubAgentExecutor.AgentDefinition.VERIFICATION}（L889-892）
     */
    record VerificationAgent() implements BuiltInAgentDefinition {
        private static final Set<String> DENIED = Set.of(
                "Agent", "ExitPlanMode", "FileEdit", "FileWrite", "NotebookEdit");

        @Override public String type() { return "verification"; }
        @Override public String description() { return "Test and verify changes with actual commands"; }
        @Override public int maxTurns() { return 30; }
        @Override public Set<String> allowedTools() { return null; }
        @Override public Set<String> deniedTools() { return DENIED; }
        @Override public String modelOverride() { return null; }
        @Override public boolean omitClaudeMd() { return false; }
        @Override public String systemPromptTemplate() { return SubAgentExecutor.VERIFICATION_AGENT_PROMPT; }

        /** 创建默认配置实例 */
        public static VerificationAgent createDefault() { return new VerificationAgent(); }
    }

    /**
     * 规划Agent — 分析规划，禁止编辑工具，省略 CLAUDE.md。
     * <p>对齐 {@code SubAgentExecutor.AgentDefinition.PLAN}（L893-896）
     */
    record PlanAgent() implements BuiltInAgentDefinition {
        private static final Set<String> DENIED = Set.of(
                "Agent", "ExitPlanMode", "FileEdit", "FileWrite", "NotebookEdit");

        @Override public String type() { return "plan"; }
        @Override public String description() { return "Create detailed implementation plans"; }
        @Override public int maxTurns() { return 30; }
        @Override public Set<String> allowedTools() { return null; }
        @Override public Set<String> deniedTools() { return DENIED; }
        @Override public String modelOverride() { return null; }
        @Override public boolean omitClaudeMd() { return true; }
        @Override public String systemPromptTemplate() { return SubAgentExecutor.PLAN_AGENT_PROMPT; }

        /** 创建默认配置实例 */
        public static PlanAgent createDefault() { return new PlanAgent(); }
    }

    /**
     * 引导Agent — 只读文档/搜索工具，偏好轻量模型。
     * <p>对齐 {@code SubAgentExecutor.AgentDefinition.GUIDE}（L900-903）
     */
    record GuideAgent() implements BuiltInAgentDefinition {
        private static final Set<String> ALLOWED = Set.of(
                "Glob", "Grep", "FileRead", "WebFetch", "WebSearch");

        @Override public String type() { return "guide"; }
        @Override public String description() { return "Claude Code usage guide and documentation expert"; }
        @Override public int maxTurns() { return 30; }
        @Override public Set<String> allowedTools() { return ALLOWED; }
        @Override public Set<String> deniedTools() { return null; }
        @Override public String modelOverride() { return "haiku"; }
        @Override public boolean omitClaudeMd() { return false; }
        @Override public String systemPromptTemplate() { return SubAgentExecutor.GUIDE_AGENT_PROMPT; }

        /** 创建默认配置实例 */
        public static GuideAgent createDefault() { return new GuideAgent(); }
    }
}
