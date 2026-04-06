package com.aicodeassistant.tool;

/**
 * 搜索/读取信息 — isSearchOrReadCommand 的返回类型。
 * 用于 UI 折叠展示和自动批准策略判断。
 *
 * @see <a href="SPEC §3.2.1">工具接口定义</a>
 */
public record SearchReadInfo(
        boolean isSearch,
        boolean isRead,
        boolean isList
) {
    public static final SearchReadInfo NONE = new SearchReadInfo(false, false, false);
}
