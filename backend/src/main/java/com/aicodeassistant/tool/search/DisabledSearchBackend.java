package com.aicodeassistant.tool.search;

import java.util.List;

/**
 * 禁用搜索后端 — 当无可用搜索配置时使用。
 */
public class DisabledSearchBackend implements WebSearchBackend {

    @Override
    public List<SearchResult> search(String query, SearchOptions options) {
        throw new RuntimeException(
                "Web search is not available. Configure web-search.backend in application.yml");
    }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public String name() {
        return "disabled";
    }
}
