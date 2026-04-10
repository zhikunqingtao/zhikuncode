package com.aicodeassistant.tool.interaction;

import com.aicodeassistant.tool.*;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * SleepTool — 暂停执行指定时间。
 * <p>
 * 用于等待外部进程完成或限速场景。支持中断信号提前唤醒。
 * 最大 300 秒（5 分钟）。
 *
 * @see <a href="SPEC §4.1.12">SleepTool</a>
 */
@Component
public class SleepTool implements Tool {

    private static final int MAX_SLEEP_SECONDS = 300;

    @Override
    public String getName() {
        return "Sleep";
    }

    @Override
    public String getDescription() {
        return "Pause execution for a specified number of seconds (1-300). " +
                "Useful for waiting on external processes or rate limiting.";
    }

    @Override
    public String prompt() {
        return """
                Wait for a specified duration. The user can interrupt the sleep at any time.
                
                Use this when the user tells you to sleep or rest, when you have nothing to \
                do, or when you're waiting for something.
                
                You can call this concurrently with other tools \u2014 it won't interfere with them.
                
                Prefer this over `Bash(sleep ...)` \u2014 it doesn't hold a shell process.
                
                Each wake-up costs an API call, but the prompt cache expires after 5 minutes \
                of inactivity \u2014 balance accordingly.
                """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "seconds", Map.of(
                                "type", "integer",
                                "description", "Number of seconds to sleep (1-300)",
                                "minimum", 1,
                                "maximum", MAX_SLEEP_SECONDS)
                ),
                "required", List.of("seconds")
        );
    }

    @Override
    public String getGroup() {
        return "interaction";
    }

    @Override
    public PermissionRequirement getPermissionRequirement() {
        return PermissionRequirement.NONE;
    }

    @Override
    public boolean isReadOnly(ToolInput input) {
        return true;
    }

    @Override
    public boolean isConcurrencySafe(ToolInput input) {
        return true;
    }

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        int seconds = input.getInt("seconds");

        // 1. 上限校验
        if (seconds < 1 || seconds > MAX_SLEEP_SECONDS) {
            return ToolResult.error(
                    "seconds must be between 1 and " + MAX_SLEEP_SECONDS + ", got: " + seconds);
        }

        // 2. 执行休眠（支持中断信号提前唤醒）
        try {
            Thread.sleep(Duration.ofSeconds(seconds));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.success("Sleep interrupted after partial wait.");
        }

        return ToolResult.success("Slept for " + seconds + " seconds.");
    }
}
