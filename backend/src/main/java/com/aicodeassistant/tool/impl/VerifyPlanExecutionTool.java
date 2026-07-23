package com.aicodeassistant.tool.impl;

import com.aicodeassistant.tool.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class VerifyPlanExecutionTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(VerifyPlanExecutionTool.class);

    private static final int COMMAND_TIMEOUT_SECONDS = 60;
    private static final int MAX_OUTPUT_LENGTH = 10_000;

    /** 结构化验证结果 */
    private record VerificationOutcome(
        boolean passed,
        String failureCode,
        String message,
        Integer exitCode,
        boolean timedOut,
        Map<String, Object> metadata
    ) {}

    @Override public String getName() { return "VerifyPlanExecution"; }
    @Override public String getDescription() {
        return "验证计划模式下的任务执行结果是否符合预期";
    }
    @Override public String getGroup() { return "plan"; }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "required", List.of("plan_id", "task_id"),
            "properties", Map.of(
                "plan_id", Map.of("type", "string", "description", "计划 ID"),
                "task_id", Map.of("type", "string", "description", "任务 ID"),
                "expected_outcome", Map.of("type", "string", "description", "预期结果"),
                "verification_type", Map.of("type", "string",
                    "description", "file_exists|content_matches|test_passes|command_succeeds"),
                "file_path", Map.of("type", "string", "description", "文件路径（content_matches 时使用）"),
                "command", Map.of("type", "string", "description", "要执行的命令（test_passes/command_succeeds 时使用）")
            )
        );
    }

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        String planId = input.getString("plan_id");
        String taskId = input.getString("task_id");
        String type = input.getString("verification_type", "command_succeeds");

        VerificationOutcome outcome = switch (type) {
            case "file_exists" -> verifyFileExists(input);
            case "content_matches" -> verifyContentMatch(input);
            case "test_passes" -> verifyCommandExecution(input, context);
            case "command_succeeds" -> verifyCommandExecution(input, context);
            default -> new VerificationOutcome(false, "PLAN_VERIFICATION_TYPE_UNSUPPORTED",
                "Unknown verification type: " + type, null, false, Map.of());
        };

        if (outcome.passed()) {
            String msg = outcome.message() != null && !outcome.message().isBlank()
                ? String.format("计划[%s] 任务[%s] 验证通过\n%s", planId, taskId, outcome.message())
                : String.format("计划[%s] 任务[%s] 验证通过", planId, taskId);
            return ToolResult.success(msg);
        } else if (outcome.timedOut()) {
            return ToolResult.timedOut(outcome.failureCode(),
                String.format("计划[%s] 任务[%s] 验证超时: %s", planId, taskId, outcome.message()),
                outcome.exitCode(), true);
        } else {
            return ToolResult.failed(
                ToolResult.ToolFailureType.PROCESS,
                outcome.failureCode(),
                String.format("计划[%s] 任务[%s] 验证失败: %s", planId, taskId, outcome.message()),
                ToolResult.Retryability.NEVER,
                ToolResult.EffectState.NONE,
                outcome.exitCode(),
                outcome.metadata()
            );
        }
    }

    // ──── 验证方法 ────

    private VerificationOutcome verifyFileExists(ToolInput input) {
        String filePath = input.getString("expected_outcome", "");
        if (filePath.isBlank()) {
            return new VerificationOutcome(false, "PLAN_FILE_MISSING",
                "File path not specified", null, false, Map.of());
        }
        if (Files.exists(Path.of(filePath))) {
            return new VerificationOutcome(true, null, null, null, false, Map.of());
        } else {
            return new VerificationOutcome(false, "PLAN_FILE_MISSING",
                "File does not exist: " + filePath, null, false, Map.of());
        }
    }

    private VerificationOutcome verifyContentMatch(ToolInput input) {
        String expectedOutcome = input.getString("expected_outcome", "");
        String filePath = input.getString("file_path", "");
        if (filePath.isBlank()) {
            return new VerificationOutcome(false, "PLAN_FILE_MISSING",
                "File path not specified", null, false, Map.of());
        }
        try {
            String content = Files.readString(Path.of(filePath));
            if (content.contains(expectedOutcome)) {
                return new VerificationOutcome(true, null, null, null, false, Map.of());
            } else {
                return new VerificationOutcome(false, "PLAN_CONTENT_MISMATCH",
                    "Content does not match expected outcome", null, false, Map.of());
            }
        } catch (IOException e) {
            return new VerificationOutcome(false, "PLAN_VERIFICATION_EXECUTION_ERROR",
                "Cannot read file: " + e.getMessage(), null, false, Map.of());
        }
    }

    private VerificationOutcome verifyCommandExecution(ToolInput input, ToolUseContext ctx) {
        // 优先从 "command" 字段读取，回退到 "expected_outcome"
        String command = input.getString("command", "");
        if (command.isBlank()) {
            command = input.getString("expected_outcome", "");
        }
        if (command.isBlank()) {
            return new VerificationOutcome(false, "PLAN_VERIFICATION_FAILED",
                "No command specified", null, false, Map.of());
        }

        // 解析命令为 argv 列表（拒绝管道/重定向/多命令等复杂 shell 结构）
        List<String> argv = parseSimpleCommand(command);
        if (argv == null || argv.isEmpty()) {
            return new VerificationOutcome(false, "PLAN_VERIFICATION_COMMAND_REJECTED",
                "Command contains unsupported shell features (pipes, redirects, semicolons, etc.): " + command,
                null, false, Map.of());
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(argv);
            pb.redirectErrorStream(true);
            pb.directory(new File(ctx.workingDirectory()));

            Process process = pb.start();

            // 使用虚拟线程排空输出防止管道死锁
            final Process proc = process;
            var outputFuture = Thread.ofVirtual().start(() -> {
                // 输出在主线程通过 readAllBytes 读取
            });

            String output;
            try {
                byte[] outputBytes = process.getInputStream().readAllBytes();
                output = new String(outputBytes, StandardCharsets.UTF_8);
            } catch (IOException e) {
                output = "Failed to read process output: " + e.getMessage();
            }

            if (output.length() > MAX_OUTPUT_LENGTH) {
                output = output.substring(0, MAX_OUTPUT_LENGTH) + "\n... [truncated]";
            }

            boolean finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new VerificationOutcome(false, "PLAN_VERIFICATION_TIMEOUT",
                    "Command timed out after " + COMMAND_TIMEOUT_SECONDS + "s", null, true, Map.of());
            }

            int exitCode = process.exitValue();
            if (exitCode == 0) {
                return new VerificationOutcome(true, null, output, exitCode, false, Map.of());
            } else {
                return new VerificationOutcome(false, "PLAN_VERIFICATION_FAILED",
                    "Command exited with code " + exitCode + ":\n" + output, exitCode, false, Map.of());
            }
        } catch (Exception e) {
            log.warn("VerifyPlanExecution command execution error: {}", e.getMessage());
            return new VerificationOutcome(false, "PLAN_VERIFICATION_EXECUTION_ERROR",
                "Execution error: " + e.getMessage(), null, false, Map.of());
        }
    }

    // ──── Shell 简单命令解析器 ────

    /**
     * 将命令字符串解析为 argv 列表。
     * 支持：空格分割、单引号/双引号。
     * 拒绝（返回 null）：管道 |, 分号 ;, &&, ||, 反引号 `, $(), >, <, >>, 2>
     */
    static List<String> parseSimpleCommand(String command) {
        if (command == null || command.isBlank()) return null;

        // 快速检测：若含有不安全 shell 元字符，直接拒绝
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == '|' || c == ';' || c == '`' || c == '<' || c == '>') {
                return null;
            }
        }
        // 检测 $( 子命令替换
        if (command.contains("$(")) return null;
        // 检测 && 和 ||
        if (command.contains("&&") || command.contains("||")) return null;

        List<String> argv = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingle = false;
        boolean inDouble = false;

        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);

            if (inSingle) {
                if (c == '\'') {
                    inSingle = false;
                } else {
                    current.append(c);
                }
            } else if (inDouble) {
                if (c == '"') {
                    inDouble = false;
                } else if (c == '\\' && i + 1 < command.length()) {
                    char next = command.charAt(i + 1);
                    if (next == '"' || next == '\\' || next == '$' || next == '`') {
                        current.append(next);
                        i++;
                    } else {
                        current.append(c);
                    }
                } else {
                    current.append(c);
                }
            } else {
                if (c == '\'') {
                    inSingle = true;
                } else if (c == '"') {
                    inDouble = true;
                } else if (c == '\\' && i + 1 < command.length()) {
                    current.append(command.charAt(i + 1));
                    i++;
                } else if (Character.isWhitespace(c)) {
                    if (!current.isEmpty()) {
                        argv.add(current.toString());
                        current.setLength(0);
                    }
                } else {
                    current.append(c);
                }
            }
        }

        // 未闭合的引号 → 拒绝
        if (inSingle || inDouble) return null;

        if (!current.isEmpty()) {
            argv.add(current.toString());
        }

        return argv.isEmpty() ? null : argv;
    }
}
