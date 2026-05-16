package com.aicodeassistant.tool.search;

/**
 * 搜索查询 — 封装搜索请求参数。
 *
 * @param pattern     搜索文本
 * @param filePattern 文件名 glob 模式（可选）
 * @param type        搜索类型
 */
public record SearchQuery(
        String pattern,
        String filePattern,
        SearchType type
) {
    public enum SearchType { CONTENT, FILENAME, SYMBOL }

    /** 便捷构造 — 仅指定搜索文本，默认为 CONTENT 类型 */
    public static SearchQuery ofContent(String pattern) {
        return new SearchQuery(pattern, null, SearchType.CONTENT);
    }

    /** 便捷构造 — 文件名搜索 */
    public static SearchQuery ofFilename(String pattern) {
        return new SearchQuery(pattern, null, SearchType.FILENAME);
    }
}
