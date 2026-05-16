package com.aicodeassistant.tool.search;

/**
 * 搜索匹配项 — 单条匹配结果。
 *
 * @param filePath  文件路径
 * @param relevance 相关性评分 (0-1)
 * @param source    匹配来源: "local" / "recent" / "git-changed" / "global"
 */
public record SearchMatch(
        String filePath,
        double relevance,
        String source
) {}
