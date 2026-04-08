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
            "shred", "truncate");

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
    public ToolResult call(ToolInput input, ToolUseContext context) {
        String command = input.getString("command");
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
                Process process = pb.start();
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
