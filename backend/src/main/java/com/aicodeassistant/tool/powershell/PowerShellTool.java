package com.aicodeassistant.tool.powershell;

import com.aicodeassistant.model.PermissionBehavior;
import com.aicodeassistant.tool.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * PowerShellTool — Windows 平台上 BashTool 的等价实现。
 * <p>
 * 在 Windows 上执行 PowerShell 命令，优先使用 pwsh (PowerShell 7+)，
 * 降级到 powershell.exe (Windows PowerShell 5.1)。
 * <p>
 * 条件注册: 仅在 Windows 平台上注册为 Spring Bean。
 *
 * @see <a href="SPEC §4.1.10">PowerShellTool</a>
 */
@Component
@Conditional(WindowsCondition.class)
public class PowerShellTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(PowerShellTool.class);

    /** 默认超时 (毫秒) */
    static final int DEFAULT_TIMEOUT_MS = 120_000;

    @Override
    public String getName() {
        return "PowerShell";
    }

    @Override
    public String getDescription() {
        return "Execute PowerShell commands on Windows. " +
                "Supports both pwsh (PowerShell 7+) and powershell.exe (Windows PowerShell 5.1).";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "command", Map.of(
                                "type", "string",
                                "description", "PowerShell command to execute"
                        ),
                        "timeout", Map.of(
                                "type", "integer",
                                "description", "Timeout in milliseconds (default: 120000)"
                        )
                ),
                "required", List.of("command")
        );
    }

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        String command = input.getString("command");
        int timeout = input.getInt("timeout", DEFAULT_TIMEOUT_MS);

        // 1. 检测可用的 PowerShell 可执行文件
        String psExe = detectPowerShellExecutable();
        if (psExe == null) {
            return ToolResult.error(
                    "PowerShell not found. Requires pwsh (PowerShell 7+) or powershell.exe.");
        }

        try {
            // 2. 构建 PowerShell 命令
            // pwsh -NoProfile -NonInteractive -Command "..."
            String escapedCommand = command.replace("\"", "`\"");
            ProcessBuilder pb = new ProcessBuilder(
                    psExe, "-NoProfile", "-NonInteractive", "-Command", escapedCommand)
                    .directory(context.workingDirectory() != null
                            ? new java.io.File(context.workingDirectory()) : null);

            // 3. 执行
            Process process = pb.start();

            // 读取 stdout
            StringBuilder stdout = new StringBuilder();
            try (var reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stdout.append(line).append("\n");
                }
            }

            // 读取 stderr
            StringBuilder stderr = new StringBuilder();
            try (var reader = new BufferedReader(
                    new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stderr.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(timeout, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolResult.error("PowerShell command timed out after " + timeout + "ms");
            }

            int exitCode = process.exitValue();
            String output = stdout.toString();
            Map<String, Object> metadata = new java.util.HashMap<>();
            metadata.put("exitCode", exitCode);
            metadata.put("psExecutable", psExe);
            if (!stderr.isEmpty()) {
                metadata.put("stderr", stderr.toString().trim());
            }

            return new ToolResult(output, exitCode != 0, metadata);
        } catch (Exception e) {
            log.error("PowerShell execution failed: {}", e.getMessage(), e);
            return ToolResult.error("PowerShell error: " + e.getMessage());
        }
    }

    /**
     * 检测可用的 PowerShell 可执行文件。
     * <p>
     * 优先 pwsh (PowerShell 7+, 跨平台)，降级到 powershell.exe。
     */
    String detectPowerShellExecutable() {
        for (String exe : List.of("pwsh", "powershell.exe")) {
            try {
                Process p = new ProcessBuilder(exe, "-Version")
                        .redirectErrorStream(true).start();
                if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    return exe;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    @Override
    public PermissionRequirement getPermissionRequirement() {
        return PermissionRequirement.ALWAYS_ASK;
    }

    @Override
    public boolean isDestructive(ToolInput input) {
        return true;
    }

    @Override
    public boolean isOpenWorld() {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(ToolInput input) {
        return false;
    }

    @Override
    public boolean isReadOnly(ToolInput input) {
        return false;
    }

    @Override
    public String getGroup() {
        return "bash";
    }

    @Override
    public String toAutoClassifierInput(ToolInput input) {
        return "PowerShell: " + input.getString("command", "");
    }
}
