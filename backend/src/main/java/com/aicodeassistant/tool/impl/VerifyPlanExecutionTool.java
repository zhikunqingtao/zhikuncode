package com.aicodeassistant.tool.impl;

import com.aicodeassistant.tool.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class VerifyPlanExecutionTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(VerifyPlanExecutionTool.class);

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
                    "description", "file_exists|content_matches|test_passes|command_succeeds")
            )
        );
    }

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        String planId = input.getString("plan_id");
        String taskId = input.getString("task_id");
        String type = input.getString("verification_type", "command_succeeds");
        boolean passed = switch (type) {
            case "file_exists" -> Files.exists(
                    Path.of(input.getString("expected_outcome", "")));
            case "content_matches" -> verifyContentMatch(input, context);
            case "test_passes" -> verifyTestPasses(input, context);
            case "command_succeeds" -> verifyCommandSucceeds(input, context);
            default -> false;
        };
        return ToolResult.success(String.format("计划[%s] 任务[%s] 验证%s",
                planId, taskId, passed ? "✅ 通过" : "❌ 未通过"));
    }

    private boolean verifyContentMatch(ToolInput input, ToolUseContext ctx) {
        String expectedOutcome = input.getString("expected_outcome", "");
        String filePath = input.getString("file_path", null);
        if (filePath == null || expectedOutcome.isEmpty()) return false;
        try {
            String content = Files.readString(Path.of(filePath));
            return content.contains(expectedOutcome);
        } catch (IOException e) {
            return false;
        }
    }

    private static final Set<String> ALLOWED_COMMAND_PREFIXES = Set.of(
            "mvn ", "./mvnw ", "gradle ", "./gradlew ",
            "npm test", "npm run test", "npx jest", "npx vitest",
            "pytest", "python -m pytest", "python3 -m pytest",
            "cargo test", "go test",
            "ls ", "cat ", "head ", "tail ", "wc ", "grep ",
            "find ", "test ", "[ "
    );

    private boolean isCommandAllowed(String command) {
        if (command == null || command.isBlank()) return false;
        String trimmed = command.trim();
        if (trimmed.matches("(?i).*(rm\\s+-rf|sudo|eval|exec|mkfs|dd\\s+|chmod\\s+777|>\\s*/dev/).*"))
            return false;
        for (String prefix : ALLOWED_COMMAND_PREFIXES) {
            if (trimmed.startsWith(prefix)) return true;
        }
        return false;
    }

    private boolean verifyTestPasses(ToolInput input, ToolUseContext ctx) {
        String testCommand = input.getString("expected_outcome", "");
        if (testCommand.isEmpty()) return false;
        if (!isCommandAllowed(testCommand)) {
            log.warn("verifyTestPasses 拒绝执行非白名单命令: {}", testCommand);
            return false;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", testCommand)
                    .directory(new File(ctx.workingDirectory()))
                    .redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) process.destroyForcibly();
            return finished && process.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean verifyCommandSucceeds(ToolInput input, ToolUseContext ctx) {
        return verifyTestPasses(input, ctx);
    }
}
