package com.aicodeassistant.tool.search;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 搜索后端工厂 — 根据配置自动选择最优后端。
 * <p>
 * P0 阶段: 默认返回 DisabledSearchBackend，
 * 后续 Round 实现 Brave/SerpAPI/Searxng 后端。
 *
 * @see <a href="SPEC §3.2.3b">WebSearchBackendFactory</a>
 */
@Component
public class WebSearchBackendFactory {

    @Value("${web-search.backend:auto}")
    private String backendConfig;

    @Value("${web-search.api-key:}")
    private String searchApiKey;

    @Value("${web-search.searxng-url:}")
    private String searxngUrl;

    /**
     * 创建搜索后端。
     * P0: 仅支持 disabled 和 auto(默认 disabled)。
     */
    public WebSearchBackend createBackend() {
        if ("disabled".equals(backendConfig)) {
            return new DisabledSearchBackend();
        }

        // auto 模式: 检查可用配置
        if ("auto".equals(backendConfig)) {
            // P0: 没有具体后端实现，返回 disabled
            // P1: 检查 API key 存在性，自动选择 Brave/SerpAPI/Searxng
            if (!searchApiKey.isEmpty()) {
                // TODO P1: return new ExternalWebSearchBackend(searchApiKey);
            }
            if (!searxngUrl.isEmpty()) {
                // TODO P1: return new SearxngWebSearchBackend(searxngUrl);
            }
            return new DisabledSearchBackend();
        }

        // 显式指定后端 — P0 统一返回 disabled
        return new DisabledSearchBackend();
    }
}
