package com.aicodeassistant.tool.search;

import java.util.List;

/**
 * 搜索后端策略接口 — 解耦搜索实现与 LLM Provider。
 *
 * @see <a href="SPEC §3.2.3">WebSearchTool 完整实现</a>
 */
public interface WebSearchBackend {

    /** 执行搜索并返回结果 */
    List<SearchResult> search(String query, SearchOptions options);

    /** 当前后端是否可用 */
    boolean isAvailable();

    /** 后端名称（用于日志和计费标识）*/
    String name();
}
