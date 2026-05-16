package com.aicodeassistant.tool.search;

import java.util.List;

/**
 * 作用域搜索结果 — 包含匹配列表、使用的策略及总计数。
 *
 * @param matches      匹配项列表（按相关性降序）
 * @param usedStrategy 实际使用的搜索策略
 * @param totalCount   匹配总数
 */
public record ScopedSearchResult(
        List<SearchMatch> matches,
        SearchStrategyRouter.Strategy usedStrategy,
        int totalCount
) {}
