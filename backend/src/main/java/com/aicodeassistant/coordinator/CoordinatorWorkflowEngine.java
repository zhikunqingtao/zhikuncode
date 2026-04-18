package com.aicodeassistant.coordinator;

import com.aicodeassistant.websocket.ServerMessage;
import com.aicodeassistant.websocket.WebSocketController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Coordinator 四阶段工作流编排引擎。
 * <p>
 * 职责：
 * <ul>
 *   <li>管理工作流实例生命周期</li>
 *   <li>阶段自动检测：根据 LLM 输出内容判断当前应处于哪个阶段</li>
 *   <li>"不委派理解"原则验证：检测是否存在未经充分研究就直接实现的情况</li>
 *   <li>假阳性处理：误判时记录 WARN 日志但不阻塞流程</li>
 *   <li>通过 WebSocket 推送阶段变更到前端</li>
 * </ul>
 *
 * @see CoordinatorWorkflow
 * @see WorkflowPhase
 * @see SwarmService
 */
@Component
public class CoordinatorWorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(CoordinatorWorkflowEngine.class);

    // ═══════════════════════════════════════════════════════════════
    // "不委派理解"模糊指令检测
    // ═══════════════════════════════════════════════════════════════

    /** 模糊指令正则 — 检测委派理解的反模式 */
    private static final List<Pattern> VAGUE_PATTERNS = List.of(
            Pattern.compile("(?i)based on (your|the) (findings|research)"),
            Pattern.compile("(?i)fix the (bug|issue|problem)"),
            Pattern.compile("(?i)using what you (learned|found)"),
            Pattern.compile("(?i)implement the (solution|fix)")
    );

    /** Prompt 最短有效长度 — 低于此值警告"委派理解" */
    private static final int MIN_PROMPT_LENGTH = 100;       // ASCII/英文场景
    private static final int MIN_PROMPT_LENGTH_CJK = 50;    // CJK（中日韩）场景

    /** 阶段检测关键词 */
    private static final Set<String> RESEARCH_KEYWORDS = Set.of(
            "explore", "investigate", "search", "find", "look for", "research",
            "调研", "搜索", "查找", "探索");
    private static final Set<String> SYNTHESIS_KEYWORDS = Set.of(
            "synthesize", "summarize", "plan", "design", "craft",
            "综合", "总结", "规划", "设计");
    private static final Set<String> IMPLEMENTATION_KEYWORDS = Set.of(
            "implement", "execute", "apply", "modify", "write", "create",
            "实现", "执行", "修改", "编写", "创建");
    private static final Set<String> VERIFICATION_KEYWORDS = Set.of(
            "verify", "test", "validate", "check", "lint", "build",
            "验证", "测试", "校验", "检查");

    // ═══════════════════════════════════════════════════════════════
    // 依赖
    // ═══════════════════════════════════════════════════════════════

    private final SwarmService swarmService;
    private final CoordinatorService coordinatorService;
    private final WebSocketController webSocketController;

    /** 活跃工作流 (sessionId → CoordinatorWorkflow) */
    private final ConcurrentHashMap<String, CoordinatorWorkflow> activeWorkflows = new ConcurrentHashMap<>();

    public CoordinatorWorkflowEngine(SwarmService swarmService,
                                      @Lazy CoordinatorService coordinatorService,
                                      @Lazy WebSocketController webSocketController) {
        this.swarmService = swarmService;
        this.coordinatorService = coordinatorService;
        this.webSocketController = webSocketController;
    }

    // ═══════════════════════════════════════════════════════════════
    // 1. 工作流管理
    // ═══════════════════════════════════════════════════════════════

    /**
     * 创建并启动完整工作流。
     *
     * @param sessionId 会话 ID
     * @param objective 工作流目标（用户提出的复杂任务描述）
     * @return CoordinatorWorkflow 实例
     */
    public CoordinatorWorkflow executeWorkflow(String sessionId, String objective) {
        String workflowId = "wf-" + UUID.randomUUID().toString().substring(0, 8);

        CoordinatorWorkflow workflow = new CoordinatorWorkflow(workflowId, objective);
        activeWorkflows.put(sessionId, workflow);

        // 进入 Research 阶段
        WorkflowPhase.Research research = workflow.startWorkflow();

        log.info("Workflow engine started: workflowId={}, sessionId={}, objective={}",
                workflowId, sessionId, truncate(objective, 80));

        // 推送初始阶段更新到前端
        pushPhaseUpdate(sessionId, workflow, research);

        return workflow;
    }

    /**
     * 推进工作流到下一阶段。
     *
     * @param sessionId     会话 ID
     * @param resultSummary 当前阶段结果摘要
     * @return 下一阶段，若已完成返回 null
     */
    public WorkflowPhase advanceWorkflow(String sessionId, String resultSummary) {
        CoordinatorWorkflow workflow = activeWorkflows.get(sessionId);
        if (workflow == null) {
            log.warn("No active workflow for session: {}", sessionId);
            return null;
        }

        WorkflowPhase previous = workflow.getCurrentPhase();
        WorkflowPhase next = workflow.advancePhase(resultSummary);

        if (next != null) {
            pushPhaseUpdate(sessionId, workflow, next);
        } else {
            // 工作流完成
            pushPhaseComplete(sessionId, workflow);
        }

        log.info("Workflow advanced: {} → {} (session={})",
                previous != null ? previous.name() : "N/A",
                next != null ? next.name() : "COMPLETED",
                sessionId);

        return next;
    }

    /**
     * 获取当前会话的活跃工作流。
     */
    public CoordinatorWorkflow getActiveWorkflow(String sessionId) {
        return activeWorkflows.get(sessionId);
    }

    /**
     * 取消工作流。
     */
    public void cancelWorkflow(String sessionId) {
        CoordinatorWorkflow workflow = activeWorkflows.remove(sessionId);
        if (workflow != null) {
            workflow.cancel();
            pushPhaseComplete(sessionId, workflow);
            log.info("Workflow cancelled: {} (session={})", workflow.getWorkflowId(), sessionId);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 2. 阶段自动检测
    // ═══════════════════════════════════════════════════════════════

    /**
     * 根据 LLM 输出内容自动检测当前应处于哪个阶段。
     * <p>
     * 基于工具调用模式和关键词启发式判断。
     * 重要：这是推断而非硬编码，允许假阳性（记录日志但不阻塞）。
     *
     * @param llmOutput LLM 输出文本
     * @return 检测到的阶段
     */
    public WorkflowPhase detectPhase(String llmOutput) {
        if (llmOutput == null || llmOutput.isBlank()) {
            return new WorkflowPhase.Research("", List.of());
        }

        String lower = llmOutput.toLowerCase();

        // 反向优先级检测（高阶段优先）
        int verifyScore = countKeywordMatches(lower, VERIFICATION_KEYWORDS);
        int implScore = countKeywordMatches(lower, IMPLEMENTATION_KEYWORDS);
        int synthScore = countKeywordMatches(lower, SYNTHESIS_KEYWORDS);
        int researchScore = countKeywordMatches(lower, RESEARCH_KEYWORDS);

        // 工具调用模式检测
        boolean hasFileEdit = lower.contains("fileedit") || lower.contains("filewrite");
        boolean hasTestOrBuild = lower.contains("bash") && (lower.contains("test") || lower.contains("build") || lower.contains("lint"));
        boolean hasSyntheticOutput = lower.contains("syntheticoutput");

        // 调整分数
        if (hasFileEdit) implScore += 3;
        if (hasTestOrBuild) verifyScore += 3;
        if (hasSyntheticOutput) synthScore += 2;

        // 选择最高分阶段
        int maxScore = Math.max(Math.max(researchScore, synthScore), Math.max(implScore, verifyScore));

        if (maxScore == 0) {
            return new WorkflowPhase.Research("", List.of());
        }

        if (verifyScore == maxScore) {
            return new WorkflowPhase.Verification(List.of(), "");
        }
        if (implScore == maxScore) {
            return new WorkflowPhase.Implementation("", List.of());
        }
        if (synthScore == maxScore) {
            return new WorkflowPhase.Synthesis("", List.of());
        }
        return new WorkflowPhase.Research("", List.of());
    }

    /**
     * 检测阶段并与工作流当前阶段对比。
     * 如果检测到阶段跳跃（如跳过 Synthesis 直接 Implementation），记录 WARN 日志。
     *
     * @param sessionId 会话 ID
     * @param llmOutput LLM 输出
     * @return 检测到的阶段
     */
    public WorkflowPhase detectAndValidatePhase(String sessionId, String llmOutput) {
        WorkflowPhase detected = detectPhase(llmOutput);

        CoordinatorWorkflow workflow = activeWorkflows.get(sessionId);
        if (workflow == null) return detected;

        WorkflowPhase current = workflow.getCurrentPhase();
        if (current == null) return detected;

        // 检测阶段跳跃
        if (detected.phaseIndex() > current.phaseIndex() + 1) {
            log.warn("Phase skip detected in workflow {}: current={} (index={}), detected={} (index={}). "
                            + "This may be a false positive — not blocking execution.",
                    workflow.getWorkflowId(), current.name(), current.phaseIndex(),
                    detected.name(), detected.phaseIndex());

            // 推送阶段跳转警告（通过 notification，不阻塞）
            try {
                webSocketController.sendNotification(sessionId,
                        "workflow-phase-skip",
                        "warn",
                        String.format("Phase skip detected: %s → %s (expected sequential progression)",
                                current.name(), detected.name()),
                        8000);
            } catch (Exception e) {
                log.debug("Failed to push phase skip warning: {}", e.getMessage());
            }
        }

        return detected;
    }

    // ═══════════════════════════════════════════════════════════════
    // 3. "不委派理解"原则验证
    // ═══════════════════════════════════════════════════════════════

    /**
     * 验证 Agent 派发指令的质量。
     * <p>
     * 检查 AgentTool 的 prompt 参数是否包含足够具体的信息。
     * 初期仅作为 WARN 日志，不阻断执行（防止假阳性影响体验）。
     *
     * @param phase       当前工作流阶段
     * @param agentPrompt Agent 任务 prompt
     * @return 验证结果
     */
    public ValidationResult validateDelegation(WorkflowPhase phase, String agentPrompt) {
        List<String> warnings = new ArrayList<>();

        // 1. 检查 prompt 长度（过短意味着委派理解）
        int effectiveMinLength = containsCjk(agentPrompt) ? MIN_PROMPT_LENGTH_CJK : MIN_PROMPT_LENGTH;
        if (agentPrompt == null || agentPrompt.length() < effectiveMinLength) {
            warnings.add(String.format(
                    "Prompt too short (%d chars < %d minimum). Likely delegating understanding.",
                    agentPrompt != null ? agentPrompt.length() : 0, effectiveMinLength));
        }

        // 2. 检查是否包含具体文件路径、行号、变量名等
        boolean hasSpecificInfo = agentPrompt != null && (
                agentPrompt.contains("/") ||                                  // 文件路径
                agentPrompt.matches("(?s).*:\\d+.*") ||                       // 行号
                agentPrompt.matches("(?s).*\\b[A-Z][a-zA-Z]+\\.[a-zA-Z]+\\(.*") // 方法名
        );
        if (!hasSpecificInfo && phase instanceof WorkflowPhase.Implementation) {
            // 仅在 Implementation 阶段严格要求具体信息
            warnings.add("Prompt lacks specific file paths, line numbers, or method names (required in Implementation phase).");
        }

        // 3. 检查是否包含模糊指令
        if (agentPrompt != null) {
            for (Pattern pattern : VAGUE_PATTERNS) {
                if (pattern.matcher(agentPrompt).find()) {
                    warnings.add("Vague delegation detected: '" + pattern.pattern() + "'");
                }
            }
        }

        // 记录警告（初期仅日志，不阻断）
        ValidationSeverity severity;
        if (warnings.isEmpty()) {
            severity = ValidationSeverity.OK;
        } else {
            severity = ValidationSeverity.WARN;
            log.warn("Delegation quality warning (phase={}): {}", phase.name(), warnings);
        }

        return new ValidationResult(warnings.isEmpty(), warnings, severity);
    }

    /**
     * 验证并推送委派警告到前端。
     *
     * @param sessionId   会话 ID
     * @param phase       当前阶段
     * @param agentPrompt Agent 任务 prompt
     * @return 验证结果
     */
    public ValidationResult validateAndNotify(String sessionId, WorkflowPhase phase, String agentPrompt) {
        ValidationResult result = validateDelegation(phase, agentPrompt);

        if (!result.valid() && !result.warnings().isEmpty()) {
            // 推送委派警告到前端（通过 notification）
            String warningMsg = String.join("; ", result.warnings());
            try {
                webSocketController.sendNotification(sessionId,
                        "delegation-warning",
                        "warn",
                        "Delegation quality warning: " + truncate(warningMsg, 200),
                        10000);
            } catch (Exception e) {
                log.debug("Failed to push delegation warning: {}", e.getMessage());
            }
        }

        return result;
    }

    // ═══════════════════════════════════════════════════════════════
    // 验证结果类型
    // ═══════════════════════════════════════════════════════════════

    /** 验证严重度 */
    public enum ValidationSeverity { OK, WARN, ERROR }

    /** 验证结果 */
    public record ValidationResult(
            boolean valid,
            List<String> warnings,
            ValidationSeverity severity
    ) {}

    // ═══════════════════════════════════════════════════════════════
    // WebSocket 推送
    // ═══════════════════════════════════════════════════════════════

    /**
     * 推送阶段变更到前端。
     */
    private void pushPhaseUpdate(String sessionId, CoordinatorWorkflow workflow, WorkflowPhase phase) {
        if (sessionId == null) return;

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("workflowId", workflow.getWorkflowId());
            payload.put("phaseName", phase.name());
            payload.put("status", workflow.getStatus().name());
            payload.put("phaseIndex", phase.phaseIndex());
            payload.put("totalPhases", WorkflowPhase.TOTAL_PHASES);
            payload.put("phasePrompt", phase.phasePrompt());
            payload.put("objective", workflow.getObjective());

            webSocketController.pushToUser(sessionId, "workflow_phase_update", payload);
        } catch (Exception e) {
            log.debug("Failed to push workflow phase update: {}", e.getMessage());
        }
    }

    /**
     * 推送工作流完成到前端。
     */
    private void pushPhaseComplete(String sessionId, CoordinatorWorkflow workflow) {
        if (sessionId == null) return;

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("workflowId", workflow.getWorkflowId());
            payload.put("phaseName", "");
            payload.put("status", workflow.getStatus().name());
            payload.put("phaseIndex", -1);
            payload.put("totalPhases", WorkflowPhase.TOTAL_PHASES);
            payload.put("phasePrompt", "");
            payload.put("objective", workflow.getObjective());

            webSocketController.pushToUser(sessionId, "workflow_phase_update", payload);
        } catch (Exception e) {
            log.debug("Failed to push workflow complete: {}", e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 辅助方法
    // ═══════════════════════════════════════════════════════════════

    /** 关键词匹配计数 */
    private int countKeywordMatches(String text, Set<String> keywords) {
        int count = 0;
        for (String kw : keywords) {
            if (text.contains(kw)) count++;
        }
        return count;
    }

    /** CJK 字符检测 — 用于动态调整 Prompt 长度阈值 */
    private static boolean containsCjk(String text) {
        if (text == null) return false;
        long cjkCount = text.codePoints()
                .filter(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN
                        || Character.UnicodeScript.of(cp) == Character.UnicodeScript.HIRAGANA
                        || Character.UnicodeScript.of(cp) == Character.UnicodeScript.KATAKANA
                        || Character.UnicodeScript.of(cp) == Character.UnicodeScript.HANGUL)
                .count();
        return cjkCount > text.length() * 0.3;  // CJK 字符占比超过 30%
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
