package com.aicodeassistant.permission;

import com.aicodeassistant.model.*;
import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.aicodeassistant.hook.HookRegistry;
import com.aicodeassistant.hook.HookService;
import com.aicodeassistant.sandbox.SandboxManager;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

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

    /** 裸壳前缀 — 禁止为这些前缀生成 "Always allow" 建议 */
    private static final Set<String> BARE_SHELL_PREFIXES = Set.of(
            "sh", "bash", "zsh", "fish", "csh", "tcsh", "ksh", "dash",
            "cmd", "powershell", "pwsh",
            "env", "xargs",
            "nice", "stdbuf", "nohup", "timeout", "time",
            "sudo", "doas", "pkexec",
            "su"
    );

    /** 内容级危险模式 (即使 bypass 也强制 ask) */
    private static final List<Pattern> CONTENT_LEVEL_ASK_PATTERNS = List.of(
            Pattern.compile("rm\\s+(-[rRf]+\\s+)*(/|~|\\$HOME)"),
            Pattern.compile("chmod\\s+(-R\\s+)?777\\s+/"),
            Pattern.compile(">(\\s*)/dev/sd[a-z]"),
            Pattern.compile("mkfs\\."),
            Pattern.compile("dd\\s+.*of=/dev/"),
            Pattern.compile(":\\(\\)\\{\\s*:\\|:&\\s*\\};:"),
            Pattern.compile("git\\s+push\\s+.*--force"),
            Pattern.compile("git\\s+(reset|clean)\\s+--hard"),
            Pattern.compile("DROP\\s+(TABLE|DATABASE)", Pattern.CASE_INSENSITIVE)
    );

    /** 异步权限等待 — toolUseId → CompletableFuture<PermissionDecision> */
    private final ConcurrentHashMap<String, CompletableFuture<PermissionDecision>> pendingRequests =
            new ConcurrentHashMap<>();

    private final PermissionRuleMatcher ruleMatcher;
    private final PermissionRuleRepository ruleRepository;
    private final AutoModeClassifier autoModeClassifier;
    private final HookService hookService;
    private final SandboxManager sandboxManager;

    public PermissionPipeline(PermissionRuleMatcher ruleMatcher,
                              PermissionRuleRepository ruleRepository,
                              AutoModeClassifier autoModeClassifier,
                              HookService hookService,
                              SandboxManager sandboxManager) {
        this.ruleMatcher = ruleMatcher;
        this.ruleRepository = ruleRepository;
        this.autoModeClassifier = autoModeClassifier;
        this.hookService = hookService;
        this.sandboxManager = sandboxManager;
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

        // ===== Step 1f: 内容级 ask 规则 (优先于 bypass 模式) =====
        PermissionDecision contentAskDecision = checkContentLevelAsk(tool, input);
        if (contentAskDecision != null) {
            log.debug("Step 1f: content-level ask triggered for tool={}", tool.getName());
            return contentAskDecision;
        }

        // ===== Step 1g: 安全检查（bypass 免疫） =====
        String toolPath = tool.getPath(input);
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

        // ===== Step 1j: Sandbox 权限规则覆盖（新增）=====
        Optional<PermissionDecision> sandboxDecision = evaluateSandboxRules(tool.getName(), input);
        if (sandboxDecision.isPresent()) {
            log.debug("Step 1j: sandbox rule decision for tool={}: {}", tool.getName(), sandboxDecision.get().behavior());
            return sandboxDecision.get();
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

    // ==================== 异步权限请求 ====================

    /**
     * 发起异步权限请求 — 返回 CompletableFuture 等待用户决策。
     * <p>
     * 对齐原版 createPermissionRequestMessage():
     * 通过 WebSocket 推送 permission_request 到前端，前端展示权限对话框，
     * 用户选择后通过 /app/permission 回传，最终 resolvePermission() 完成 future。
     *
     * @param toolUseId  工具调用 ID
     * @param toolName   工具名称
     * @param input      工具输入（前端展示用）
     * @param reason     请求原因（前端展示用）
     * @param wsPusher   WebSocket 推送器（由调用方注入，避免循环依赖）
     * @param sessionId  会话 ID
     * @return 用户决策的 CompletableFuture
     */
    public CompletableFuture<PermissionDecision> requestPermission(
            String toolUseId, String toolName, Map<String, Object> input,
            String reason, PermissionNotifier wsPusher, String sessionId) {

        CompletableFuture<PermissionDecision> future = new CompletableFuture<>();
        pendingRequests.put(toolUseId, future);

        // 通过 WebSocket 推送权限请求到前端
        String riskLevel = BARE_SHELL_PREFIXES.stream()
                .anyMatch(p -> String.valueOf(input.getOrDefault("command", "")).startsWith(p))
                ? "high" : "normal";

        // 记录转发来源信息（当 sessionId 与 wsPusher 的目标会话不同时，说明是子代理冒泡转发）
        log.info("Permission request: toolUseId={}, toolName={}, targetSession={}",
                toolUseId, toolName, sessionId);

        wsPusher.sendPermissionRequest(sessionId, toolUseId, toolName, input, riskLevel, reason);

        // 120 秒超时 → 自动拒绝
        future.orTimeout(120, TimeUnit.SECONDS)
              .exceptionally(ex -> {
                  pendingRequests.remove(toolUseId);
                  log.warn("Permission request timed out: toolUseId={}", toolUseId);
                  return PermissionDecision.denyByMode("Permission request timed out");
              });

        return future;
    }

    /**
     * 解决挂起的权限请求 — 由权限响应处理方调用。
     *
     * @param toolUseId 工具调用 ID
     * @param decision  用户的权限决策
     */
    public void resolvePermission(String toolUseId, PermissionDecision decision) {
        CompletableFuture<PermissionDecision> future = pendingRequests.remove(toolUseId);
        if (future != null) {
            future.complete(decision);
            log.info("Permission resolved: toolUseId={}, behavior={}", toolUseId, decision.behavior());
        } else {
            log.warn("No pending permission request for toolUseId={}", toolUseId);
        }
    }

    /**
     * 取消所有挂起的权限请求（会话中断时调用）。
     */
    public void cancelAllPending() {
        pendingRequests.forEach((id, future) -> {
            future.complete(PermissionDecision.denyByMode("Session interrupted"));
        });
        pendingRequests.clear();
    }

    // ==================== 建议生成 ====================

    /**
     * 生成权限建议 — 对齐原版 suggestions: PermissionUpdate[]。
     * 前端展示 "Allow for this session" / "Always allow" 等选项。
     */
    public List<Map<String, String>> buildSuggestions(Tool tool, ToolInput input) {
        List<Map<String, String>> suggestions = new ArrayList<>();
        suggestions.add(Map.of(
                "label", "Allow for this session",
                "scope", "session",
                "toolName", tool.getName()));
        suggestions.add(Map.of(
                "label", "Always allow " + tool.getName(),
                "scope", "global",
                "toolName", tool.getName()));

        // BashTool: 生成前缀规则建议
        if ("Bash".equals(tool.getName())) {
            String cmd = input.getOptionalString("command").orElse(null);
            if (cmd != null) {
                String prefix = extractCommandPrefix(cmd);
                if (prefix != null && !BARE_SHELL_PREFIXES.contains(prefix.split("\\s+")[0])) {
                    suggestions.add(Map.of(
                            "label", String.format("Always allow 'Bash(%s:*)'", prefix),
                            "scope", "global",
                            "toolName", "Bash",
                            "ruleContent", prefix));
                }
            }
        }
        return suggestions;
    }

    /**
     * 提取命令前缀（前 2 个词）。
     */
    private String extractCommandPrefix(String command) {
        if (command == null || command.isBlank()) return null;
        String[] parts = command.trim().split("\\s+", 3);
        return parts.length >= 2 ? parts[0] + " " + parts[1] : parts[0];
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
        // 当前为骨架实现 — 后续接入 LLM 分类器
        // TODO: 接入 PermissionClassifier.classify(toolName, toolInput) → RiskClassification
        //   SAFE → allow, RISKY → empty (交给用户确认), DESTRUCTIVE → deny
        log.debug("Classifier evaluation skipped (skeleton impl) for tool={}", toolName);
        return Optional.empty();
    }

    /**
     * Sandbox 权限规则覆盖 — 沙箱环境下的特殊权限策略。
     * <p>
     * 当在 Docker 沙箱中运行时，文件系统操作限制在工作目录内，
     * 网络访问可能受限。沙箱提供额外的安全层，允许更宽松的工具权限。
     * <p>
     * 文件操作类工具（FileEdit、FileWrite、Bash）在沙箱中自动允许。
     *
     * @param toolName  工具名称
     * @param toolInput 工具输入
     * @return 权限决策（非沙箱环境或不匹配时返回 empty）
     */
    private Optional<PermissionDecision> evaluateSandboxRules(String toolName, ToolInput toolInput) {
        if (!sandboxManager.isSandboxingEnabled()) {
            return Optional.empty();
        }
        // 沙箱中的文件操作自动允许（沙箱已提供隔离）
        if (Set.of("FileEdit", "FileWrite", "Write", "Edit", "Bash", "BashTool").contains(toolName)) {
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
     * Step 1f: 内容级 ask 规则检查 — 对齐原版 BARE_SHELL_PREFIXES + 危险命令模式。
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
