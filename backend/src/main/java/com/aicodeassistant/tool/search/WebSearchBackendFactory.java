package com.aicodeassistant.tool.search;

import com.aicodeassistant.mcp.McpClientManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 搜索后端工厂 — 根据配置自动选择最优后端。
 * <p>
 * 优先级: MCP搜索 > API密钥搜索 > Searxng > Disabled
 *
 * @see <a href="SPEC §3.2.3b">WebSearchBackendFactory</a>
 */
@Component
public class WebSearchBackendFactory {

    private static final Logger log = LoggerFactory.getLogger(WebSearchBackendFactory.class);

    @Value("${web-search.backend:auto}")
    private String backendConfig;

    @Value("${web-search.api-key:}")
    private String searchApiKey;

    @Value("${web-search.searxng-url:}")
    private String searxngUrl;

    private final McpClientManager mcpClientManager;

    public WebSearchBackendFactory(McpClientManager mcpClientManager) {
        this.mcpClientManager = mcpClientManager;
    }

    /**
     * 创建搜索后端。
     * 自动模式下按优先级检测: MCP → API Key → Searxng → Disabled
     */
    public WebSearchBackend createBackend() {
        if ("disabled".equals(backendConfig)) {
            return new DisabledSearchBackend();
        }

        // 显式指定 MCP 后端
        if ("mcp".equals(backendConfig)) {
            McpWebSearchBackend mcpBackend = new McpWebSearchBackend(mcpClientManager);
            if (mcpBackend.isAvailable()) {
                log.info("Web search backend: MCP (zhipu-websearch) — explicitly configured");
                return mcpBackend;
            }
            log.warn("MCP web search backend explicitly configured but not available, falling back to disabled");
            return new DisabledSearchBackend();
        }

        // auto 模式: 按优先级检测
        if ("auto".equals(backendConfig)) {
            // 优先级 1: MCP 搜索后端
            McpWebSearchBackend mcpBackend = new McpWebSearchBackend(mcpClientManager);
            if (mcpBackend.isAvailable()) {
                log.info("Web search backend: MCP (zhipu-websearch) — auto-detected");
                return mcpBackend;
            }

            // 优先级 2: API Key 搜索后端
            if (searchApiKey != null && !searchApiKey.isEmpty()) {
                // TODO: return new ExternalWebSearchBackend(searchApiKey);
                log.info("Web search API key configured but external backend not yet implemented");
            }

            // 优先级 3: Searxng 自托管搜索
            if (searxngUrl != null && !searxngUrl.isEmpty()) {
                // TODO: return new SearxngWebSearchBackend(searxngUrl);
                log.info("Searxng URL configured but backend not yet implemented");
            }

            log.info("Web search backend: disabled (no available backend detected)");
            return new DisabledSearchBackend();
        }

        // 未知配置 — 返回 disabled
        log.warn("Unknown web-search.backend config: '{}', using disabled", backendConfig);
        return new DisabledSearchBackend();
    }
}
