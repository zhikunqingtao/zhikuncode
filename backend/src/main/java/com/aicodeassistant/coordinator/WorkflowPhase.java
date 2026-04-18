package com.aicodeassistant.coordinator;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 四阶段工作流阶段定义 — sealed interface + 4 个 record 实现。
 * <p>
 * 对标 Claude Code Coordinator 四阶段工作流：
 * Research → Synthesis → Implementation → Verification
 * <p>
 * 每个阶段定义：
 * <ul>
 *   <li>{@link #name()} — 阶段名称</li>
 *   <li>{@link #phasePrompt()} — 阶段引导提示词</li>
 *   <li>{@link #allowedTools()} — Coordinator 层可用工具（非 Worker 层）</li>
 *   <li>{@link #phaseIndex()} — 阶段序号 (0-3)</li>
 *   <li>{@link #entryCondition()} — 进入条件描述</li>
 *   <li>{@link #completionCondition()} — 完成条件描述</li>
 * </ul>
 * <p>
 * allowedTools 与 SubAgentExecutor.AgentDefinition 的 deniedTools 协调：
 * <ul>
 *   <li>EXPLORE/VERIFICATION/PLAN 的 deniedTools = {Agent, ExitPlanMode, FileEdit, FileWrite, NotebookEdit}</li>
 *   <li>GENERAL_PURPOSE 允许所有工具 (allowedTools = {"*"})</li>
 *   <li>CoordinatorService.COORDINATOR_ALLOWED_TOOLS = {Agent, TaskStop, SendMessage, SyntheticOutput}</li>
 * </ul>
 *
 * @see CoordinatorWorkflow
 * @see CoordinatorWorkflowEngine
 */
public sealed interface WorkflowPhase permits
        WorkflowPhase.Research,
        WorkflowPhase.Synthesis,
        WorkflowPhase.Implementation,
        WorkflowPhase.Verification {

    /** 阶段名称 */
    String name();

    /** 阶段引导提示词 */
    String phasePrompt();

    /** Coordinator 层可用工具集 */
    Set<String> allowedTools();

    /** 阶段序号 (0=Research, 1=Synthesis, 2=Implementation, 3=Verification) */
    int phaseIndex();

    /** 进入条件描述 */
    String entryCondition();

    /** 完成条件描述 */
    String completionCondition();

    /** 总阶段数 */
    int TOTAL_PHASES = 4;

    // ═══════════════════════════════════════════════════════════════
    // Phase 1: Research（调研）
    // ═══════════════════════════════════════════════════════════════

    /**
     * Research 阶段 — 派出 Explore Agent 搜索相关代码和文件。
     * <p>
     * 每个搜索任务用独立的 Agent 并行执行，收集所有发现到 Scratchpad。
     *
     * @param objective     研究目标
     * @param queries       研究查询列表
     */
    record Research(String objective, List<String> queries) implements WorkflowPhase {
        @Override
        public String name() { return "Research"; }

        @Override
        public String phasePrompt() {
            return "Investigate the codebase. Launch Explore agents to find relevant files "
                    + "and understand the problem. Each search task should use an independent Agent for parallel execution. "
                    + "Collect all findings to Scratchpad.";
        }

        @Override
        public Set<String> allowedTools() {
            // Coordinator 层：只能派 Agent + 发消息
            // Worker 层：自动应用 EXPLORE AgentDefinition 的 deniedTools
            return Set.of("Agent", "SendMessage");
        }

        @Override
        public int phaseIndex() { return 0; }

        @Override
        public String entryCondition() {
            return "Workflow started with a clear objective.";
        }

        @Override
        public String completionCondition() {
            return "All Explore agents completed and findings collected in Scratchpad.";
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Phase 2: Synthesis（综合）
    // ═══════════════════════════════════════════════════════════════

    /**
     * Synthesis 阶段 — 汇总所有 Research 发现，生成精确执行计划。
     * <p>
     * 核心原则：<b>永远不要委派理解</b>（Never Delegate Understanding）。
     * 生成的执行计划必须具体到文件路径、行号、修改内容。
     *
     * @param researchSummary 研究摘要
     * @param insights        关键洞察列表
     */
    record Synthesis(String researchSummary, List<String> insights) implements WorkflowPhase {
        @Override
        public String name() { return "Synthesis"; }

        @Override
        public String phasePrompt() {
            return "Read all research findings. Understand the problem deeply. "
                    + "Craft implementation specs with specific file paths and line numbers. "
                    + "CRITICAL: Never delegate understanding — each worker instruction must be "
                    + "complete, specific, and self-contained.";
        }

        @Override
        public Set<String> allowedTools() {
            return Set.of("Agent", "SendMessage", "SyntheticOutput");
        }

        @Override
        public int phaseIndex() { return 1; }

        @Override
        public String entryCondition() {
            return "Research phase completed with sufficient findings collected.";
        }

        @Override
        public String completionCondition() {
            return "Precise execution plan generated with specific file paths, line numbers, and modification details.";
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Phase 3: Implementation（实施）
    // ═══════════════════════════════════════════════════════════════

    /**
     * Implementation 阶段 — 将执行计划分配给 Worker Agent。
     * <p>
     * 每个 Worker 收到完整、具体、自含的指令。监控 Worker 进度，处理冲突。
     *
     * @param plan  执行计划概述
     * @param tasks 具体任务列表
     */
    record Implementation(String plan, List<String> tasks) implements WorkflowPhase {
        @Override
        public String name() { return "Implementation"; }

        @Override
        public String phasePrompt() {
            return "Assign execution plans to Worker agents. Each worker receives complete, "
                    + "specific, self-contained instructions with exact file paths and changes. "
                    + "Monitor worker progress and handle conflicts.";
        }

        @Override
        public Set<String> allowedTools() {
            // Worker 层使用 GENERAL_PURPOSE AgentDefinition（allowedTools = Set.of("*")）
            return Set.of("Agent", "SendMessage", "TaskStop");
        }

        @Override
        public int phaseIndex() { return 2; }

        @Override
        public String entryCondition() {
            return "Synthesis phase completed with a concrete execution plan.";
        }

        @Override
        public String completionCondition() {
            return "All Worker agents completed their assigned tasks successfully.";
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Phase 4: Verification（验证）
    // ═══════════════════════════════════════════════════════════════

    /**
     * Verification 阶段 — 派出 Verification Agent 检查所有修改。
     * <p>
     * 验证 Agent 必须实际执行命令（测试、lint、build），
     * 不接受"代码看起来正确"这样的验证。
     *
     * @param testCriteria        测试标准列表
     * @param validationStrategy  验证策略描述
     */
    record Verification(List<String> testCriteria, String validationStrategy) implements WorkflowPhase {
        @Override
        public String name() { return "Verification"; }

        @Override
        public String phasePrompt() {
            return "Launch Verification agents to test all changes. Agents must run actual commands "
                    + "(tests, lint, build) — do NOT accept 'the code looks correct' as verification. "
                    + "Report any failures for remediation.";
        }

        @Override
        public Set<String> allowedTools() {
            // Worker 层使用 VERIFICATION AgentDefinition（deniedTools = FileEdit/FileWrite）
            return Set.of("Agent", "SendMessage");
        }

        @Override
        public int phaseIndex() { return 3; }

        @Override
        public String entryCondition() {
            return "Implementation phase completed with all changes applied.";
        }

        @Override
        public String completionCondition() {
            return "All verification agents ran actual commands and all checks passed.";
        }
    }
}
