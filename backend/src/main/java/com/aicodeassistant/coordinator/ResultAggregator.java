package com.aicodeassistant.coordinator;

import com.aicodeassistant.tool.agent.SubAgentExecutor.AgentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Worker 结果聚合器 — 将多个 Worker Agent 的结果合并为统一摘要。
 * <p>
 * 在 Coordinator 模式下，多个 Worker 完成后需要聚合结果
 * 以便 Coordinator 做出下一步决策。
 *
 * @see <a href="SPEC §11">Coordinator 深化</a>
 */
@Component
public class ResultAggregator {

    private static final Logger log = LoggerFactory.getLogger(ResultAggregator.class);

    /** 单个结果的最大截取长度 */
    private static final int MAX_RESULT_CHARS = 50_000;

    /** 聚合摘要的最大总长度 */
    private static final int MAX_AGGREGATE_CHARS = 200_000;

    /**
     * 聚合多个 Worker 结果为结构化摘要。
     *
     * @param teamName 团队名称
     * @param results  Worker 执行结果列表
     * @return 格式化的聚合摘要
     */
    public String aggregate(String teamName, List<AgentResult> results) {
        if (results == null || results.isEmpty()) {
            return "No results to aggregate for team: " + teamName;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("## Team Results: ").append(teamName).append("\n\n");

        int successCount = 0;
        int failCount = 0;

        for (int i = 0; i < results.size(); i++) {
            AgentResult result = results.get(i);
            String status = result.result() != null && !result.result().startsWith("Agent execution failed")
                    && !result.result().startsWith("Worker") ? "SUCCESS" : "PARTIAL";
            if ("SUCCESS".equals(status)) successCount++;
            else failCount++;

            sb.append("### Worker ").append(i + 1).append(" [").append(status).append("]\n");
            if (result.prompt() != null) {
                sb.append("**Task**: ").append(truncate(result.prompt(), 200)).append("\n");
            }
            sb.append("**Result**:\n");
            String content = result.result() != null ? result.result() : "(no output)";
            sb.append(truncate(content, MAX_RESULT_CHARS)).append("\n\n");
        }

        sb.append("---\n");
        sb.append("**Summary**: ").append(results.size()).append(" workers completed")
                .append(" (").append(successCount).append(" success, ")
                .append(failCount).append(" partial/failed)\n");

        String aggregated = sb.toString();
        if (aggregated.length() > MAX_AGGREGATE_CHARS) {
            aggregated = aggregated.substring(0, MAX_AGGREGATE_CHARS) + "\n...[aggregation truncated]";
        }

        log.info("Aggregated {} results for team '{}' ({} chars)",
                results.size(), teamName, aggregated.length());
        return aggregated;
    }

    /**
     * 提取所有结果的关键输出（适用于简洁摘要场景）。
     */
    public String extractKeyFindings(List<AgentResult> results) {
        return results.stream()
                .filter(r -> r.result() != null && !r.result().isBlank())
                .map(r -> "- " + truncate(firstLine(r.result()), 200))
                .collect(Collectors.joining("\n"));
    }

    // ── 辅助方法 ──────────────────────────────────────────────

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...[truncated]";
    }

    private String firstLine(String text) {
        if (text == null) return "";
        int idx = text.indexOf('\n');
        return idx > 0 ? text.substring(0, idx) : text;
    }
}
