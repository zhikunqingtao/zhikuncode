package com.aicodeassistant.tool.impl;

import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.service.CronTaskService;
import com.aicodeassistant.tool.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CronDeleteTool — 删除定时任务。
 *
 * @see <a href="§10.4 B2">ScheduleCronTool 设计</a>
 */
@Component
public class CronDeleteTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CronDeleteTool.class);

    private final CronTaskService cronTaskService;
    private final FeatureFlagService featureFlags;

    public CronDeleteTool(CronTaskService cronTaskService,
                          FeatureFlagService featureFlags) {
        this.cronTaskService = cronTaskService;
        this.featureFlags = featureFlags;
    }

    @Override
    public String getName() { return "CronDelete"; }

    @Override
    public String getDescription() {
        return "Delete a scheduled cron task by its ID.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "required", List.of("id"),
                "properties", Map.of(
                        "id", Map.of(
                                "type", "string",
                                "description", "The ID of the cron task to delete")
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
        String id = input.getString("id", null);
        if (id == null || id.isBlank()) {
            return ValidationResult.invalid("MISSING_ID", "Task id is required");
        }
        if (cronTaskService.getTask(id).isEmpty()) {
            return ValidationResult.invalid("NOT_FOUND",
                    "No scheduled task found with id: " + id);
        }
        return ValidationResult.ok();
    }

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        String id = input.getString("id");

        Optional<CronTaskService.CronTask> removed = cronTaskService.remove(id);
        if (removed.isPresent()) {
            CronTaskService.CronTask task = removed.get();
            return ToolResult.success("Deleted scheduled task: id=" + task.id()
                    + ", cron='" + task.cron() + "'"
                    + ", remaining=" + cronTaskService.taskCount());
        } else {
            return ToolResult.error("No scheduled task found with id: " + id);
        }
    }
}
