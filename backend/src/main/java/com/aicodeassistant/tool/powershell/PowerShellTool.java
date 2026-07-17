package com.aicodeassistant.tool.powershell;

import com.aicodeassistant.model.PermissionBehavior;
import com.aicodeassistant.tool.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import com.aicodeassistant.tool.process.ManagedProcessRunner;
import java.nio.file.Path;
import java.time.Duration;

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
 */
@Component
@Conditional(WindowsCondition.class)
public class PowerShellTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(PowerShellTool.class);

    /** 默认超时 (毫秒) */
    static final int DEFAULT_TIMEOUT_MS = 120_000;
    private final ManagedProcessRunner processRunner;

    @Autowired
    public PowerShellTool(ManagedProcessRunner processRunner) { this.processRunner = processRunner; }
    public PowerShellTool() { this.processRunner = null; }

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
    public String prompt() {
        return """
                Executes a given PowerShell command with optional timeout. Working directory persists \
                between commands; shell state (variables, functions) does not.

                IMPORTANT: This tool is for terminal operations via PowerShell: git, npm, docker, and \
                PS cmdlets. DO NOT use it for file operations (reading, writing, editing, searching, \
                finding files) - use the specialized tools for this instead.

                PowerShell edition guidance:
                - If using Windows PowerShell 5.1: Pipeline chain operators `&&` and `||` are NOT available. \
                To run B only if A succeeds: `A; if ($?) { B }`. To chain unconditionally: `A; B`.
                - If using PowerShell 7+ (pwsh): `&&` and `||` ARE available and work like bash.
                - When unsure, assume 5.1 for compatibility — do NOT use `&&`, `||`, ternary `?:`, \
                null-coalescing `??`, or null-conditional `?.`.

                Before executing the command, follow these steps:
                1. Directory Verification: If the command will create new directories or files, first use \
                `Get-ChildItem` (or `ls`) to verify the parent directory exists and is the correct location.
                2. Command Execution: Always quote file paths that contain spaces with double quotes. \
                Capture the output of the command.

                PowerShell Syntax Notes:
                - Variables use $ prefix: $myVar = "value"
                - Escape character is backtick (`), not backslash
                - Use Verb-Noun cmdlet naming: Get-ChildItem, Set-Location, New-Item, Remove-Item
                - Common aliases: ls (Get-ChildItem), cd (Set-Location), cat (Get-Content), rm (Remove-Item)
                - Pipe operator | passes objects, not text
                - Use Select-Object, Where-Object, ForEach-Object for filtering and transformation
                - String interpolation: "Hello $name" or "Hello $($obj.Property)"
                - Registry access: `HKLM:\\SOFTWARE\\...`, `HKCU:\\...` — NOT raw HKEY_LOCAL_MACHINE
                - Environment variables: read with `$env:NAME`, set with `$env:NAME = "value"`
                - Call native exe with spaces in path: `& "C:\\Program Files\\App\\app.exe" arg1 arg2`

                Interactive and blocking commands (will hang — runs with -NonInteractive):
                - NEVER use `Read-Host`, `Get-Credential`, `Out-GridView`, `$Host.UI.PromptForChoice`, or `pause`
                - Destructive cmdlets may prompt for confirmation. Add `-Confirm:$false` when you intend \
                the action to proceed. Use `-Force` for read-only/hidden items.
                - Never use `git rebase -i`, `git add -i`, or other commands that open an interactive editor

                Passing multiline strings to native executables:
                - Use a single-quoted here-string so PowerShell does not expand `$` or backticks inside. \
                The closing `'@` MUST be at column 0 (no leading whitespace) on its own line.
                - Use `@'...'@` (single-quoted, literal) not `@"..."@` (double-quoted, interpolated) \
                unless you need variable expansion.

                Usage notes:
                - The command argument is required.
                - You can specify an optional timeout in milliseconds (up to 600000ms / 10 minutes). \
                If not specified, commands will timeout after 120000ms (2 minutes).
                - Write a clear, concise description of what this command does.
                - Avoid using PowerShell to run commands that have dedicated tools, unless explicitly instructed:
                  - File search: Use GlobTool (NOT Get-ChildItem -Recurse)
                  - Content search: Use GrepTool (NOT Select-String)
                  - Read files: Use FileRead (NOT Get-Content)
                  - Edit files: Use FileEdit
                  - Write files: Use FileWrite (NOT Set-Content/Out-File)
                  - Communication: Output text directly (NOT Write-Output/Write-Host)
                - When issuing multiple commands:
                  - If the commands are independent, make multiple PowerShell tool calls in a single message.
                  - If the commands depend on each other, chain them in a single call using edition-specific syntax.
                  - Use `;` only when you need to run commands sequentially but don't care if earlier commands fail.
                  - DO NOT use newlines to separate commands (newlines are ok in quoted strings and here-strings).
                - Do NOT prefix commands with `cd` or `Set-Location` -- the working directory is already set automatically.
                - For git commands:
                  - Prefer to create a new commit rather than amending an existing commit.
                  - Before running destructive operations (e.g., git reset --hard, git push --force), consider \
                safer alternatives. Only use destructive operations when truly the best approach.
                  - Never skip hooks (--no-verify) or bypass signing unless the user has explicitly asked for it.
                """;
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
            return ToolResult.validationError("POWERSHELL_EXECUTABLE_NOT_FOUND",
                    "PowerShell not found. Requires pwsh (PowerShell 7+) or powershell.exe.");
        }

        try {
            String escapedCommand = command.replace("\"", "`\"");
            if (processRunner == null || context.currentRunId() == null || context.toolUseId() == null)
                return ToolResult.failed(ToolResult.ToolFailureType.PROCESS, "PROCESS_OWNERSHIP_MISSING",
                        "PowerShell requires managed process ownership", ToolResult.Retryability.NEVER,
                        ToolResult.EffectState.NOT_STARTED, null, Map.of());
            ManagedProcessRunner.Result result = processRunner.run(new ManagedProcessRunner.Request(
                    List.of(psExe, "-NoProfile", "-NonInteractive", "-Command", escapedCommand),
                    Path.of(context.workingDirectory()), Duration.ofMillis(timeout),
                    context.currentRunId(), context.toolUseId()));
            if (result.cancelled()) return ToolResult.cancelled("PROCESS_CANCELLED", "PowerShell command cancelled",
                    ToolResult.EffectState.UNKNOWN).withMetadata("terminationConfirmed", result.terminationConfirmed());
            if (result.timedOut()) return ToolResult.timedOut("PROCESS_DEADLINE_EXCEEDED",
                    "PowerShell command timed out after " + timeout + "ms", 137, result.terminationConfirmed());
            int exitCode = result.exitCode();
            String output = result.stdout();
            Map<String, Object> metadata = new java.util.HashMap<>();
            metadata.put("exitCode", exitCode);
            metadata.put("psExecutable", psExe);
            if (!result.stderr().isEmpty()) metadata.put("stderr", result.stderr().trim());
            metadata.put("stdoutTruncated", result.stdoutTruncated());
            metadata.put("stderrTruncated", result.stderrTruncated());

            return ToolResult.process(output, exitCode, metadata);
        } catch (Exception e) {
            log.error("PowerShell execution failed: {}", e.getMessage(), e);
            return ToolResult.internalError("POWERSHELL_EXECUTION_FAILED",
                    "PowerShell error: " + e.getMessage(), ToolResult.EffectState.UNKNOWN);
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
