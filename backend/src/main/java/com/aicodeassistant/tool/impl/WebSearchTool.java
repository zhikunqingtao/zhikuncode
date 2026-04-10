package com.aicodeassistant.tool.impl;

import com.aicodeassistant.tool.*;
import com.aicodeassistant.tool.search.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * WebSearchTool — 执行网络搜索（策略模式 4 后端）。
 * <p>
 * 根据配置和当前 Provider 选择最优搜索后端:
 * - Anthropic: 使用原生 server_tool_use
 * - OpenAI 兼容: 使用外部搜索 API (SerpAPI/Brave/Searxng)
 * - 本地模型: 使用自托管 Searxng 或禁用
 *
 * @see <a href="SPEC §3.2.3">WebSearchTool 规范</a>
 */
@Component
public class WebSearchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WebSearchTool.class);
    private static final int DEFAULT_MAX_RESULTS = 8;

    private final WebSearchBackend searchBackend;

    public WebSearchTool(WebSearchBackendFactory factory) {
        this.searchBackend = factory.createBackend();
    }

    @Override
    public String getName() {
        return "WebSearch";
    }

    @Override
    public String getDescription() {
        return "Perform a web search to find relevant information on the internet.";
    }

    @Override
    public String prompt() {
        return """
                - Allows searching the web and using results to inform responses
                - Provides up-to-date information for current events and recent data
                - Returns search result information formatted as search result blocks, \
                including links as markdown hyperlinks
                - Use this tool for accessing information beyond the knowledge cutoff
                - Searches are performed automatically within a single API call
                
                CRITICAL REQUIREMENT - You MUST follow this:
                  - After answering the user's question, you MUST include a "Sources:" section \
                at the end of your response
                  - In the Sources section, list all relevant URLs from the search results as \
                markdown hyperlinks: [Title](URL)
                  - This is MANDATORY - never skip including sources in your response
                
                Usage notes:
                  - Domain filtering is supported to include or block specific websites
                """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string", "description", "Search query"),
                        "allowed_domains", Map.of("type", "array",
                                "items", Map.of("type", "string"),
                                "description", "Only include results from these domains"),
                        "blocked_domains", Map.of("type", "array",
                                "items", Map.of("type", "string"),
                                "description", "Exclude results from these domains")
                ),
                "required", List.of("query")
        );
    }

    @Override
    public boolean isReadOnly(ToolInput input) {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return searchBackend.isAvailable();
    }

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        String query = input.getString("query");
        List<String> allowedDomains = input.getOptionalList("allowed_domains", String.class)
                .orElse(List.of());
        List<String> blockedDomains = input.getOptionalList("blocked_domains", String.class)
                .orElse(List.of());

        // 1. 输入验证
        if (!allowedDomains.isEmpty() && !blockedDomains.isEmpty()) {
            return ToolResult.error(
                    "allowed_domains and blocked_domains cannot both be specified.");
        }

        // 2. 检查后端可用性
        if (!searchBackend.isAvailable()) {
            return ToolResult.error(
                    "Web search is not available. Configure a search backend via "
                            + "web-search.backend property or set BRAVE_API_KEY / SERPAPI_KEY.");
        }

        // 3. 构建搜索选项
        SearchOptions options = new SearchOptions(
                allowedDomains, blockedDomains,
                DEFAULT_MAX_RESULTS, Locale.getDefault().toLanguageTag());

        // 4. 执行搜索
        try {
            long startTime = System.nanoTime();
            List<SearchResult> results = searchBackend.search(query, options);
            double durationSec = (System.nanoTime() - startTime) / 1_000_000_000.0;

            if (results.isEmpty()) {
                return ToolResult.success(String.format(
                        "Web search for \"%s\" returned no results (%.1fs).\n"
                                + "Try rephrasing the query or using different keywords.",
                        query, durationSec));
            }

            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Web search results for: \"%s\" (%d results, %.1fs)\n\n",
                    query, results.size(), durationSec));

            for (int i = 0; i < results.size(); i++) {
                SearchResult r = results.get(i);
                sb.append(String.format("%d. **%s**\n", i + 1, r.title()));
                sb.append(String.format("   URL: %s\n", r.url()));
                if (r.snippet() != null && !r.snippet().isBlank()) {
                    sb.append(String.format("   %s\n", r.snippet()));
                }
                sb.append("\n");
            }

            sb.append("REMINDER: Include sources in your response using markdown hyperlinks.");
            return ToolResult.success(sb.toString());

        } catch (Exception e) {
            log.warn("Web search failed for query '{}': {}", query, e.getMessage());
            return ToolResult.error("Web search failed: " + e.getMessage());
        }
    }
}
