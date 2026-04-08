package com.aicodeassistant.permission;

import com.aicodeassistant.model.*;
import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 权限决策管线 — 7 步顺序检查（短路返回）。
 * <p>
 * 对齐源码: hasPermissionsToUseToolInner() 完整 7 步：
 * <pre>
 * Step 1a: deny 规则检查 → 命中则拒绝
 * Step 1b: ask 规则检查 → 命中则询问
 * Step 1c: tool.checkPermissions() → 工具自身权限检查
 * Step 1d: 工具实现拒绝 → 直接拒绝
 * Step 1e: requiresUserInteraction → 必须用户交互
 * Step 1f: 内容级 ask 规则 → 优先于 bypass 模式
 * Step 1g: 安全检查 → .git/.claude 等路径保护
 * Step 2a: bypassPermissions 模式 → 直接允许
 * Step 2b: alwaysAllow 规则 → 命中则允许
 * Step 3:  passthrough → 转为 ask，进入模式分支
 * </pre>
 *
 * @see <a href="SPEC §3.4.3a">权限决策管线完整实现</a>
 */
@Service
public class PermissionPipeline {

    private static final Logger log = LoggerFactory.getLogger(PermissionPipeline.class);

    /** 安全敏感路径 — bypass 模式也不能跳过的安全检查 */
    private static final Set<String> PROTECTED_PATHS = Set.of(
            ".git", ".claude", ".env", ".ssh", ".gnupg", ".aws"
    );

    /** 文件编辑工具名称集合 */
    private static final Set<String> FILE_EDIT_TOOLS = Set.of(
            "FileEdit", "FileWrite", "Write", "Edit"
    );

    private final PermissionRuleMatcher ruleMatcher;
    private final PermissionRuleRepository ruleRepository;
    private final AutoModeClassifier autoModeClassifier;

    public PermissionPipeline(PermissionRuleMatcher ruleMatcher,
                              PermissionRuleRepository ruleRepository,
                              AutoModeClassifier autoModeClassifier) {
        this.ruleMatcher = ruleMatcher;
        this.ruleRepository = ruleRepository;
        this.autoModeClassifier = autoModeClassifier;
    }

    /**
     * 完整权限决策管线 — 对齐源码 hasPermissionsToUseToolInner()。
     *
     * @param tool    目标工具
     * @param input   工具输入
     * @param context 工具执行上下文
     * @param permissionContext 权限上下文（模式 + 规则集）
     * @return 最终权限决策
     */
    public PermissionDecision checkPermission(
            Tool tool, ToolInput input, ToolUseContext context,
            PermissionContext permissionContext) {

        log.debug("Permission check: tool={}, mode={}", 
                tool.getName(), permissionContext.mode());

        // ===== Step 1a: deny 规则 =====
        PermissionRule denyRule = ruleMatcher.findDenyRule(permissionContext, tool);
        if (denyRule != null) {
            log.debug("Step 1a: deny rule matched for tool={}", tool.getName());
            return PermissionDecision.deny(denyRule,
                    "Permission to use " + tool.getName() + " has been denied.");
        }

        // ===== Step 1b: ask 规则 =====
        PermissionRule askRule = ruleMatcher.findAskRule(permissionContext, tool);
        if (askRule != null) {
            log.debug("Step 1b: ask rule matched for tool={}", tool.getName());
            return PermissionDecision.ask(askRule);
        }

        // ===== Step 1c: 工具自身权限检查 =====
        PermissionBehavior toolBehavior;
        try {
            toolBehavior = tool.checkPermissions(input, context);
        } catch (Exception e) {
            log.warn("Step 1c: tool.checkPermissions() failed for tool={}: {}",
                    tool.getName(), e.getMessage());
            toolBehavior = PermissionBehavior.PASSTHROUGH;
        }

        // ===== Step 1d: 工具实现拒绝 =====
        if (toolBehavior == PermissionBehavior.DENY) {
            log.debug("Step 1d: tool denied for tool={}", tool.getName());
            return PermissionDecision.denyByMode("Tool implementation denied the operation");
        }

        // ===== Step 1e: requiresUserInteraction =====
        if (tool.requiresUserInteraction() && toolBehavior == PermissionBehavior.ASK) {
            log.debug("Step 1e: tool requires user interaction for tool={}", tool.getName());
            return PermissionDecision.ask(PermissionDecisionReason.PERMISSION_PROMPT_TOOL,
                    "Tool requires user interaction");
        }

        // ===== Step 1g: 安全检查（bypass 免疫） =====
        String toolPath = tool.getPath(input);
        if (toolPath != null && isProtectedPath(toolPath)) {
            log.debug("Step 1g: protected path detected for tool={}, path={}", 
                    tool.getName(), toolPath);
            return PermissionDecision.ask(PermissionDecisionReason.SAFETY_CHECK,
                    "Operation targets a protected path: " + toolPath);
        }

        // ===== Step 2a: bypassPermissions 模式 =====
        PermissionMode mode = permissionContext.mode();
        boolean shouldBypass = mode == PermissionMode.BYPASS_PERMISSIONS
                || (mode == PermissionMode.PLAN && permissionContext.isBypassPermissionsModeAvailable());
        if (shouldBypass) {
            log.debug("Step 2a: bypass mode for tool={}", tool.getName());
            return PermissionDecision.allow(PermissionDecisionReason.MODE, mode);
        }

        // ===== Step 2b: alwaysAllow 规则 =====
        PermissionRule allowRule = ruleMatcher.findAllowRule(permissionContext, tool);
        if (allowRule != null) {
            log.debug("Step 2b: allow rule matched for tool={}", tool.getName());
            return PermissionDecision.allow(allowRule);
        }

        // ===== Step 3: passthrough → ask，进入模式分支 =====
        if (toolBehavior == PermissionBehavior.PASSTHROUGH) {
            toolBehavior = PermissionBehavior.ASK;
        }

        // 只读工具（NONE 权限）直接允许
        if (tool.isReadOnly(input) 
                && tool.getPermissionRequirement() == com.aicodeassistant.tool.PermissionRequirement.NONE) {
            log.debug("Step 3: read-only tool auto-allowed: tool={}", tool.getName());
            return PermissionDecision.allow(PermissionDecisionReason.MODE, mode);
        }

        return applyModeTransformation(toolBehavior, mode, tool, input);
    }

    /**
     * 模式转换 — 将 ask 决策根据权限模式转换为最终行为。
     */
    private PermissionDecision applyModeTransformation(
            PermissionBehavior behavior, PermissionMode mode,
            Tool tool, ToolInput input) {

        if (behavior != PermissionBehavior.ASK) {
            // 非 ask 行为直接返回
            if (behavior == PermissionBehavior.ALLOW) {
                return PermissionDecision.allow(PermissionDecisionReason.MODE, mode);
            }
            return PermissionDecision.denyByMode("Tool behavior is DENY");
        }

        return switch (mode) {
            case DEFAULT -> {
                log.debug("Mode DEFAULT: asking user for tool={}", tool.getName());
                yield PermissionDecision.ask(PermissionDecisionReason.MODE,
                        "Standard mode requires user confirmation for " + tool.getName());
            }
            case PLAN -> {
                if (tool.isReadOnly(input)) {
                    yield PermissionDecision.allow(PermissionDecisionReason.MODE, mode);
                }
                yield PermissionDecision.ask(PermissionDecisionReason.MODE,
                        "Plan mode requires confirmation for write operations");
            }
            case ACCEPT_EDITS -> {
                if (isFileEditTool(tool)) {
                    yield PermissionDecision.allow(PermissionDecisionReason.MODE, mode);
                }
                yield PermissionDecision.ask(PermissionDecisionReason.MODE,
                        "Accept-edits mode requires confirmation for non-edit operations");
            }
            case DONT_ASK -> PermissionDecision.denyByMode(
                    "Current permission mode (Don't Ask) auto-rejects write operations");
            case BYPASS_PERMISSIONS -> PermissionDecision.allow(PermissionDecisionReason.MODE, mode);
            case AUTO -> {
                // LLM 驱动的两阶段权限分类（对齐 yoloClassifier）
                try {
                    PermissionContext classifierContext = ruleRepository.buildContext(
                            PermissionMode.AUTO, false, false);
                    PermissionDecision classifierDecision = autoModeClassifier.classify(
                            tool, input, classifierContext);

                    if (classifierDecision.isAllowed()) {
                        yield classifierDecision;
                    }
                    if (classifierDecision.isDenied()) {
                        yield PermissionDecision.ask(PermissionDecisionReason.CLASSIFIER,
                                classifierDecision.reason() != null
                                        ? classifierDecision.reason()
                                        : "AUTO mode: classifier blocked, requesting confirmation");
                    }
                    yield classifierDecision;
                } catch (Exception e) {
                    // 分类器异常时降级为 ASK
                    log.warn("AUTO classifier error, falling back to ASK: {}", e.getMessage());
                    yield PermissionDecision.ask(PermissionDecisionReason.CLASSIFIER,
                            "AUTO mode: classifier unavailable, requesting confirmation");
                }
            }
            case BUBBLE -> PermissionDecision.ask(PermissionDecisionReason.ASYNC_AGENT,
                    "Bubble mode: delegating to parent agent")
                    .withBubble(true);
        };
    }

    /**
     * 记住用户的权限决策 — 将用户确认/拒绝转为持久化规则。
     *
     * @param tool     工具
     * @param input    工具输入
     * @param allowed  用户是否允许
     * @param scope    记忆作用域
     */
    public void rememberDecision(Tool tool, ToolInput input,
                                  boolean allowed, RuleScope scope) {
        PermissionRuleSource source = scope == RuleScope.SESSION
                ? PermissionRuleSource.USER_SESSION
                : PermissionRuleSource.USER_GLOBAL;

        String toolName = tool.getName();
        String ruleContent = null;

        // BashTool: 记住具体命令
        if ("Bash".equals(toolName)) {
            ruleContent = input.getOptionalString("command").orElse(null);
        }

        PermissionRuleValue value = ruleContent != null
                ? new PermissionRuleValue(toolName, ruleContent)
                : new PermissionRuleValue(toolName, null);

        PermissionRule rule = new PermissionRule(source,
                allowed ? PermissionBehavior.ALLOW : PermissionBehavior.DENY,
                value);

        if (allowed) {
            ruleRepository.addAllowRule(rule);
        } else {
            ruleRepository.addDenyRule(rule);
        }

        log.info("Remembered permission decision: tool={}, allowed={}, scope={}, content={}",
                toolName, allowed, scope, ruleContent);
    }

    // ==================== 辅助方法 ====================

    private boolean isFileEditTool(Tool tool) {
        return FILE_EDIT_TOOLS.contains(tool.getName());
    }

    private boolean isProtectedPath(String path) {
        if (path == null) return false;
        for (String protectedDir : PROTECTED_PATHS) {
            if (path.contains("/" + protectedDir + "/")
                    || path.contains("/" + protectedDir)
                    || path.startsWith(protectedDir + "/")
                    || path.equals(protectedDir)) {
                return true;
            }
        }
        return false;
    }
}
