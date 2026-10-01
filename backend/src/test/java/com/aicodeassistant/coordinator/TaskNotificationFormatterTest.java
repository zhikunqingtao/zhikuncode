package com.aicodeassistant.coordinator;

import com.aicodeassistant.tool.agent.SubAgentExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TaskNotificationFormatter 的最小契约测试：
 * 转义 status/agentId，summary 截断 200 字，usage 仅 duration_ms，直接格式化结果含 result。
 * 后台送达路径还会包装和截断，不能由这些直接调用测试推导其 XML 完整性。
 */
class TaskNotificationFormatterTest {

    private final TaskNotificationFormatter formatter = new TaskNotificationFormatter();

    private static SubAgentExecutor.AgentResult result(String status, String text) {
        return new SubAgentExecutor.AgentResult(status, text, "prompt", null);
    }

    @Test
    @DisplayName("status 与 agentId 中的 XML 特殊字符会被转义")
    void escapesStatusAndAgentId() {
        String xml = formatter.formatNotification("agent-<&>", result("failed<&>", "ok"), 10L);

        assertTrue(xml.contains("<task-id>agent-&lt;&amp;&gt;</task-id>"));
        assertTrue(xml.contains("<status>failed&lt;&amp;&gt;</status>"));
        assertFalse(xml.contains("<status>failed<"));
    }

    @Test
    @DisplayName("summary 截断为结果前 200 字，usage 仅含 duration_ms，result 恒存在")
    void summaryTruncatesTo200CharsAndUsageOnlyHasDuration() {
        String longText = "x".repeat(250);
        String xml = formatter.formatNotification("agent-1", result("completed", longText), 1234L);

        assertTrue(xml.contains("<summary>" + "x".repeat(200) + "</summary>"));
        assertFalse(xml.contains("<summary>" + "x".repeat(201)));
        assertTrue(xml.contains("<duration_ms>1234</duration_ms>"));
        assertFalse(xml.contains("total_tokens"));
        assertFalse(xml.contains("tool_uses"));
        assertTrue(xml.contains("<result>" + longText + "</result>"));
    }

    @Test
    @DisplayName("直接格式化 null 结果时 summary 为 No output，status 缺省为 completed")
    void resultAlwaysPresentAndStatusDefaultsToCompleted() {
        String xml = formatter.formatNotification("agent-2", result(null, null), 7L);

        assertTrue(xml.contains("<status>completed</status>"));
        assertTrue(xml.contains("<summary>No output</summary>"));
        assertTrue(xml.contains("<result></result>"));
    }

    @Test
    @DisplayName("空字符串结果保留空 summary，与 null 结果的 No output 区分")
    void emptyResultKeepsEmptySummary() {
        String xml = formatter.formatNotification("agent-empty", result("completed", ""), 0L);

        assertTrue(xml.contains("<summary></summary>"));
        assertTrue(xml.contains("<result></result>"));
        assertFalse(xml.contains("No output"));
    }

    @Test
    @DisplayName("result 正文中的 XML 特殊字符会被转义")
    void escapesResultBody() {
        String xml = formatter.formatNotification("agent-3", result("completed", "<b>&</b>"), 1L);

        assertTrue(xml.contains("<result>&lt;b&gt;&amp;&lt;/b&gt;</result>"));
    }
}
