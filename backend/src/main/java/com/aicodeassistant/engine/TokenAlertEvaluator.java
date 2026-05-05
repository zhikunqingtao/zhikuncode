package com.aicodeassistant.engine;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Token 使用率三级告警评估器。
 * 对齐 CC 的 70%/90%/触发 三级递进模型。
 */
public class TokenAlertEvaluator {

    private static final Logger log = LoggerFactory.getLogger(TokenAlertEvaluator.class);

    public enum Level {
        NORMAL,           // < 70%
        WARNING,          // 70%-90%
        CRITICAL,         // > 90%
        TRIGGER_COMPACT   // 超出 effectiveWindow
    }

    private static final int DEFAULT_RESERVED_FOR_SUMMARY = 20_000;
    private static final double WARNING_THRESHOLD = 0.70;
    private static final double CRITICAL_THRESHOLD = 0.90;

    /**
     * 评估当前 token 使用率告警级别。
     *
     * @param currentTokens 当前已使用token数
     * @param contextWindow 模型上下文窗口大小
     * @param maxOutputTokens 模型最大输出token
     * @return 告警级别
     */
    public Level evaluate(int currentTokens, int contextWindow, int maxOutputTokens) {
        int effectiveWindow = getEffectiveWindow(contextWindow, maxOutputTokens);
        double ratio = (double) currentTokens / effectiveWindow;

        if (ratio >= 1.0) return Level.TRIGGER_COMPACT;
        if (ratio >= CRITICAL_THRESHOLD) return Level.CRITICAL;
        if (ratio >= WARNING_THRESHOLD) return Level.WARNING;
        return Level.NORMAL;
    }

    /**
     * 获取有效窗口大小（contextWindow减去为输出和摘要预留的空间）。
     *
     * @param contextWindow 模型上下文窗口大小
     * @param maxOutputTokens 模型最大输出token
     * @return 有效窗口大小
     */
    public int getEffectiveWindow(int contextWindow, int maxOutputTokens) {
        int reservedForSummary = Math.min(maxOutputTokens, DEFAULT_RESERVED_FOR_SUMMARY);
        int effectiveWindow = contextWindow - reservedForSummary;
        if (effectiveWindow <= 0) {
            effectiveWindow = Math.max(contextWindow / 2, 10_000);
        }
        return effectiveWindow;
    }
}
