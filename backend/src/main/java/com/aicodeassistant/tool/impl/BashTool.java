package com.aicodeassistant.tool.impl;

import com.aicodeassistant.tool.*;
import com.aicodeassistant.tool.bash.BashCommandClassifier;
import com.aicodeassistant.tool.bash.ShellStateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * BashTool — 执行 shell 命令。
 * <p>
 * P0 实现: ProcessBuilder + 超时 + SIGTERM→SIGKILL 梯度终止。
 * P0 安全: 使用正则分类器 (BashCommandClassifier) 判断只读性。
 * P1 增强: 接入 AST 安全分析器 (BashSecurityAnalyzer)。
 *
 * @see <a href="SPEC §3.2.3">BashTool 规范</a>
 */
@Component
public class BashTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(BashTool.class);

    private static final int MAX_OUTPUT_CHARS = 30_000;
    private static final int DEFAULT_TIMEOUT_MS = 120_000;
    private static final int MAX_TIMEOUT_MS = 600_000;

    private final BashCommandClassifier commandClassifier;
    private final ShellStateManager shellStateManager;

    public BashTool(BashCommandClassifier commandClassifier, ShellStateManager shellStateManager) {
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
                        "description", Map.of("type", "string", "description", "Description of what the command does")
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

    @Override
    public boolean isReadOnly(ToolInput input) {
        String command = input.getString("command");
        return commandClassifier.classify(command).isReadOnly();
    }

    @Override
    public boolean isConcurrencySafe(ToolInput input) {
        return isReadOnly(input);
    }

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        String command = input.getString("command");
        int timeout = Math.min(input.getInt("timeout", DEFAULT_TIMEOUT_MS), MAX_TIMEOUT_MS);
        String sessionId = context.sessionId();

        try {
            // 1. Shell 状态包装
            String wrappedCommand = shellStateManager.wrapCommand(command, sessionId);
            String workingDir = shellStateManager.resolveWorkingDirectory(
                    sessionId, context.workingDirectory());

            // 2. 构建进程
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", wrappedCommand);
            pb.directory(new File(workingDir));
            pb.redirectErrorStream(true);

            // 3. 启动进程并读取输出
            Process process = pb.start();
            StringBuilder output = new StringBuilder();
            boolean truncated = false;

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

        } catch (IOException e) {
            log.error("Failed to execute command: {}", command, e);
            return ToolResult.error("Failed to execute command: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("Command execution interrupted");
        }
    }
}
