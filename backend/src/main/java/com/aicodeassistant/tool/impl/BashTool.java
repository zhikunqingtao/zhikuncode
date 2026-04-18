package com.aicodeassistant.tool.impl;

import com.aicodeassistant.model.PermissionBehavior;
import com.aicodeassistant.tool.*;
import com.aicodeassistant.tool.bash.BashCommandClassifier;
import com.aicodeassistant.tool.bash.BashSecurityAnalyzer;
import com.aicodeassistant.tool.bash.ShellStateManager;
import com.aicodeassistant.tool.bash.ast.BashAstNode.SimpleCommandNode;
import com.aicodeassistant.tool.bash.ast.ParseForSecurityResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
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
 * @see <a href="SPEC §3.2.3">BashTool 规范</a>
 * @see <a href="SPEC §3.2.3c">AST 安全分析子系统</a>
 */
@Component
public class BashTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(BashTool.class);

    private static final int MAX_OUTPUT_CHARS = 30_000;
    private static final int DEFAULT_TIMEOUT_MS = 120_000;
    private static final int MAX_TIMEOUT_MS = 600_000;

    // 高危破坏性命令 — 经 AST 包装命令剥离后检查 argv[0]
    private static final Set<String> DESTRUCTIVE_COMMANDS = Set.of(
            "rm", "rmdir", "chmod", "chown", "mkfs", "dd",
            "shred", "truncate", "wipefs", "fdisk", "parted",
            "kill", "killall", "pkill", "reboot", "shutdown",
            "halt", "poweroff", "init", "systemctl",
            "sudo", "su", "doas");  // 禁止特权提升命令

    // 绝对拦截命令 — 这些命令在任何情况下都必须 100% 拒绝执行
    private static final Set<String> BLOCKED_COMMANDS = Set.of(
            "sudo", "su", "doas");  // 特权提升命令

    // 危险 flag 模式 — 检测特定命令+危险flag组合
    private static final Map<String, Set<String>> DANGEROUS_FLAG_PATTERNS = Map.of(
            "rm", Set.of("-rf", "-fr", "--recursive", "--force"),
            "chmod", Set.of("777", "755", "666", "+s", "u+s", "g+s"),
            "chown", Set.of("-R", "--recursive")
    );

    private final BashSecurityAnalyzer securityAnalyzer;
    private final BashCommandClassifier commandClassifier;
    private final ShellStateManager shellStateManager;

    public BashTool(BashSecurityAnalyzer securityAnalyzer,
                    BashCommandClassifier commandClassifier,
                    ShellStateManager shellStateManager) {
        this.securityAnalyzer = securityAnalyzer;
        this.commandClassifier = commandClassifier;
        this.shellStateManager = shellStateManager;
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
                        "is_background", Map.of("type", "boolean", "description", "Run command in background, returning immediately with process ID")
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
    // [v1.57.0 G1] checkPermissions() — BashTool 权限检查核心入口
    //
    // 对照源码: bashPermissions.ts bashToolHasPermission()
    // 此方法在 PermissionPipeline Step 1c 被调用 (§3.4.3a)。
    // 三级降级: AST → 正则 → Python bashlex (fail-closed)
    // ═══════════════════════════════════════════════════════════════════
    @Override
    public PermissionBehavior checkPermissions(ToolInput input, ToolUseContext context) {
        String command = input.getString("command");

        // ── Step 0: AST 安全解析 (§3.2.3c parseForSecurity) ──
        ParseForSecurityResult astResult = securityAnalyzer.parseForSecurity(command);

        return switch (astResult) {
            case ParseForSecurityResult.Simple simple -> {
                // AST 解析成功 → 所有子命令均通过 checkSemantics
                if (simple.commands().isEmpty()) {
                    // 空命令或纯赋值 → passthrough
                    yield PermissionBehavior.PASSTHROUGH;
                }
                // 全部子命令 argv[0] 为只读 → passthrough
                boolean allReadOnly = simple.commands().stream().allMatch(cmd -> {
                    String argv0 = cmd.argv().isEmpty() ? "" : cmd.argv().getFirst();
                    return commandClassifier.isSearchOrReadCommand(argv0);
                });
                if (allReadOnly) {
                    yield PermissionBehavior.PASSTHROUGH;
                }
                // 非只读 → ask 用户确认
                yield PermissionBehavior.ASK;
            }

            case ParseForSecurityResult.TooComplex tooComplex -> {
                // AST 标记为复杂 → ask 用户确认
                log.debug("AST too-complex: {} (node: {})", tooComplex.reason(), tooComplex.nodeType());
                yield PermissionBehavior.ASK;
            }

            case ParseForSecurityResult.ParseUnavailable unavailable -> {
                // ── 降级路径: 正则分类器 (层级 2) ──
                yield handleParseUnavailable(command);
            }
        };
    }

    /**
     * AST 解析不可用时的降级处理。
     * <p>
     * 降级链: 正则分类器 → Python bashlex → fail-closed ask。
     */
    private PermissionBehavior handleParseUnavailable(String command) {
        // 尝试正则分类器快速判断 (覆盖 90%+ 简单命令)
        var classification = commandClassifier.classify(command);
        if (classification.isReadOnly()) {
            return PermissionBehavior.PASSTHROUGH;
        }
        // 无法安全分类 → fail-closed: ask 用户确认
        log.debug("Parse unavailable + regex classifier unable to classify, fail-closed ask");
        return PermissionBehavior.ASK;
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
                return commandClassifier.isSearchOrReadCommand(argv0);
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
    // [v1.57.0 G3] isDestructive() — 基于 AST 分析的破坏性判断
    // ═══════════════════════════════════════════════════════════════════
    @Override
    public boolean isDestructive(ToolInput input) {
        String command = input.getString("command");

        ParseForSecurityResult result = securityAnalyzer.parseForSecurity(command);
        if (result instanceof ParseForSecurityResult.Simple simple) {
            return simple.commands().stream().anyMatch(cmd -> {
                String argv0 = cmd.argv().isEmpty() ? "" : cmd.argv().getFirst();
                return DESTRUCTIVE_COMMANDS.contains(argv0);
            });
        }
        // 无法判断 → 保守返回 false (由 checkPermissions 的 ask 兜底)
        return false;
    }

    @Override
    public String toAutoClassifierInput(ToolInput input) {
        return input.getString("command");
    }

    @Override
    public PermissionBehavior preparePermissionMatcher(ToolInput input) {
        String command = input.getOptionalString("command").orElse(null);
        if (command == null || command.isBlank()) return PermissionBehavior.PASSTHROUGH;
        // 提取命令前缀用于匹配 alwaysAllow/deny 规则
        // 裸 shell 前缀的 ASK 逻辑已在 PermissionPipeline.checkContentLevelAsk 中处理
        return PermissionBehavior.PASSTHROUGH;
    }

    /**
     * 预执行安全验证 — 在执行前拦截绝对禁止的危险命令。
     * <p>
     * 检查项:
     * 1. 特权提升命令 (sudo/su/doas) → 100% 拦截
     * 2. 危险 rm -rf / 模式 → 拦截
     * 3. chmod 危险权限修改 → 拦截
     * 4. 危险 flag 组合检测
     *
     * @param command 待执行命令
     * @return 拒绝原因，null 表示通过验证
     */
    private String validateCommandSafety(String command) {
        if (command == null || command.isBlank()) return null;
        String trimmed = command.trim();

        // 1. 拆分管道/链式命令，逐段检查
        String[] segments = trimmed.split("\\s*(?:\\|\\||&&|[|;])\\s*");
        for (String segment : segments) {
            String seg = segment.trim();
            if (seg.isEmpty()) continue;

            // 剥离环境变量赋值前缀 (KEY=VAL)
            String stripped = seg.replaceAll("^(\\w+=\\S*\\s+)+", "");

            // 提取首 token
            String[] tokens = stripped.split("\\s+");
            if (tokens.length == 0) continue;
            String argv0 = tokens[0];

            // 1a. 特权提升命令绝对拦截
            if (BLOCKED_COMMANDS.contains(argv0)) {
                return String.format("Blocked: '%s' is a privilege escalation command and is not allowed. "
                        + "Execute commands directly without privilege escalation.", argv0);
            }

            // 1b. 危险 rm -rf / 模式检测
            if ("rm".equals(argv0)) {
                String argsStr = stripped.substring(2).trim();
                boolean hasRecursiveForce = argsStr.contains("-rf") || argsStr.contains("-fr")
                        || (argsStr.contains("--recursive") && argsStr.contains("--force"));
                if (hasRecursiveForce) {
                    // 检查是否针对根目录或关键系统目录
                    for (String token : tokens) {
                        if ("/".equals(token) || "/*".equals(token)
                                || token.startsWith("/etc") || token.startsWith("/usr")
                                || token.startsWith("/bin") || token.startsWith("/sbin")
                                || token.startsWith("/boot") || token.startsWith("/var")
                                || token.startsWith("/sys") || token.startsWith("/proc")
                                || token.equals("~") || token.equals("$HOME")) {
                            return String.format("Blocked: 'rm -rf %s' targets a critical system path "
                                    + "and is not allowed.", token);
                        }
                    }
                }
            }

            // 1c. chmod 危险权限模式检测
            if ("chmod".equals(argv0) && tokens.length >= 2) {
                for (int i = 1; i < tokens.length; i++) {
                    String t = tokens[i];
                    if ("777".equals(t) || "+s".equals(t) || "u+s".equals(t) || "g+s".equals(t)) {
                        return String.format("Blocked: 'chmod %s' sets dangerous permissions "
                                + "and is not allowed.", t);
                    }
                }
            }

            // 1d. 危险 flag 组合检测
            Set<String> dangerousFlags = DANGEROUS_FLAG_PATTERNS.get(argv0);
            if (dangerousFlags != null) {
                for (int i = 1; i < tokens.length; i++) {
                    if (dangerousFlags.contains(tokens[i])) {
                        log.debug("Dangerous flag detected: {} {} (flag: {})",
                                argv0, tokens[i], tokens[i]);
                        // 仅标记日志，不拦截 —— 由权限系统(ASK)决定
                        break;
                    }
                }
            }
        }
        return null; // 验证通过
    }

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        String command = input.getString("command");

        // ── 预执行安全验证 — 绝对拦截危险命令 ──
        String rejection = validateCommandSafety(command);
        if (rejection != null) {
            log.warn("Command blocked by safety validation: {} — reason: {}", command, rejection);
            return ToolResult.error(rejection);
        }

        int timeout = Math.min(input.getInt("timeout", DEFAULT_TIMEOUT_MS), MAX_TIMEOUT_MS);
        boolean isBackground = input.getBoolean("is_background", false);
        String sessionId = context.sessionId();

        try {
            // 1. Shell 状态包装
            String wrappedCommand = shellStateManager.wrapCommand(command, sessionId);
            String workingDir = shellStateManager.resolveWorkingDirectory(
                    sessionId, context.workingDirectory());

            // 后台执行模式
            if (isBackground) {
                ProcessBuilder pb = new ProcessBuilder("bash", "-c", wrappedCommand);
                pb.directory(new File(workingDir));
                pb.redirectErrorStream(true);
                // P1-05: 后台进程重定向输出到 /dev/null，防止管道填满导致进程阻塞
                pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                pb.redirectError(ProcessBuilder.Redirect.DISCARD);
                Process process = pb.start();
                // 关闭 stdin，防止资源泄漏
                process.getOutputStream().close();
                long pid = process.pid();
                log.info("Background process started: pid={}, command={}", pid, command);
                return ToolResult.success(String.format(
                        "Background process started with PID %d.%n"
                        + "Use `kill %d` to stop it.%n"
                        + "Note: output is not captured for background processes.",
                        pid, pid));
            }

            // 2. 构建进程
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", wrappedCommand);
            pb.directory(new File(workingDir));
            pb.redirectErrorStream(true);

            // 3. 启动进程并读取输出
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            boolean truncated = false;

            try {
                try (var reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (output.length() + line.length() > MAX_OUTPUT_CHARS) {
                            truncated = true;
                            break;
                        }
                        output.append(line).append('\n');
                    }
                }

                // 4. 等待进程完成 (含超时)
                // 梯度终止: SIGTERM → 等 2s → SIGKILL
                boolean completed = process.waitFor(timeout, TimeUnit.MILLISECONDS);
                if (!completed) {
                    process.destroy(); // SIGTERM
                    if (!process.waitFor(2, TimeUnit.SECONDS)) {
                        process.destroyForcibly(); // SIGKILL
                    }
                    return ToolResult.error("Command timed out after " + timeout + "ms");
                }

                int exitCode = process.exitValue();
                String stdout = output.toString();

                // 5. 更新 Shell 状态
                shellStateManager.updateStateFromSnapshot(sessionId);

                // 6. 构建结果
                if (exitCode != 0) {
                    return ToolResult.error("Exit code: " + exitCode + "\n" + stdout);
                }

                return ToolResult.success(stdout)
                        .withMetadata("exitCode", exitCode)
                        .withMetadata("truncated", truncated);
            } finally {
                // 确保关闭所有流，防止资源泄漏
                try { process.getInputStream().close(); } catch (Exception ignored) {}
                try { process.getErrorStream().close(); } catch (Exception ignored) {}
                try { process.getOutputStream().close(); } catch (Exception ignored) {}
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }

        } catch (IOException e) {
            log.error("Failed to execute command: {}", command, e);
            return ToolResult.error("Failed to execute command: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Command execution interrupted");
        }
    }
}
