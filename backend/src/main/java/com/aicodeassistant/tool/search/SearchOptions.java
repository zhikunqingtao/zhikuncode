package com.aicodeassistant.tool.search;

import java.util.List;

/**
 * 搜索选项 — 控制搜索行为。
 * <p>
 * 基础字段（原有）:
 * @param allowedDomains 仅包含这些域名的结果
 * @param blockedDomains 排除这些域名的结果
 * @param maxResults     最大结果数 (1-50, 默认8)
 * @param locale         搜索区域设置
 * <p>
 * MCP 增强字段:
 * @param contentSize    MCP 摘要详细度: "medium"(400-600字,默认) | "high"(2500字)
 * @param domainFilter   MCP 域名白名单 (search_domain_filter)
 * @param recencyFilter  MCP 时间过滤: oneDay/oneWeek/oneMonth/oneYear/noLimit
 */
public record SearchOptions(
        List<String> allowedDomains,
        List<String> blockedDomains,
        int maxResults,
        String locale,
        String contentSize,
        List<String> domainFilter,
        String recencyFilter
) {
    public static final int DEFAULT_MAX_RESULTS = 8;

    /** 向后兼容的 4 参数构造器（原有调用点无需修改） */
    public SearchOptions(List<String> allowedDomains, List<String> blockedDomains,
                         int maxResults, String locale) {
        this(allowedDomains, blockedDomains, maxResults, locale, null, null, null);
    }

    /** 仅指定结果数量的便捷构造 */
    public SearchOptions(int maxResults) {
        this(null, null, maxResults, null, null, null, null);
    }

    /** 默认配置：8条结果，medium 摘要，无过滤 */
    public static SearchOptions defaults() {
        return new SearchOptions(null, null, DEFAULT_MAX_RESULTS, null, "medium", null, null);
    }
}
