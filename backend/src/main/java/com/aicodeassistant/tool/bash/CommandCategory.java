package com.aicodeassistant.tool.bash;

/**
 * 命令分类枚举 — 同时用于 UI 展示和动态超时推荐。
 * <p>
 * 原始五个分类（READ_ONLY, SEARCH, MODIFICATION, SYSTEM_INFO, UNKNOWN）保持向后兼容，
 * 新增细粒度分类用于超时策略：COMPILATION, TEST_EXECUTION, PACKAGE_INSTALL, GIT_OPERATION, SERVER_START。
 * <p>
 * 不影响 AST→正则→路径验证 三层安全架构。
 */
public enum CommandCategory {
    READ_ONLY("read", 30_000L),              // grep, cat, ls, head, tail, wc → 30s
    SEARCH("search", 60_000L),               // find, rg, ag → 60s
    MODIFICATION("write", 120_000L),         // rm, mkdir, touch → 120s
    SYSTEM_INFO("info", 30_000L),            // uname, pwd, whoami → 30s
    COMPILATION("compile", 300_000L),        // mvn compile, npm run build, cargo build → 300s (5min)
    TEST_EXECUTION("test", 600_000L),        // mvn test, pytest, npm test → 600s (10min)
    PACKAGE_INSTALL("install", 300_000L),    // npm install, pip install, mvn dependency → 300s
    GIT_OPERATION("git", 60_000L),           // git status, git diff, git log → 60s
    SERVER_START("server", 120_000L),        // npm start, java -jar → 120s
    UNKNOWN("command", 120_000L);            // 默认 → 120s

    private final String displayLabel;
    private final long recommendedTimeoutMs;

    CommandCategory(String displayLabel, long recommendedTimeoutMs) {
        this.displayLabel = displayLabel;
        this.recommendedTimeoutMs = recommendedTimeoutMs;
    }

    /**
     * 返回用于 UI/日志展示的简短标签。
     */
    public String getDisplayLabel() {
        return displayLabel;
    }

    /**
     * 返回该类型命令的推荐超时时间（毫秒）。
     * 用于 BashTool 动态超时策略。
     */
    public long getRecommendedTimeoutMs() {
        return recommendedTimeoutMs;
    }
}
