package com.aicodeassistant.tool.search;

import java.util.List;

/**
 * 搜索作用域上下文 — 描述当前用户编辑环境。
 *
 * @param activeFilePath    当前活跃文件路径
 * @param recentFiles       最近编辑的文件列表
 * @param gitChangedFiles   Git 变更文件列表
 * @param workingDirectory  工作目录
 */
public record ScopeContext(
        String activeFilePath,
        List<String> recentFiles,
        List<String> gitChangedFiles,
        String workingDirectory
) {
    /** 最小构造 — 仅工作目录 */
    public static ScopeContext ofWorkingDirectory(String workingDirectory) {
        return new ScopeContext(null, List.of(), List.of(), workingDirectory);
    }
}
