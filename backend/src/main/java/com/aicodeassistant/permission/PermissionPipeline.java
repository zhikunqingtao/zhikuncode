package com.aicodeassistant.permission;

import com.aicodeassistant.model.*;
import com.aicodeassistant.security.CommandBlacklistService;
import com.aicodeassistant.security.PathSecurityService;
import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.aicodeassistant.tool.bash.BashCommandClassifier;
import com.aicodeassistant.tool.bash.BashSecurityAnalyzer;
import com.aicodeassistant.tool.bash.ast.ParseForSecurityResult;
import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.hook.HookRegistry;
import com.aicodeassistant.hook.HookService;
import com.aicodeassistant.sandbox.SandboxManager;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 权限决策管线 — 7 步顺序检查（短路返回）。
 * <p>
 * <pre>
 * Step 1a: deny 规则检查 → 命中则拒绝
 * Step 1b: ask 规则检查 → 命中则询问
 * Step 1c: tool.checkPermissions() → 工具自身权限检查
 * Step 1d: 工具实现拒绝 → 直接拒绝
 * Step 1e: requiresUserInteraction → 必须用户交互
 * Step 1f: 内容级 ask 规则 → 优先于 bypass 模式
 * Step 1g: 安全检查 → .git/.zhikun 等路径保护
 * Step 2a: skipAllPrompts 模式 → 直接允许
 * Step 2b: alwaysAllow 规则 → 命中则允许
 * Step 3:  passthrough → 转为 ask，进入模式分支
 * </pre>
 *
 */
@Service
public class PermissionPipeline {

    private static final Logger log = LoggerFactory.getLogger(PermissionPipeline.class);

    /** 安全敏感路径 — bypass 模式也不能跳过的安全检查 */
    private static final Set<String> PROTECTED_PATHS = Set.of(
            ".git", ".zhikun", ".env", ".ssh", ".gnupg", ".aws"
    );

    /** 文件编辑工具名称集合 */
    private static final Set<String> FILE_EDIT_TOOLS = Set.of(
            "FileEdit", "FileWrite", "Write", "Edit"
    );

    /** Bash 工具名称集合 */
    private static final Set<String> BASH_TOOL_NAMES = Set.of("Bash", "BashTool");

    /** 裸壳前缀 — 禁止为这些前缀生成 "Always allow" 建议 */
    private static final Set<String> BARE_SHELL_PREFIXES = Set.of(
            "sh", "bash", "zsh", "fish", "csh", "tcsh", "ksh", "dash",
            "cmd", "powershell", "pwsh",
            "env", "xargs",
            "nice", "stdbuf", "nohup", "timeout", "time",
            "sudo", "doas", "pkexec",
            "su",
            "eval"  // eval 几乎总是危险的
    );

    /** 破坏性命令前缀——命中即判定为 High Risk */
    private static final Set<String> DESTRUCTIVE_COMMAND_PREFIXES = Set.of(
            "rm ", "rm -",
            "rmdir ",
            "dd ",
            "mkfs",
            "chmod -R 777",
            "chown -R",
            "> /", ">> /",
            "truncate ",
            "shred ",
            "kill -9",
            "killall ",
            "git push --force",
            "git push -f",
            "git reset --hard",
            "docker rm ",
            "docker system prune"
    );

    /** 内容级危险模式 (即使 bypass 也强制 ask) */
    private static final List<Pattern> CONTENT_LEVEL_ASK_PATTERNS = List.of(
            Pattern.compile("rm\\s+(?:-[rRf]+\\s+){0,5}(/|~|\\$HOME)"),
            Pattern.compile("chmod\\s+(-R\\s+)?777\\s+/"),
            Pattern.compile(">(\\s*)/dev/sd[a-z]"),
            Pattern.compile("mkfs\\."),
            Pattern.compile("dd\\s+[^\\n]{0,200}of=/dev/"),
            Pattern.compile(":\\(\\)\\{\\s*:\\|:&\\s*\\};:"),
            Pattern.compile("git\\s+push\\s+.*--force"),
            Pattern.compile("git\\s+(reset|clean)\\s+--hard"),
            Pattern.compile("DROP\\s+(TABLE|DATABASE)", Pattern.CASE_INSENSITIVE),
            // eval/source/exec 危险函数绕过防护
            Pattern.compile("\\beval\\s+"),
            Pattern.compile("\\bsource\\s+"),
            Pattern.compile("\\bexec\\s+"),
            // sed -i 就地编辑
            Pattern.compile("\\bsed\\s+(-[a-zA-Z]*i|-i[a-zA-Z]*)\\s+")
    );

    /** 异步权限等待 — Run-scoped correlation key → wake-up future. */
    private final ConcurrentHashMap<InteractionKey, CompletableFuture<PermissionDecision>> pendingRequests =
            new ConcurrentHashMap<>();
    private record InteractionKey(String runId, String correlationKey) { }

    private final PermissionRuleMatcher ruleMatcher;
    private final PermissionRuleRepository ruleRepository;
    private final AutoModeClassifier autoModeClassifier;
    private final HookService hookService;
    private final SandboxManager sandboxManager;
    private final PathSecurityService pathSecurityService;
    private final BashCommandClassifier bashCommandClassifier;
    private final BashSecurityAnalyzer bashSecurityAnalyzer;
    private final FeatureFlagService featureFlags;
    private final CommandBlacklistService commandBlacklistService;
    private final PermissionInteractionService permissionInteractions;
    private final PersistentPermissionGrantStore grantStore;
    private final PermissionGrantKeyFactory grantKeyFactory;

    @Autowired
    public PermissionPipeline(PermissionRuleMatcher ruleMatcher,
                              PermissionRuleRepository ruleRepository,
                              AutoModeClassifier autoModeClassifier,
                              HookService hookService,
                              SandboxManager sandboxManager,
                              PathSecurityService pathSecurityService,
                              BashCommandClassifier bashCommandClassifier,
                              BashSecurityAnalyzer bashSecurityAnalyzer,
                              FeatureFlagService featureFlags,
                              CommandBlacklistService commandBlacklistService,
                              @org.springframework.context.annotation.Lazy PermissionInteractionService permissionInteractions,
                              PersistentPermissionGrantStore grantStore,
                              PermissionGrantKeyFactory grantKeyFactory) {
        this.ruleMatcher = ruleMatcher;
        this.ruleRepository = ruleRepository;
        this.autoModeClassifier = autoModeClassifier;
        this.hookService = hookService;
        this.sandboxManager = sandboxManager;
        this.pathSecurityService = pathSecurityService;
        this.bashCommandClassifier = bashCommandClassifier;
        this.bashSecurityAnalyzer = bashSecurityAnalyzer;
        this.featureFlags = featureFlags;
        this.commandBlacklistService = commandBlacklistService;
        this.permissionInteractions = permissionInteractions;
        this.grantStore = grantStore;
        this.grantKeyFactory = grantKeyFactory;
    }

    public PermissionPipeline(PermissionRuleMatcher ruleMatcher, PermissionRuleRepository ruleRepository,
                              AutoModeClassifier autoModeClassifier, HookService hookService,
                              SandboxManager sandboxManager, PathSecurityService pathSecurityService,
                              BashCommandClassifier bashCommandClassifier, FeatureFlagService featureFlags,
                              CommandBlacklistService commandBlacklistService,
                              PermissionInteractionService permissionInteractions) {
        this(ruleMatcher, ruleRepository, autoModeClassifier, hookService, sandboxManager,
                pathSecurityService, bashCommandClassifier, null, featureFlags, commandBlacklistService,
                permissionInteractions, null, null);
    }

    /**
     * 完整权限决策管线
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
        log.debug("Step 1a: checking deny rules for tool={}", tool.getName());
        PermissionRule denyRule = ruleMatcher.findDenyRule(permissionContext, tool);
        if (denyRule != null) {
            log.debug("Step 1a: deny rule matched for tool={}", tool.getName());
            return PermissionDecision.deny(denyRule,
                    "Permission to use " + tool.getName() + " has been denied.");
        }

        // ===== Step 1b: ask 规则 =====
        log.debug("Step 1b: checking ask rules for tool={}", tool.getName());
        PermissionRule askRule = ruleMatcher.findAskRule(permissionContext, tool);
        if (askRule != null) {
            log.debug("Step 1b: ask rule matched for tool={}", tool.getName());
            return PermissionDecision.ask(askRule);
        }

        // ===== Step 1c: 工具自身权限检查 =====
        log.debug("Step 1c: tool.checkPermissions() for tool={}", tool.getName());
        PermissionBehavior toolBehavior;
        try {
            toolBehavior = tool.checkPermissions(input, context);
            log.debug("Step 1c result: tool={}, behavior={}", tool.getName(), toolBehavior);
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

        // ===== Step 1f: 内容级 ask 规则 (优先于 bypass 模式) =====
        log.debug("Step 1f: checkContentLevelAsk for tool={}", tool.getName());

        // ===== Step 1f-pre: 系统级命令黑名单（bypass 免疫）=====
        if (BASH_TOOL_NAMES.contains(tool.getName())) {
            String bashCmd = input.getOptionalString("command").orElse(null);
            if (bashCmd != null) {
                var blockResult = commandBlacklistService.checkCommand(bashCmd);
                if (blockResult.level() == CommandBlacklistService.BlockLevel.ABSOLUTE_DENY) {
                    return PermissionDecision.denyByMode(
                            "Blocked by command blacklist: " + blockResult.reason());
                }
                if (blockResult.level() == CommandBlacklistService.BlockLevel.HIGH_RISK_ASK) {
                    return PermissionDecision.ask(
                            PermissionDecisionReason.SAFETY_CHECK,
                            "High-risk command detected: " + blockResult.reason());
                }
            }
        }

        PermissionDecision contentAskDecision = checkContentLevelAsk(tool, input);
        if (contentAskDecision != null) {
            log.debug("Step 1f: content-level ask triggered for tool={}", tool.getName());
            return contentAskDecision;
        }

        // ===== Step 1f-pre: 提前获取 toolPath（后续多处复用） =====
        String toolPath = tool.getPath(input);

        // ===== Step 1f-write: 写路径危险文件/目录检查 (Layer 2+3+5) =====
        if (!tool.isReadOnly(input) && toolPath != null) {
            PathSecurityService.PathCheckResult writeCheck =
                    pathSecurityService.checkWritePermission(toolPath, context.workingDirectory());
            if (!writeCheck.isAllowed() && !writeCheck.needsConfirmation()) {
                return PermissionDecision.denyByMode(writeCheck.message());
            }
            if (writeCheck.needsConfirmation()) {
                return PermissionDecision.ask(writeCheck.message());
            }
        }

        // ===== Step 1f-bash: Bash 命令危险删除 + 环境变量检查 (Layer 4+7) =====
        log.debug("Step 1f-bash: checking for tool={}", tool.getName());
        if (BASH_TOOL_NAMES.contains(tool.getName())) {
            String command = input.getString("command");
            String rmBlock = pathSecurityService.checkDangerousRemoval(command);
            if (rmBlock != null) {
                return PermissionDecision.denyByMode(rmBlock);
            }
            String envBlock = checkBashEnvironmentAccess(command);
            if (envBlock != null) {
                if (envBlock.startsWith("ASK:")) {
                    return PermissionDecision.ask(envBlock.substring(4).trim());
                }
                return PermissionDecision.denyByMode(envBlock);
            }
        }

        // ===== Step 1g: 安全检查（bypass 免疫） =====
        log.debug("Step 1g: safety check for tool={}", tool.getName());
        toolPath = tool.getPath(input);
        if (toolPath != null && isProtectedPath(toolPath)) {
            log.debug("Step 1g: protected path detected for tool={}, path={}", 
                    tool.getName(), toolPath);
            return PermissionDecision.ask(PermissionDecisionReason.SAFETY_CHECK,
                    "Operation targets a protected path: " + toolPath);
        }

        // ===== Step 1h: Hook 权限规则注入（新增）=====
        Optional<PermissionDecision> hookDecision = evaluateHookRules(tool.getName(), input);
        if (hookDecision.isPresent()) {
            log.debug("Step 1h: hook rule decision for tool={}: {}", tool.getName(), hookDecision.get().behavior());
            return hookDecision.get();
        }

        // ===== Step 1i: Classifier 权限规则注入（新增，仅 auto 模式）=====
        Optional<PermissionDecision> classifierDecision = evaluateClassifierRules(
                tool.getName(), input, permissionContext.mode().name().toLowerCase());
        if (classifierDecision.isPresent()) {
            log.debug("Step 1i: classifier rule decision for tool={}: {}", tool.getName(), classifierDecision.get().behavior());
            return classifierDecision.get();
        }

        // ===== Step 1j: 敏感系统文件访问检查（必须先于 sandbox/auto allow） =====
        if (BASH_TOOL_NAMES.contains(tool.getName())) {
            String bashCmd = input.getOptionalString("command").orElse(null);
            String sensitiveBlock = pathSecurityService.checkSensitiveFileRead(bashCmd);
            if (sensitiveBlock != null) {
                log.debug("Step 1k: sensitive file read blocked for tool={}, reason={}", tool.getName(), sensitiveBlock);
                return PermissionDecision.denyByMode(sensitiveBlock);
            }
        }

        // ===== Step 1k: Sandbox 权限规则覆盖 =====
        Optional<PermissionDecision> sandboxDecision = evaluateSandboxRules(tool.getName(), input);
        if (sandboxDecision.isPresent()) {
            log.debug("Step 1k: sandbox rule decision for tool={}: {}", tool.getName(), sandboxDecision.get().behavior());
            return sandboxDecision.get();
        }

        // ===== Step 2a: skipAllPrompts 模式 =====
        PermissionMode mode = permissionContext.mode();
        boolean shouldBypass = mode == PermissionMode.SKIP_ALL_PROMPTS
                || (mode == PermissionMode.PLAN && permissionContext.isSkipAllPromptsModeAvailable());
        if (shouldBypass) {
            log.debug("Step 2a: bypass mode for tool={}", tool.getName());
            return PermissionDecision.allow(PermissionDecisionReason.MODE, mode);
        }

        // ===== Step 2b: alwaysAllow 规则 + V4 硬门控 =====
        String step2bCommand = BASH_TOOL_NAMES.contains(tool.getName())
                ? input.getOptionalString("command").orElse("")
                : "";
        String step2bRiskLevel = assessCommandRiskLevel(step2bCommand);
        if (grantStore != null && grantKeyFactory != null && context.sessionId() != null) {
            var key = grantKeyFactory.create(tool, input, context.workingDirectory(), step2bRiskLevel);
            if (key.isPresent()) {
                boolean child = context.parentSessionId() != null;
                boolean grantMatch;
                if (child) {
                    // 先尝试 child-exact 匹配（低风险只读命令的精确复用）
                    grantMatch = isChildExactEligible(tool, input, step2bRiskLevel)
                            && grantStore.matchesChildExact(context.parentSessionId(), context.currentRunId(),
                                    context.sessionId(), childAgentType(context), key.get());
                    // 回退：检查父会话的 SESSION grant（用户在 bubble 弹窗中授权的）
                    if (!grantMatch) {
                        grantMatch = grantStore.matchesSession(context.currentRunId(),
                                context.parentSessionId(), key.get());
                        if (!grantMatch && key.get().canonicalCwd() != null) {
                            grantMatch = grantStore.matchesWorkspace(context.currentRunId(),
                                    grantKeyFactory.workspaceHash(key.get().canonicalCwd()), key.get());
                        }
                    }
                } else {
                    grantMatch = grantStore.matchesSession(context.currentRunId(),
                            context.sessionId(), key.get())
                            || grantStore.matchesWorkspace(context.currentRunId(),
                                    grantKeyFactory.workspaceHash(key.get().canonicalCwd()), key.get());
                }
                if (grantMatch) {
                    return PermissionDecision.allow(PermissionDecisionReason.OTHER, null);
                }
            }
        }
        PermissionRule allowRule = ruleMatcher.findAllowRule(permissionContext, tool, input, step2bRiskLevel);
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

        log.debug("Step 3: applyModeTransformation: tool={}, behavior={}, mode={}", tool.getName(), toolBehavior, mode);
        return applyModeTransformation(toolBehavior, mode, tool, input, step2bRiskLevel);
    }

    private String checkBashEnvironmentAccess(String command) {
        if (command == null) return null;
        if (command.matches("^\\s*(env|printenv)\\s*$")) {
            return "ASK: Bulk environment export may leak sensitive information";
        }
        // Unit-test compatibility constructors do not provide the AST analyzer. Production
        // injection always does; fail closed instead of silently falling back to regex parsing.
        if (bashSecurityAnalyzer == null) {
            return "ASK: Bash environment analysis unavailable";
        }
        var analysis = bashSecurityAnalyzer.analyzeEnvironmentReferences(command);
        if (analysis.requiresConservativeAsk()) {
            return "ASK: Bash environment references could not be proven safe ("
                    + analysis.parseStatus().name().toLowerCase(Locale.ROOT) + ")";
        }
        if (!analysis.sensitiveInheritedReferences().isEmpty()) {
            return "Sensitive environment variable access denied: $"
                    + analysis.sensitiveInheritedReferences().iterator().next();
        }
        for (String inherited : analysis.inheritedReferences()) {
            if (!bashSecurityAnalyzer.isAllowedInheritedEnvironmentReference(inherited)) {
                return "ASK: Command references non-whitelisted env var: $" + inherited;
            }
        }
        return null;
    }

    /**
     * 模式转换 — 将 ask 决策根据权限模式转换为最终行为。
     */
    private PermissionDecision applyModeTransformation(
            PermissionBehavior behavior, PermissionMode mode,
            Tool tool, ToolInput input, String riskLevel) {

        if (behavior != PermissionBehavior.ASK) {
            // 非 ask 行为直接返回
            if (behavior == PermissionBehavior.ALLOW) {
                return PermissionDecision.allow(PermissionDecisionReason.MODE, mode);
            }
            return PermissionDecision.denyByMode("Tool behavior is DENY");
        }

        return switch (mode) {
            case DEFAULT -> {
                if ("low".equalsIgnoreCase(riskLevel)) {
                    log.debug("Mode DEFAULT: auto-allowing low-risk tool={}", tool.getName());
                    yield PermissionDecision.allow(PermissionDecisionReason.MODE, mode);
                }
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
            case DONT_ASK -> {
                // DONT_ASK 语义: 不弹窗确认。只读工具自动允许，写操作自动拒绝。
                if (tool.isReadOnly(input)) {
                    log.debug("Mode DONT_ASK: auto-allowing read-only tool={}", tool.getName());
                    yield PermissionDecision.allow(PermissionDecisionReason.MODE, mode);
                }
                log.debug("Mode DONT_ASK: auto-denying write tool={}", tool.getName());
                yield PermissionDecision.denyByMode(
                        "DONT_ASK mode: write operations are auto-denied without user confirmation");
            }
            case SKIP_ALL_PROMPTS -> PermissionDecision.allow(PermissionDecisionReason.MODE, mode);
            case AUTO -> {
                // killswitch 检查：AUTO 模式可通过 FeatureFlag 紧急关闭
                if (!featureFlags.isEnabled("AUTO_MODE_ENABLED")) {
                    log.warn("AUTO mode killswitch active, falling back to DEFAULT");
                    yield PermissionDecision.ask(PermissionDecisionReason.MODE,
                            "AUTO mode temporarily disabled by administrator");
                }
                // LLM 驱动的两阶段权限分类
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
    /** Persist an allow grant after the winning interaction CAS. Denials are not broad grants. */
    public void rememberDecision(Tool tool, ToolInput input, boolean allowed, PermissionScope scope,
                                 String projectKey, String sessionId, String runId, String toolUseId) {
        if (!allowed || grantStore == null || grantKeyFactory == null || sessionId == null) return;
        String command = ("Bash".equals(tool.getName()) || "BashTool".equals(tool.getName()))
                ? input.getOptionalString("command").orElse("") : "";
        String risk = assessCommandRiskLevel(command);
        var key = grantKeyFactory.create(tool, input, projectKey, risk);
        if (key.isEmpty()) return; // ONCE: no persistent grant
        String interactionId = permissionInteractions.interactionId(runId, toolUseId);
        if (interactionId == null) return;
        if (scope == PermissionScope.WORKSPACE && key.get().workspaceAllowed()) {
            grantStore.saveStandard("WORKSPACE", null,
                    grantKeyFactory.workspaceHash(key.get().canonicalCwd()), key.get(), interactionId);
        } else if (scope == PermissionScope.SESSION) {
            grantStore.saveStandard("SESSION", sessionId, null, key.get(), interactionId);
        }
    }

    public void rememberChildDecision(Tool tool, ToolInput input, boolean allowed,
                                      ToolUseContext context, String toolUseId) {
        if (!allowed || grantStore == null || grantKeyFactory == null || context == null
                || context.parentSessionId() == null) return;
        String command = BASH_TOOL_NAMES.contains(tool.getName())
                ? input.getOptionalString("command").orElse("") : "";
        String risk = assessCommandRiskLevel(command);
        var key = grantKeyFactory.create(tool, input, context.workingDirectory(), risk);
        String interactionId = permissionInteractions.interactionId(context.currentRunId(), toolUseId);
        if (key.isEmpty() || interactionId == null) return;

        // 低风险只读命令：保存 child-exact grant（精确复用）
        if (isChildExactEligible(tool, input, risk)) {
            grantStore.saveChildExact(context.parentSessionId(), context.currentRunId(),
                    context.sessionId(), childAgentType(context), key.get(), interactionId);
        }
        // 始终保存父会话 SESSION grant（用户在 bubble UI 中明确授权的）
        grantStore.saveStandard("SESSION", context.parentSessionId(), null, key.get(), interactionId);
    }

    private static String childAgentType(ToolUseContext context) {
        return context.agentHierarchy() == null || context.agentHierarchy().isBlank()
                ? "subagent" : context.agentHierarchy();
    }

    /**
     * Conservative child-agent reuse gate. Exact hashing is not sufficient on
     * its own: only AST-proven, non-writing, non-networking operations may be
     * reused. Any unsupported syntax or environment ambiguity remains ASK.
     */
    private boolean isChildExactEligible(Tool tool, ToolInput input, String risk) {
        if (!"low".equals(risk) || tool == null || !tool.isReadOnly(input)) return false;
        if (!BASH_TOOL_NAMES.contains(tool.getName())) return true;
        if (bashSecurityAnalyzer == null) return false;
        String command = input.getOptionalString("command").orElse("");
        ParseForSecurityResult parsed = bashSecurityAnalyzer.parseForSecurity(command);
        if (!(parsed instanceof ParseForSecurityResult.Simple simple) || simple.commands().isEmpty()) return false;
        var environment = bashSecurityAnalyzer.analyzeEnvironmentReferences(command);
        if (environment.requiresConservativeAsk() || !environment.sensitiveInheritedReferences().isEmpty()) {
            return false;
        }
        Set<String> forbidden = Set.of(
                "curl", "wget", "nc", "ncat", "ssh", "scp", "rsync", "ftp",
                "npm", "npx", "yarn", "pnpm", "pip", "pip3", "brew", "apt", "apt-get",
                "git", "docker", "kubectl", "tee", "dd", "mv", "cp", "rm", "touch",
                "sed", "perl", "python", "python3", "node", "bash", "sh", "zsh");
        return simple.commands().stream().allMatch(node ->
                !node.argv().isEmpty()
                        && node.redirects().isEmpty()
                        && node.envVars().isEmpty()
                        && !forbidden.contains(node.argv().getFirst()));
    }

    // ==================== 异步权限请求 ====================

    /**
     * 发起异步权限请求 — 返回 CompletableFuture 等待用户决策。
     * <p>
     * 通过 WebSocket 推送持久化 interaction 到前端，前端展示权限对话框。
     * 用户决定只经 {@code /api/interactions/{id}/decisions} 写入数据库 CAS；
     * 内存 future 仅由数据库终态唤醒，不参与裁决。
     *
     * @param toolUseId  工具调用 ID
     * @param tool       工具定义
     * @param input      经过 hook 处理后的工具输入
     * @param reason     请求原因（前端展示用）
     * @param wsPusher   WebSocket 推送器（由调用方注入，避免循环依赖）
     * @param sessionId  会话 ID
     * @return 用户决策的 CompletableFuture
     */
    public CompletableFuture<PermissionDecision> requestPermission(
            String toolUseId, Tool tool, ToolInput input,
            String reason, PermissionNotifier wsPusher, String sessionId,
            String runId, String childSessionId, String workingDirectory) {

        if (tool == null || input == null) {
            return CompletableFuture.completedFuture(
                    PermissionDecision.denyByMode("Permission request is missing typed tool input"));
        }
        String toolName = tool.getName();
        Map<String, Object> inputMap = rawInputMap(input);

        // 通过 WebSocket 推送权限请求到前端
        String commandStr = String.valueOf(inputMap.getOrDefault("command", ""));
        String riskLevel = assessCommandRiskLevel(commandStr);
        List<String> scopeOptions = grantKeyFactory == null
                ? List.of()
                : grantKeyFactory.create(tool, input, workingDirectory, riskLevel)
                        .map(key -> childSessionId != null || !key.workspaceAllowed()
                                ? List.of("session")
                                : List.of("session", "workspace"))
                        .orElseGet(List::of);

        // 记录转发来源信息（当 sessionId 与 wsPusher 的目标会话不同时，说明是子代理冒泡转发）
        log.info("Permission request: toolUseId={}, toolName={}, targetSession={}",
                toolUseId, toolName, sessionId);

        CompletableFuture<PermissionDecision> authorityFuture;
        InteractionKey interactionKey = new InteractionKey(runId, toolUseId);
        synchronized (pendingRequests) {
            CompletableFuture<PermissionDecision> existing = pendingRequests.get(interactionKey);
            if (existing != null) {
                log.warn("Duplicate permission request for toolUseId={}, returning existing", toolUseId);
                return existing;
            }
            try {
                String inputSummary = commandStr.length() > 500
                        ? commandStr.substring(0, 500) + "..."
                        : commandStr;
                String source = childSessionId != null ? "bubble" : "direct";
                permissionInteractions.persistRequest(
                        runId, sessionId, toolUseId, toolName, riskLevel,
                        reason, inputSummary, scopeOptions, source, childSessionId);
                authorityFuture = permissionInteractions.awaitDecision(runId, toolUseId);
                pendingRequests.put(interactionKey, authorityFuture);
            } catch (Exception e) {
                log.error("Failed to persist permission request: toolUseId={}, error={}",
                        toolUseId, e.getMessage());
                return CompletableFuture.completedFuture(
                        PermissionDecision.denyByMode("Permission persistence failed"));
            }
        }

        // InteractionCreatedEvent is the sole delivery path. Direct notifier calls
        // would race the durable event and produce duplicate prompts.
        authorityFuture.whenComplete((ignored, error) -> {
            pendingRequests.remove(interactionKey, authorityFuture);
        });
        return authorityFuture;
    }

    private static Map<String, Object> rawInputMap(ToolInput input) {
        if (!(input.getRawData() instanceof Map<?, ?> raw)) {
            return Map.of("input", String.valueOf(input.getRawData()));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, value) -> {
            if (key != null) result.put(String.valueOf(key), value);
        });
        return Collections.unmodifiableMap(result);
    }

    /**
     * 取消所有挂起的权限请求（会话中断时调用）。
     */
    public void cancelAllPending() {
        permissionInteractions.cancelAll("session_interrupted");
        pendingRequests.clear();
    }

    // ==================== 建议生成 ====================

    /**
     * 生成权限建议。
     * 前端展示 "Allow for this session" / "Always allow" 等选项。
     */
    public List<Map<String, String>> buildSuggestions(Tool tool, ToolInput input) {
        List<Map<String, String>> suggestions = new ArrayList<>();
        suggestions.add(Map.of(
                "label", "Allow for this session",
                "scope", "session",
                "toolName", tool.getName()));
        return suggestions;
    }

    // ==================== Hook / Classifier / Sandbox 权限评估 ====================

    /**
     * Hook 权限规则注入 — PreToolUse Hook 可以返回权限覆盖。
     * <p>
     * 在权限决策管线中，Hook 来源的优先级高于 SYSTEM_DEFAULT 但低于 SESSION。
     * Hook 通过 {@link HookService#executePreToolUse} 执行，根据返回结果注入权限决策：
     * <ul>
     *   <li>{@code proceed=false} → 阻止工具执行</li>
     *   <li>{@code proceed=true} → 不影响权限决策（跳过）</li>
     * </ul>
     *
     * @param toolName  工具名称
     * @param toolInput 工具输入
     * @return 权限决策（Hook 未表态时返回 empty）
     */
    private Optional<PermissionDecision> evaluateHookRules(String toolName, ToolInput toolInput) {
        try {
            String inputStr = toolInput.getOptionalString("command")
                    .orElse(toolInput.toString());
            HookRegistry.HookResult hookResult = hookService.executePreToolUse(toolName, inputStr, null);
            if (hookResult == null) {
                return Optional.empty();
            }
            if (!hookResult.proceed()) {
                // Hook 拒绝操作
                String reason = hookResult.message() != null
                        ? "Blocked by PreToolUse hook: " + hookResult.message()
                        : "Blocked by PreToolUse hook";
                return Optional.of(PermissionDecision.ask(
                        PermissionDecisionReason.HOOK, reason));
            }
            return Optional.empty(); // Hook 通过，不影响决策
        } catch (Exception e) {
            log.warn("Hook permission evaluation failed for tool={}: {}", toolName, e.getMessage());
            return Optional.empty(); // 异常时不影响现有流程
        }
    }

    /**
     * Classifier 权限规则注入 — LLM 分类器评估工具调用风险。
     * <p>
     * 仅在 {@code auto} 模式下启用。当前为骨架实现（预留接口，返回默认值），
     * 后续可接入轻量级 LLM 调用判断工具操作是否安全。
     * <p>
     * 分类结果: SAFE / RISKY / DESTRUCTIVE
     *
     * @param toolName       工具名称
     * @param toolInput      工具输入
     * @param permissionMode 当前权限模式
     * @return 权限决策（非 auto 模式或骨架实现时返回 empty）
     */
    private Optional<PermissionDecision> evaluateClassifierRules(String toolName,
                                                                  ToolInput toolInput,
                                                                  String permissionMode) {
        if (!"auto".equals(permissionMode)) {
            return Optional.empty(); // 只在 auto 模式下使用分类器
        }

        try {
            // UNI-6 fix: 提取命令内容进行快速风险评估
            // 注意：此处仅做快速轻量级预检（无 LLM 调用），
            // 完整的 LLM 分类在 applyModeTransformation 的 case AUTO 中执行。
            // 与 CONTENT_LEVEL_ASK_PATTERNS 的职责边界：
            //   - CONTENT_LEVEL_ASK_PATTERNS: 在所有模式下强制 ask（包括 BYPASS）
            //   - isHighRiskCommand: 仅在 AUTO 模式的 Step 1i 阶段提前拦截
            log.debug("Classifier pre-evaluation for tool={} in auto mode", toolName);

            String command = toolInput.getOptionalString("command").orElse(null);
            if (command != null && isHighRiskCommand(command)) {
                return Optional.of(PermissionDecision.ask(
                        PermissionDecisionReason.CLASSIFIER,
                        "AUTO mode pre-check: high-risk command detected, requiring confirmation"));
            }

            return Optional.empty(); // 非高风险命令，交给后续 case AUTO 完整分类

        } catch (Exception e) {
            log.warn("Classifier pre-evaluation failed for tool={}: {}", toolName, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * UNI-6 fix: 快速高风险命令判断（无需 LLM 调用）。
     * 覆盖危险指令包括但不限于 rm -rf、sudo、env、chmod 777、dd、mkfs、
     * DROP TABLE/DATABASE、git push --force、git reset --hard 等。
     * 与 CONTENT_LEVEL_ASK_PATTERNS 互补：后者使用正则匹配，本方法使用字符串前缀快速检测。
     */
    private boolean isHighRiskCommand(String command) {
        String lower = command.toLowerCase().trim();
        // 文件系统破坏性命令
        if (lower.startsWith("rm ") || lower.contains("rm -")) return true;
        // 权限提升与环境篡改
        if (lower.startsWith("sudo ") || lower.startsWith("env ")) return true;
        // 磁盘格式化与裸写
        if (lower.contains("mkfs") || lower.startsWith("dd ")) return true;
        if (lower.contains("chmod") && lower.contains("777")) return true;
        // 数据库破坏性操作
        if (lower.contains("drop table") || lower.contains("drop database")) return true;
        // Git 不可逆操作
        if (lower.contains("git push") && lower.contains("--force")) return true;
        if (lower.contains("git reset") && lower.contains("--hard")) return true;
        if (lower.contains("git clean") && (lower.contains("-f") || lower.contains("--force"))) return true;
        // 危险的 shell 元命令
        if (lower.startsWith("eval ")) return true;
        // Windows 磁盘格式化（兼容性考虑）
        if (lower.matches("format\\s+[a-z]:.*")) return true;
        return false;
    }

    /**
     * Sandbox 权限规则覆盖 — 沙箱环境下的特殊权限策略。
     * <p>
     * 当在 Docker 沙箱中运行时，文件系统操作限制在工作目录内，
     * 网络访问可能受限。沙箱提供额外的安全层，允许更宽松的工具权限。
     * <p>
     * 只有确实将在 Docker 沙箱中执行的 Bash 命令可获得此覆盖。
     * FileEdit/FileWrite 在宿主工作区执行，不得借用 Docker 隔离结果自动放行。
     *
     * @param toolName  工具名称
     * @param toolInput 工具输入
     * @return 权限决策（非沙箱环境或不匹配时返回 empty）
     */
    Optional<PermissionDecision> evaluateSandboxRules(String toolName, ToolInput toolInput) {
        if (!sandboxManager.isSandboxingEnabled()) {
            return Optional.empty();
        }
        if (BASH_TOOL_NAMES.contains(toolName)) {
            String command = toolInput.getOptionalString("command").orElse("");
            if (!sandboxManager.shouldUseSandbox(command)) return Optional.empty();
            return Optional.of(PermissionDecision.allow(
                    PermissionDecisionReason.SANDBOX_OVERRIDE, null));
        }
        return Optional.empty();
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

    /**
     * V4: 复合命令风险分级——对命令按管道/链式/分号分割，任一段命中 High Risk 前缀则整体判定为 High Risk。
     * 这是硬门控的安全基础。
     */
    private String assessCommandRiskLevel(String commandStr) {
        if (commandStr == null || commandStr.isBlank()) {
            return "low";
        }

        // 按 |, &&, ||, ; 分割为独立命令段
        String[] segments = commandStr.split("\\s*\\|\\|\\s*|\\s*&&\\s*|\\s*[|;]\\s*");

        for (String segment : segments) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty()) continue;

            // 检查 shell 包装器/权限提升前缀
            if (BARE_SHELL_PREFIXES.stream().anyMatch(p -> trimmed.startsWith(p + " ") || trimmed.equals(p))) {
                return "high";
            }
            // 检查破坏性命令前缀
            if (DESTRUCTIVE_COMMAND_PREFIXES.stream().anyMatch(trimmed::startsWith)) {
                return "high";
            }
        }

        // 额外检查：find -exec/-delete 等参数级危险
        if (commandStr.contains("-exec") || commandStr.contains("-delete")) {
            return "high";
        }

        // 所有段都不高危，按原有逻辑判断只读/非只读
        return bashCommandClassifier.isReadOnlyCommand(commandStr) ? "low" : "medium";
    }

    /**
     * Step 1f: 内容级 ask 规则检查。
     * 即使在 bypass 模式下，危险命令仍需要用户确认。
     */
    @SuppressWarnings("unchecked")
    private PermissionDecision checkContentLevelAsk(Tool tool, ToolInput input) {
        if (!"BashTool".equals(tool.getName()) && !"Bash".equals(tool.getName())) return null;
        String command = input.getOptionalString("command").orElse(null);
        if (command == null) return null;

        // 检查 1: 裸 shell 前缀
        String firstWord = command.trim().split("\\s+")[0];
        if (BARE_SHELL_PREFIXES.contains(firstWord)) {
            return PermissionDecision.ask(
                    PermissionDecisionReason.SAFETY_CHECK,
                    "Shell wrapper '" + firstWord + "' can execute arbitrary commands");
        }

        // 检查 2: 内容级危险模式
        for (Pattern pattern : CONTENT_LEVEL_ASK_PATTERNS) {
            if (pattern.matcher(command).find()) {
                return PermissionDecision.ask(
                        PermissionDecisionReason.SAFETY_CHECK,
                        "Dangerous command pattern detected: " + pattern.pattern());
            }
        }
        return null; // 未命中，继续正常流程
    }
}
