package com.aicodeassistant.engine;

/**
 * CollapseLevel — 三级渐进折叠策略 sealed interface。
 * 定义消息按距尾部距离的折叠级别。
 *
 * @see ContextCollapseService#progressiveCollapse
 */
public sealed interface CollapseLevel
        permits CollapseLevel.FullRetention,
                CollapseLevel.SummaryRetention,
                CollapseLevel.SkeletonRetention {

    /** 距尾部的消息数阈值（inclusive），该级别覆盖 [尾部-maxAge, 尾部-prevMaxAge) 区间 */
    int maxAgeMessages();

    /** 对消息内容执行折叠 */
    String collapse(String originalContent);

    /** Level A: 完整保留 — 尾部 10 条消息原样保留 */
    record FullRetention(int maxAgeMessages) implements CollapseLevel {
        public FullRetention() { this(10); }
        @Override public String collapse(String originalContent) {
            return originalContent; // 不做任何处理
        }
    }

    /** Level B: 摘要保留 — 倒数 10-30 条，长文本截断保留前 500 字符 */
    record SummaryRetention(int maxAgeMessages, int keepChars) implements CollapseLevel {
        public SummaryRetention() { this(30, 500); }
        @Override public String collapse(String originalContent) {
            if (originalContent == null || originalContent.length() <= keepChars) {
                return originalContent;
            }
            return originalContent.substring(0, Math.min(keepChars, originalContent.length()))
                    + "\n...[summary-collapsed: " + originalContent.length() + " chars]";
        }
    }

    /** Level C: 骨架保留 — 30 条以前，仅保留 role + toolUseId + 一行摘要 */
    record SkeletonRetention(int maxAgeMessages) implements CollapseLevel {
        public SkeletonRetention() { this(Integer.MAX_VALUE); }
        @Override public String collapse(String originalContent) {
            if (originalContent == null || originalContent.length() <= 50) {
                return originalContent;
            }
            int newline = originalContent.indexOf('\n');
            String firstLine = newline > 0
                    ? originalContent.substring(0, Math.min(newline, 80))
                    : originalContent.substring(0, Math.min(80, originalContent.length()));
            return "[skeleton] " + firstLine + "...";
        }
    }
}
