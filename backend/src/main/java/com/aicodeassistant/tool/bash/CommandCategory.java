package com.aicodeassistant.tool.bash;

/**
 * UI 展示分类枚举 — 第四层分类，与安全分类正交。
 * <p>
 * 仅用于日志展示和 UI 标签，不参与安全决策。
 * 不影响 AST→正则→路径验证 三层安全架构。
 */
public enum CommandCategory {
    READ_ONLY,      // grep, find, cat, ls, head, tail, wc
    SEARCH,         // grep, find, rg, ag（搜索相关）
    MODIFICATION,   // rm, mkdir, touch, mv, cp, chmod
    SYSTEM_INFO,    // uname, pwd, whoami, env, hostname
    UNKNOWN;        // 无法分类的命令

    /**
     * 返回用于 UI/日志展示的简短标签。
     */
    public String getDisplayLabel() {
        return switch (this) {
            case READ_ONLY -> "read";
            case SEARCH -> "search";
            case MODIFICATION -> "write";
            case SYSTEM_INFO -> "info";
            case UNKNOWN -> "command";
        };
    }
}
