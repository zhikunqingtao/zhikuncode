package com.aicodeassistant.coordinator;

import com.aicodeassistant.tool.agent.SubAgentExecutor;
import org.springframework.stereotype.Component;

/**
 * 任务通知格式化器 — 将工人代理结果格式化为 Coordinator 可读的通知。
 * 对标原版 task-notification XML 格式。
 */
@Component
public class TaskNotificationFormatter {

    /**
     * 将子代理结果格式化为 task-notification XML。
     */
    public String formatNotification(String agentId, SubAgentExecutor.AgentResult result,
                                     long durationMs) {
        String status = result.status() != null ? result.status() : "completed";
        String summary = result.result() != null
                ? result.result().substring(0, Math.min(result.result().length(), 200))
                : "No output";
        String fullResult = result.result() != null ? result.result() : "";

        return """
                <task-notification>
                <task-id>%s</task-id>
                <status>%s</status>
                <summary>%s</summary>
                <result>%s</result>
                <usage>
                  <duration_ms>%d</duration_ms>
                </usage>
                </task-notification>
                """.formatted(agentId, status, escapeXml(summary),
                escapeXml(fullResult), durationMs);
    }

    private String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
