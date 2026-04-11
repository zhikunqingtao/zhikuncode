package com.aicodeassistant.tool.impl;

import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.service.CronTaskService;
import com.aicodeassistant.tool.*;
import com.cronutils.model.Cron;
import com.cronutils.model.CronType;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.model.time.ExecutionTime;
import com.cronutils.parser.CronParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.*;

/**
 * CronCreateTool — 创建定时任务。
 *
 * <p>使用标准 5 字段 Unix cron 表达式（分 时 日 月 周）。
 * 通过 {@code feature('AGENT_TRIGGERS')} 门控。</p>
 *
 * @see <a href="§10.4 B2">ScheduleCronTool 设计</a>
 */
@Component
public class CronCreateTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CronCreateTool.class);

    private final CronTaskService cronTaskService;
    private final FeatureFlagService featureFlags;
    private final ObjectMapper objectMapper;
    private final CronParser cronParser;

    public CronCreateTool(CronTaskService cronTaskService,
                          FeatureFlagService featureFlags,
                          ObjectMapper objectMapper) {
        this.cronTaskService = cronTaskService;
        this.featureFlags = featureFlags;
        this.objectMapper = objectMapper;
        this.cronParser = new CronParser(
                CronDefinitionBuilder.instanceDefinitionFor(CronType.UNIX));
    }

    @Override
    public String getName() { return "CronCreate"; }

    @Override
    public String getDescription() {
        return "Create a scheduled cron task that triggers at specified intervals. "
             + "Uses standard 5-field Unix cron expressions (minute hour day-of-month month day-of-week). "
             + "Maximum 50 concurrent tasks. Tasks expire after 30 days.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "required", List.of("cron", "prompt"),
                "properties", Map.of(
                        "cron", Map.of(
                                "type", "string",
                                "description", "5-field Unix cron expression (e.g., '*/5 * * * *' for every 5 minutes)"),
                        "prompt", Map.of(
                                "type", "string",
                                "description", "The prompt/instruction to execute when triggered"),
                        "recurring", Map.of(
                                "type", "boolean",
                                "description", "Whether the task repeats (default: true)"),
                        "durable", Map.of(
                                "type", "boolean",
                                "description", "Whether the task survives restarts (default: false)")
                )
        );
    }

    @Override
    public boolean isEnabled() {
        return featureFlags.isEnabled("AGENT_TRIGGERS");
    }

    @Override
    public boolean shouldDefer() { return true; }

    @Override
    public ValidationResult validateInput(ToolInput input, ToolUseContext context) {
        String cron = input.getString("cron", null);
        if (cron == null || cron.isBlank()) {
            return ValidationResult.invalid("MISSING_CRON", "cron expression is required");
        }

        String prompt = input.getString("prompt", null);
        if (prompt == null || prompt.isBlank()) {
            return ValidationResult.invalid("MISSING_PROMPT", "prompt is required");
        }

        // 验证 cron 表达式
        try {
            Cron parsed = cronParser.parse(cron);
            parsed.validate();

            // 验证未来一年内有匹配
            ExecutionTime executionTime = ExecutionTime.forCron(parsed);
            Optional<ZonedDateTime> next = executionTime.nextExecution(ZonedDateTime.now());
            if (next.isEmpty()) {
                return ValidationResult.invalid("INVALID_CRON",
                        "Cron expression does not match any date in the next year");
            }
        } catch (IllegalArgumentException e) {
            return ValidationResult.invalid("INVALID_CRON",
                    "Invalid cron expression: " + e.getMessage());
        }

        // 检查 durable 兼容性
        Boolean durable = input.getBoolean("durable", false);
        if (durable && cronTaskService.taskCount() >= 50) {
            return ValidationResult.invalid("LIMIT_REACHED",
                    "Maximum number of scheduled tasks (50) reached");
        }

        return ValidationResult.ok();
    }

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        String cron = input.getString("cron");
        String prompt = input.getString("prompt");
        boolean recurring = input.getBoolean("recurring", true);
        boolean durable = input.getBoolean("durable", false);

        try {
            CronTaskService.CronTask task = cronTaskService.addTask(
                    cron, prompt, recurring, durable, context.sessionId());

            // 计算下次执行时间
            Cron parsed = cronParser.parse(cron);
            ExecutionTime executionTime = ExecutionTime.forCron(parsed);
            String nextRun = executionTime.nextExecution(ZonedDateTime.now())
                    .map(ZonedDateTime::toString)
                    .orElse("unknown");

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", task.id());
            result.put("cron", task.cron());
            result.put("prompt", task.prompt().length() > 80
                    ? task.prompt().substring(0, 80) + "..." : task.prompt());
            result.put("recurring", task.recurring());
            result.put("durable", task.durable());
            result.put("next_run", nextRun);
            result.put("expires_at", task.expiresAt().toString());
            result.put("total_tasks", cronTaskService.taskCount());

            return ToolResult.success(objectMapper.writeValueAsString(result));
        } catch (IllegalStateException e) {
            return ToolResult.error(e.getMessage());
        } catch (Exception e) {
            log.error("Failed to create cron task: {}", e.getMessage(), e);
            return ToolResult.error("Failed to create cron task: " + e.getMessage());
        }
    }
}
