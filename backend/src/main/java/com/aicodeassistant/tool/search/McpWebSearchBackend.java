package com.aicodeassistant.tool.search;

import com.aicodeassistant.mcp.McpClientManager;
import com.aicodeassistant.mcp.McpConnectionStatus;
import com.aicodeassistant.mcp.McpServerConnection;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 基于 MCP 的 Web 搜索后端 — 对接阿里云智搜 (zhipu-websearch) MCP 服务。
 * <p>
 * 通过 McpClientManager 获取 zhipu-websearch 连接，调用 webSearchPro 工具。
 * 使用 JSON-RPC 2.0 协议通过 sendRequest/readResponse 与 MCP server 通信。
 * <p>
 * MCP 配置来源: configuration/mcp/mcp_capability_registry.json (mcp_web_search_pro)
 *
 * @see WebSearchBackend
 */
public class McpWebSearchBackend implements WebSearchBackend {

    private static final Logger log = LoggerFactory.getLogger(McpWebSearchBackend.class);

    private static final String MCP_SERVER_NAME = "zhipu-websearch";
    private static final String MCP_TOOL_NAME = "webSearchPro";
    private static final long TIMEOUT_MS = 30_000;

    private final McpClientManager mcpClientManager;
    private final ObjectMapper objectMapper;

    public McpWebSearchBackend(McpClientManager mcpClientManager) {
        this.mcpClientManager = mcpClientManager;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public List<SearchResult> search(String query, SearchOptions options) {
        Optional<McpServerConnection> connectionOpt =
                mcpClientManager.getConnection(MCP_SERVER_NAME);
        if (connectionOpt.isEmpty()) {
            log.warn("MCP server '{}' not connected, returning empty results", MCP_SERVER_NAME);
            return List.of();
        }

        McpServerConnection connection = connectionOpt.get();
        if (connection.getStatus() != McpConnectionStatus.CONNECTED) {
            log.warn("MCP server '{}' status is {}, returning empty results",
                    MCP_SERVER_NAME, connection.getStatus());
            return List.of();
        }

        try {
            // 构建 MCP 工具调用参数
            ObjectNode arguments = objectMapper.createObjectNode();
            arguments.put("search_query", query);

            // 可选参数映射
            if (options != null) {
                if (options.maxResults() > 0) {
                    arguments.put("count", Math.min(options.maxResults(), 50));
                }
                if (options.contentSize() != null && !options.contentSize().isEmpty()) {
                    arguments.put("content_size", options.contentSize());
                }
                if (options.domainFilter() != null && !options.domainFilter().isEmpty()) {
                    arguments.set("search_domain_filter",
                            objectMapper.valueToTree(options.domainFilter()));
                }
                if (options.recencyFilter() != null && !options.recencyFilter().isEmpty()) {
                    arguments.put("search_recency_filter", options.recencyFilter());
                }
            }

            // 通过统一传输接口调用 MCP 工具
            @SuppressWarnings("unchecked")
            Map<String, Object> args = objectMapper.convertValue(arguments, Map.class);
            JsonNode result = connection.callTool(MCP_TOOL_NAME, args, 30_000);
            if (result == null) {
                return List.of();
            }

            // MCP 标准: result.content 是数组，每项 type=text，text 字段含 JSON
            String textContent = extractTextContent(result);
            return parseResults(textContent);

        } catch (Exception e) {
            log.error("MCP web search failed: {}", e.getMessage(), e);
            return List.of();
        }
    }

    @Override
    public boolean isAvailable() {
        return mcpClientManager.getConnection(MCP_SERVER_NAME)
                .map(conn -> conn.getStatus() == McpConnectionStatus.CONNECTED)
                .orElse(false);
    }

    @Override
    public String name() {
        return "mcp-zhipu-websearch";
    }

    /**
     * 从 MCP result 中提取 text content。
     * MCP 标准返回格式: { "content": [{"type": "text", "text": "..."}] }
     */
    private String extractTextContent(JsonNode result) {
        if (result.has("content") && result.get("content").isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode item : result.get("content")) {
                if ("text".equals(item.path("type").asText())) {
                    sb.append(item.get("text").asText());
                }
            }
            return sb.toString();
        }
        // 回退: 直接使用 result 字符串
        return result.toString();
    }

    /**
     * 解析 webSearchPro 返回的搜索结果。
     * <p>
     * 返回格式:
     * [
     *   {"title": "...", "url": "...", "content": "...", "site_name": "...", "icon": "..."},
     *   ...
     * ]
     * 或包裹在 {"results": [...]} 中。
     */
    private List<SearchResult> parseResults(String rawResult) {
        try {
            JsonNode root = objectMapper.readTree(rawResult);
            JsonNode results = root.has("results") ? root.get("results") : root;

            if (!results.isArray()) {
                log.warn("MCP search result is not an array: {}",
                        rawResult.substring(0, Math.min(200, rawResult.length())));
                return List.of();
            }

            List<SearchResult> searchResults = new ArrayList<>();
            for (JsonNode item : results) {
                String title = item.has("title") ? item.get("title").asText() : "";
                String url = item.has("url") ? item.get("url").asText() : "";
                String content = item.has("content") ? item.get("content").asText() : "";
                String siteName = item.has("site_name") ? item.get("site_name").asText() : null;

                // SearchResult(title, url, snippet, content)
                // MCP 的 content 映射为 snippet（摘要），site_name 映射为 content（附加信息）
                searchResults.add(new SearchResult(title, url, content, siteName));
            }

            log.debug("MCP web search returned {} results", searchResults.size());
            return searchResults;
        } catch (Exception e) {
            log.error("Failed to parse MCP search results: {}", e.getMessage());
            return List.of();
        }
    }
}
