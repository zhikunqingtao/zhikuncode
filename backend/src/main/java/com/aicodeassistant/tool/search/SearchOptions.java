package com.aicodeassistant.tool.search;

import java.util.List;

/**
 * 搜索选项 — 控制搜索行为。
 *
 * @param allowedDomains 仅包含这些域名的结果
 * @param blockedDomains 排除这些域名的结果
 * @param maxResults     最大结果数
 * @param locale         搜索区域设置
 */
public record SearchOptions(
        List<String> allowedDomains,
        List<String> blockedDomains,
        int maxResults,
        String locale
) {
    public static final int DEFAULT_MAX_RESULTS = 8;
}
