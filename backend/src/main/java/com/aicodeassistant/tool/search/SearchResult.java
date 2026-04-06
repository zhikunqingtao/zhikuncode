package com.aicodeassistant.tool.search;

/**
 * 搜索结果 — 统一数据格式。
 *
 * @param title   结果标题
 * @param url     结果 URL
 * @param snippet 简短摘要
 * @param content 详细内容（可选）
 */
public record SearchResult(String title, String url, String snippet, String content) {
}
