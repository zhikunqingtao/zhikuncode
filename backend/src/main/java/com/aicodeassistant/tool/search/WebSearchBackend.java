package com.aicodeassistant.tool.search;

import java.io.IOException;
import java.util.List;

/**
 * 搜索后端策略接口 — 解耦搜索实现与 LLM Provider。
 *
 */
public interface WebSearchBackend {

    /** 执行搜索并返回结果；后端调用失败时抛出 IOException（由上层转为 networkError） */
    List<SearchResult> search(String query, SearchOptions options) throws IOException;

    /** 当前后端是否可用 */
    boolean isAvailable();

    /** 后端名称（用于日志和计费标识）*/
    String name();
}
