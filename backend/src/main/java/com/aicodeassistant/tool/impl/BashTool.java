package com.aicodeassistant.tool.impl;

import com.aicodeassistant.sandbox.SandboxManager;
import com.aicodeassistant.security.CommandBlacklistService;
import com.aicodeassistant.tool.*;
import com.aicodeassistant.tool.bash.BashCommandClassifier;
import com.aicodeassistant.tool.bash.BashErrorClassifier;
import com.aicodeassistant.tool.bash.BashErrorClassifier.ErrorClassification;
import com.aicodeassistant.tool.bash.BashOutputProcessor;
import com.aicodeassistant.tool.bash.BashSecurityAnalyzer;
import com.aicodeassistant.tool.bash.CommandCategory;
import com.aicodeassistant.tool.bash.ShellStateManager;
import com.aicodeassistant.tool.bash.ast.BashAstNode.SimpleCommandNode;
import com.aicodeassistant.tool.bash.ast.ParseForSecurityResult;
import com.aicodeassistant.tool.process.ManagedProcessRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * BashTool — 执行 shell 命令。
 * <p>
 * P0 实现: ProcessBuilder + 超时 + SIGTERM→SIGKILL 梯度终止。
 * 安全: 三级降级 AST → 正则 → Python bashlex (§3.2.3c + §3.4)。
 * <ul>
 *   <li>层级 1 (P0 核心): BashSecurityAnalyzer.parseForSecurity() — AST 遍历</li>
 *   <li>层级 2 (降级): BashCommandClassifier.classify() — 正则分割</li>
 *   <li>层级 3 (P1 可选): Python bashlex — /api/bash/parse</li>
 * </ul>
 *
 */
@Component
public class BashTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(BashTool.class);

    private static final int MAX_OUTPUT_CHARS = 30_000;
    private static final long DEFAULT_TIMEOUT_MS = safeParseTimeout(
            System.getenv().getOrDefault("BASH_DEFAULT_TIMEOUT_MS", "120000"), 120_000L);
    private static final long MAX_TIMEOUT_MS = safeParseTimeout(
            System.getenv().getOrDefault("BASH_MAX_TIMEOUT_MS", "600000"), 600_000L);

    /**
     * 安全解析超时环境变量 — 避免 NumberFormatException 导致类加载失败。
     */
    private static long safeParseTimeout(String value, long defaultValue) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            LoggerFactory.getLogger(BashTool.class)
                    .warn("Invalid timeout value '{}', using default {}ms", value, defaultValue);
            return defaultValue;
        }
    }

    private final BashSecurityAnalyzer securityAnalyzer;
    private final BashCommandClassifier commandClassifier;
    private final ShellStateManager shellStateManager;
    private final BashOutputProcessor outputProcessor;
    private final SandboxManager sandboxManager;
    private final CommandBlacklistService commandBlacklistService;
    private final BashErrorClassifier errorClassifier;
    private final ManagedProcessRunner managedProcessRunner;

    @Autowired
    public BashTool(BashSecurityAnalyzer securityAnalyzer,
                    BashCommandClassifier commandClassifier,
                    ShellStateManager shellStateManager,
                    BashOutputProcessor outputProcessor,
                    SandboxManager sandboxManager,
                    CommandBlacklistService commandBlacklistService,
                    BashErrorClassifier errorClassifier,
                    ManagedProcessRunner managedProcessRunner) {
        this.securityAnalyzer = securityAnalyzer;
        this.commandClassifier = commandClassifier;
        this.shellStateManager = shellStateManager;
        this.outputProcessor = outputProcessor;
        this.sandboxManager = sandboxManager;
        this.commandBlacklistService = commandBlacklistService;
        this.errorClassifier = errorClassifier;
        this.managedProcessRunner = managedProcessRunner;
    }

    /**
     * 调用 BashErrorClassifier 对错误进行分类，并将结果注入 ToolResult.metadata。
     * <p>
     * 此方法独立于 {@link com.aicodeassistant.tool.recovery.BashRecoveryPolicy}，
     * 分类器是无状态的纯函数，重复调用不冲突。
     *
     * @param errorMessage 错误消息（作为 ToolResult.error 内容）
     * @param exitCode 进程退出码（超时场景可用 137 表示）
     * @param outputForClassification 用于分类分析的输出（合并 stdout+stderr）
     * @param command 原始命令
     */
    private ToolResult buildErrorWithClassification(String errorMessage,
                                                    int exitCode,
                                                    String outputForClassification,
                                                    String command) {
        ErrorClassification classification = errorClassifier.classify(
                exitCode, outputForClassification, command);
        return ToolResult.failed(ToolResult.ToolFailureType.PROCESS,
                        "BASH_" + classification.type().name(), errorMessage,
                        ToolResult.Retryability.NEVER, ToolResult.EffectState.UNKNOWN,
                        exitCode, Map.of())
                .withMetadata("failure_category", classification.type().name())
                .withMetadata("failure_suggestion", classification.suggestion());
    }

    @Override
    public boolean isHighRisk() {
        return true;
    }

    @Override
    public long getMaxExecutionTimeMs() {
        return 600_000L; // 10 minutes for bash commands
    }

    @Override
    public String getName() {
        return "Bash";
    }

    @Override
    public String getDescription() {
        return "Execute a shell command. Use for running scripts, installing packages, "
                + "compiling code, managing files, and performing system operations.";
    }

    @Override
    public String prompt() {
        return """
                Executes a given bash command and returns its output.
                
                The working directory persists between commands, but shell state does not. \
                The shell environment is initialized from the user's profile (bash or zsh).
                
                IMPORTANT: Avoid using this tool to run `find`, `grep`, `cat`, `head`, `tail`, \
                `sed`, `awk`, or `echo` commands, unless explicitly instructed or after you have \
                verified that a dedicated tool cannot accomplish your task. Instead, use the \
                appropriate dedicated tool as this will provide a much better experience for the user:
                - File search: Use Glob (NOT find or ls)
                - Content search: Use Grep (NOT grep or rg)
                - Read files: Use Read (NOT cat/head/tail)
                - Edit files: Use FileEdit (NOT sed/awk)
                - Write files: Use Write (NOT echo >/cat <<EOF)
                - Communication: Output text directly (NOT echo/printf)
                While the Bash tool can do similar things, it's better to use the built-in tools \
                as they provide a better user experience and make it easier to review tool calls \
                and give permission.
                
                # Instructions
                - If your command will create new directories or files, first use this tool to \
                run `ls` to verify the parent directory exists and is the correct location.
                - Always quote file paths that contain spaces with double quotes in your command \
                (e.g., cd "path with spaces/file.txt")
                - Try to maintain your current working directory throughout the session by using \
                absolute paths and avoiding usage of `cd`. You may use `cd` if the User explicitly \
                requests it.
                - You may specify an optional timeout in milliseconds (up to 600000ms / 10 minutes). \
                By default, your command will timeout after 120000ms (2 minutes).
                - You can use the `is_background` parameter to run the command in the background. \
                Only use this if you don't need the result immediately and are OK being notified \
                when the command completes later.
                - When issuing multiple commands:
                  - If the commands are independent and can run in parallel, make multiple Bash \
                tool calls in a single message.
                  - If the commands depend on each other and must run sequentially, use a single \
                Bash call with '&&' to chain them together.
                  - Use ';' only when you need to run commands sequentially but don't care if \
                earlier commands fail.
                  - DO NOT use newlines to separate commands (newlines are ok in quoted strings).
                - For git commands:
                  - Prefer to create a new commit rather than amending an existing commit.
                  - Before running destructive operations (e.g., git reset --hard, git push --force, \
                git checkout --), consider whether there is a safer alternative. Only use destructive \
                operations when they are truly the best approach.
                  - Never skip hooks (--no-verify) or bypass signing (--no-gpg-sign) unless the \
                user has explicitly asked for it. If a hook fails, investigate and fix the \
                underlying issue.
                - Avoid unnecessary `sleep` commands:
                  - Do not sleep between commands that can run immediately — just run them.
                  - If your command is long running and you would like to be notified when it \
                finishes — use `is_background`. No sleep needed.
                  - Do not retry failing commands in a sleep loop — diagnose the root cause.
                
                # Committing changes with git
                
                Only create commits when requested by the user. If unclear, ask first. \
                When the user asks you to create a new git commit, follow these steps carefully:
                
                Git Safety Protocol:
                - NEVER update the git config
                - NEVER run destructive git commands (push --force, reset --hard, checkout ., \
                restore ., clean -f, branch -D) unless the user explicitly requests these actions
                - NEVER skip hooks (--no-verify, --no-gpg-sign, etc) unless the user explicitly \
                requests it
                - NEVER run force push to main/master, warn the user if they request it
                - CRITICAL: Always create NEW commits rather than amending, unless the user \
                explicitly requests a git amend. When a pre-commit hook fails, the commit did \
                NOT happen — so --amend would modify the PREVIOUS commit. Instead, after hook \
                failure, fix the issue, re-stage, and create a NEW commit
                - When staging files, prefer adding specific files by name rather than using \
                "git add -A" or "git add .", which can accidentally include sensitive files
                - NEVER commit changes unless the user explicitly asks you to
                
                1. Run git status + git diff + git log in parallel
                2. Analyze changes and draft a concise commit message (focus on "why" not "what")
                3. Add relevant files, create the commit, run git status to verify
                4. If the commit fails due to pre-commit hook: fix the issue and create a NEW commit
                
                Important notes:
                - DO NOT push to the remote repository unless the user explicitly asks
                - IMPORTANT: Never use git commands with the -i flag (interactive)
                - If there are no changes to commit, do not create an empty commit
                - Always pass the commit message via a HEREDOC for good formatting
                
                # Creating pull requests
                Use the gh command via the Bash tool for ALL GitHub-related tasks.
                """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "command", Map.of("type", "string", "description", "The shell command to execute"),
                        "timeout", Map.of("type", "integer", "description", "Timeout in milliseconds (default 120000)"),
                        "description", Map.of("type", "string", "description", "Description of what the command does"),
                        "is_background", Map.of("type", "boolean", "description", "Run command in background, returning immediately with process ID"),
                        "declared_outputs", Map.of(
                                "type", "array",
                                "description", "Outputs explicitly declared before execution; undeclared side effects are not artifacts",
                                "items", Map.of("type", "object", "properties", Map.of(
                                        "path", Map.of("type", "string"),
                                        "operation", Map.of("type", "string", "enum", List.of("created", "modified", "deleted")),
                                        "requiredValidatorId", Map.of("type", "string")),
                                        "required", List.of("path", "operation")))
                ),
                "required", java.util.List.of("command")
        );
    }

    @Override
    public String getGroup() {
        return "bash";
    }

    @Override
    public PermissionRequirement getPermissionRequirement() {
        return PermissionRequirement.CONDITIONAL;
    }

    // ═══════════════════════════════════════════════════════════════════
    // [v1.57.0 G2] isReadOnly() — 基于 AST 分析的只读判断
    //
    // 修复: 原实现使用首 token 判断,
    //       导致 "cat file; rm -rf /" 被误判为只读。
    // 新实现: 通过 AST 遍历所有子命令 argv[0],
    //         全部为 search/read/list → 只读。
    // ═══════════════════════════════════════════════════════════════════
    @Override
    public boolean isReadOnly(ToolInput input) {
        String command = input.getString("command");

        ParseForSecurityResult result = securityAnalyzer.parseForSecurity(command);
        if (result instanceof ParseForSecurityResult.Simple simple) {
            // 所有简单命令均通过 isSearchOrRead 检查 → 整体只读
            return simple.commands().stream().allMatch(cmd -> {
                String argv0 = cmd.argv().isEmpty() ? "" : cmd.argv().getFirst();
                if (commandClassifier.isSearchOrReadCommand(argv0)) {
                    return true;
                }
                // 二次判定：拼接完整命令调用 isReadOnlyCommand（覆盖 git/docker/gh 等只读子命令）
                String fullCmd = String.join(" ", cmd.argv());
                return commandClassifier.isReadOnlyCommand(fullCmd);
            });
        }
        // AST 解析失败或 too-complex → 降级到正则分类器
        return commandClassifier.classify(command).isReadOnly();
    }

    @Override
    public boolean isConcurrencySafe(ToolInput input) {
        return isReadOnly(input);
    }

    // ═══════════════════════════════════════════════════════════════════
    // [v1.57.0 G3] isDestructive() — 统一使用黑名单服务判定
    // ═══════════════════════════════════════════════════════════════════
    @Override
    public boolean isDestructive(ToolInput input) {
        String command = input.getString("command");
        if (command == null || command.isBlank()) return false;
        CommandBlacklistService.BlockResult result = commandBlacklistService.checkCommand(command);
        return result.level() == CommandBlacklistService.BlockLevel.HIGH_RISK_ASK
                || result.level() == CommandBlacklistService.BlockLevel.ABSOLUTE_DENY;
    }

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        String command = input.getString("command");

        // 动态超时策略：LLM显式指定timeout时尊重其选择，否则基于命令类型推荐
        long timeout;
        if (input.has("timeout")) {
            // LLM显式指定了timeout，尊重其选择（受MAX_TIMEOUT_MS上限约束）
            timeout = Math.min((long) input.getInt("timeout", (int) DEFAULT_TIMEOUT_MS), MAX_TIMEOUT_MS);
        } else {
            // LLM未指定timeout，使用基于命令类型的动态推荐
            CommandCategory timeoutCategory = commandClassifier.classifyForTimeout(command);
            long recommended = timeoutCategory.getRecommendedTimeoutMs();
            timeout = Math.min(recommended, MAX_TIMEOUT_MS);
            if (recommended != DEFAULT_TIMEOUT_MS) {
                log.debug("Dynamic timeout applied: {} → {}ms (category: {})",
                    command.substring(0, Math.min(50, command.length())), recommended, timeoutCategory);
            }
        }
        boolean isBackground = input.getBoolean("is_background", false);
        String sessionId = context.sessionId();

        try {
            // 绝对禁止命令最终防线（纵深防御）
            CommandBlacklistService.BlockResult blockResult = commandBlacklistService.checkCommand(command);
            if (blockResult.level() == CommandBlacklistService.BlockLevel.ABSOLUTE_DENY) {
                return ToolResult.permissionDenied("COMMAND_ABSOLUTELY_DENIED", blockResult.reason());
            }

            // 1. Shell 状态包装
            String wrappedCommand = shellStateManager.wrapCommand(command, sessionId);
            String workingDir = shellStateManager.resolveWorkingDirectory(
                    sessionId, context.workingDirectory());

            // 后台执行模式
            if (isBackground) {
                if (managedProcessRunner == null || context.currentRunId() == null
                        || context.toolUseId() == null) {
                    return ToolResult.failed(ToolResult.ToolFailureType.PROCESS,
                            "PROCESS_OWNERSHIP_MISSING", "Background process requires runId and toolUseId",
                            ToolResult.Retryability.NEVER, ToolResult.EffectState.NOT_STARTED,
                            null, Map.of());
                }
                long pid = managedProcessRunner.startBackground(new ManagedProcessRunner.BackgroundRequest(
                        List.of("bash", "-c", wrappedCommand), Path.of(workingDir),
                        context.currentRunId(), context.toolUseId(), context.sessionId())).pid();
                log.info("Background process started: pid={}, command={}", pid, command);
                return ToolResult.backgroundStarted(String.format(
                        "Background process started with PID %d.%n"
                        + "It remains owned by the current session; use `kill %d` to stop it earlier.%n"
                        + "Note: output is not captured for background processes.",
                        pid, pid), pid);
            }

            // UI 分类日志（第四层，独立于安全分类）
            CommandCategory uiCategory = commandClassifier.classifyForUI(command);
            log.debug("Executing command [category={}]: {}", uiCategory.getDisplayLabel(), command);

            // === 沙箱路由判断：高危命令进容器执行 ===
            if (sandboxManager.isSandboxingEnabled() && sandboxManager.shouldUseSandbox(command)) {
                return executeSandboxed(command, workingDir, timeout, context);
            }

            if (context.currentRunId() == null || context.currentRunId().isBlank()
                    || context.toolUseId() == null || context.toolUseId().isBlank()) {
                return ToolResult.failed(ToolResult.ToolFailureType.PROCESS,
                        "PROCESS_OWNERSHIP_MISSING",
                        "Managed process requires runId and toolUseId",
                        ToolResult.Retryability.NEVER, ToolResult.EffectState.NOT_STARTED,
                        null, Map.of());
            }
            ManagedProcessRunner.Result result = managedProcessRunner.run(
                    new ManagedProcessRunner.Request(List.of("bash", "-c", wrappedCommand),
                            Path.of(workingDir), Duration.ofMillis(timeout),
                            context.currentRunId(), context.toolUseId()));
            String combined = result.stdout() + (result.stderr().isBlank()
                    ? "" : (result.stdout().isBlank() ? "" : "\n") + result.stderr());
            if (result.cancelled()) {
                return ToolResult.cancelled("PROCESS_CANCELLED", "Command cancelled",
                        ToolResult.EffectState.UNKNOWN)
                        .withMetadata("terminationConfirmed", result.terminationConfirmed())
                        .withMetadata("descendantTrackingUnavailable", result.descendantTrackingUnavailable());
            }
            if (result.timedOut()) {
                return ToolResult.timedOut("PROCESS_DEADLINE_EXCEEDED",
                        "Command timed out after " + timeout + "ms\n" + combined,
                        137, result.terminationConfirmed())
                        .withMetadata("terminationConfirmed", result.terminationConfirmed())
                        .withMetadata("descendantTrackingUnavailable", result.descendantTrackingUnavailable())
                        .withMetadata("stdoutTruncated", result.stdoutTruncated())
                        .withMetadata("stderrTruncated", result.stderrTruncated());
            }
            shellStateManager.updateStateFromSnapshot(sessionId);
            boolean failed = result.exitCode() != 0;
            String processed = outputProcessor.processOutput(combined, failed);
            if (failed) return buildErrorWithClassification(
                    "Exit code: " + result.exitCode() + "\n" + processed,
                    result.exitCode(), combined, command);
            return ToolResult.successWithEffect(processed, ToolResult.EffectState.UNKNOWN)
                    .withMetadata("exitCode", result.exitCode())
                    .withMetadata("truncated", result.stdoutTruncated() || result.stderrTruncated())
                    .withMetadata("elapsedMs", result.elapsedMs());

        } catch (IOException e) {
            log.error("Failed to execute command: {}", command, e);
            String errMsg = "Failed to execute command: " + e.getMessage();
            // 进程启动失败 → exitCode=-1，由分类器基于 stderr 关键字判断（默认 NON_RETRYABLE）
            return buildErrorWithClassification(errMsg, -1, e.getMessage() == null ? "" : e.getMessage(), command);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return buildErrorWithClassification("Command execution interrupted", -1, "interrupted", command);
        }
    }

    /**
     * 在沙箱容器中执行命令（用于高危命令隔离）。
     */
    private ToolResult executeSandboxed(String command, String workingDir, long timeoutMs,
                                        ToolUseContext context) throws java.io.IOException, InterruptedException {
        if (managedProcessRunner == null || context.currentRunId() == null || context.toolUseId() == null) {
            return ToolResult.failed(ToolResult.ToolFailureType.PROCESS, "PROCESS_OWNERSHIP_MISSING",
                    "Sandbox process requires runId and toolUseId", ToolResult.Retryability.NEVER,
                    ToolResult.EffectState.NOT_STARTED, null, Map.of());
        }
        var invocation = sandboxManager.prepareInvocation(command, Path.of(workingDir), Map.of(),
                context.currentRunId(), context.toolUseId());
        long effectiveTimeout = Math.min(timeoutMs, sandboxManager.getTimeoutSeconds() * 1000L);
        ManagedProcessRunner.Result result = managedProcessRunner.run(new ManagedProcessRunner.Request(
                invocation.command(), Path.of(workingDir), java.time.Duration.ofMillis(effectiveTimeout),
                context.currentRunId(), context.toolUseId(), invocation.cleanup()));
        if (result.cancelled()) {
            return ToolResult.cancelled("PROCESS_CANCELLED", "Sandboxed command cancelled",
                    ToolResult.EffectState.UNKNOWN)
                    .withMetadata("sandboxed", true)
                    .withMetadata("containerName", invocation.containerName())
                    .withMetadata("terminationConfirmed", result.terminationConfirmed());
        }
        if (result.timedOut()) {
            String timeoutMsg = "Sandboxed command timed out after "
                    + effectiveTimeout + "ms";
            ErrorClassification classification = errorClassifier.classify(137, timeoutMsg, command);
            return ToolResult.timedOut("PROCESS_DEADLINE_EXCEEDED", timeoutMsg, 137,
                            result.terminationConfirmed())
                    .withMetadata("sandboxed", true)
                    .withMetadata("containerName", invocation.containerName())
                    .withMetadata("terminationConfirmed", result.terminationConfirmed())
                    .withMetadata("failure_category", classification.type().name())
                    .withMetadata("failure_suggestion", classification.suggestion());
        }
        if (!result.terminationConfirmed()) {
            return ToolResult.failed(ToolResult.ToolFailureType.PROCESS,
                    "PROCESS_TERMINATION_UNCONFIRMED",
                    "Sandbox command exited but container cleanup could not be confirmed",
                    ToolResult.Retryability.NEVER, ToolResult.EffectState.UNKNOWN,
                    result.exitCode(), Map.of("sandboxed", true,
                            "containerName", invocation.containerName(),
                            "terminationConfirmed", false));
        }
        String output = result.stdout() + (result.stderr().isBlank() ? "" : "\n" + result.stderr());
        if (result.exitCode() != 0) {
            String errorBody = "Exit code: " + result.exitCode() + "\n" + output;
            return buildErrorWithClassification(errorBody, result.exitCode(), output, command);
        }
        return ToolResult.successWithEffect(output, ToolResult.EffectState.UNKNOWN)
                .withMetadata("sandboxed", true)
                .withMetadata("exitCode", result.exitCode())
                .withMetadata("containerName", invocation.containerName())
                .withMetadata("terminationConfirmed", result.terminationConfirmed());
    }
}
