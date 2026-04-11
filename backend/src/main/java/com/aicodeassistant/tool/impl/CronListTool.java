package com.aicodeassistant.tool.impl;

import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.service.CronTaskService;
import com.aicodeassistant.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * CronListTool — 列出所有定时任务。
 *
 * @see <a href="§10.4 B2">ScheduleCronTool 设计</a>
 */
@Component
public class CronListTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CronListTool.class);

    private final CronTaskService cronTaskService;
    private final FeatureFlagService featureFlags;
    private final ObjectMapper objectMapper;

    public CronListTool(CronTaskService cronTaskService,
                        FeatureFlagService featureFlags,
                        ObjectMapper objectMapper) {
        this.cronTaskService = cronTaskService;
        this.featureFlags = featureFlags;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getName() { return "CronList"; }

    @Override
    public String getDescription() {
        return "List all scheduled cron tasks with their IDs, schedules, and status.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of()
        );
    }

    @Override
    public boolean isEnabled() {
        return featureFlags.isEnabled("AGENT_TRIGGERS");
    }

    @Override
    public boolean isReadOnly(ToolInput input) { return true; }

    @Override
    public boolean isConcurrencySafe(ToolInput input) { return true; }

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        try {
            List<CronTaskService.CronTask> tasks = cronTaskService.listAll();

            if (tasks.isEmpty()) {
                return ToolResult.success("No scheduled tasks.");
            }

            List<Map<String, Object>> taskList = new ArrayList<>();
            for (CronTaskService.CronTask task : tasks) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", task.id());
                item.put("cron", task.cron());
                item.put("prompt", task.prompt().length() > 60
                        ? task.prompt().substring(0, 60) + "..." : task.prompt());
                item.put("recurring", task.recurring());
                item.put("durable", task.durable());
                item.put("created_at", task.createdAt().toString());
                item.put("expires_at", task.expiresAt().toString());
                taskList.add(item);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("total", tasks.size());
            result.put("tasks", taskList);

            return ToolResult.success(objectMapper.writeValueAsString(result));
        } catch (Exception e) {
            log.error("Failed to list cron tasks: {}", e.getMessage(), e);
            return ToolResult.error("Failed to list cron tasks: " + e.getMessage());
        }
    }
}
