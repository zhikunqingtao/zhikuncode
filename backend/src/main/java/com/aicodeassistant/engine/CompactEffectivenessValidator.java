package com.aicodeassistant.engine;

/**
 * 压缩效果验证 — 确认压缩后token确实降到安全水位。
 */
public class CompactEffectivenessValidator {

    private static final double SAFE_RATIO = 0.70;
    private static final int DEFAULT_RESERVED_FOR_SUMMARY = 20_000;

    /**
     * 验证压缩是否有效（token降到有效窗口的70%以下）。
     *
     * @param tokensAfter 压缩后的token数
     * @param contextWindow 模型上下文窗口
     * @param maxOutputTokens 模型最大输出token
     * @return true表示压缩有效
     */
    public boolean isEffective(int tokensAfter, int contextWindow, int maxOutputTokens) {
        int effectiveWindow = getEffectiveWindow(contextWindow, maxOutputTokens);
        double ratio = (double) tokensAfter / effectiveWindow;
        return ratio <= SAFE_RATIO;
    }

    /**
     * 计算还需要释放多少token才能达到安全水位。
     *
     * @param currentTokens 当前token数
     * @param contextWindow 模型上下文窗口
     * @param maxOutputTokens 模型最大输出token
     * @return 需要释放的token数（>=0）
     */
    public int tokensToRelease(int currentTokens, int contextWindow, int maxOutputTokens) {
        int effectiveWindow = getEffectiveWindow(contextWindow, maxOutputTokens);
        long safeTarget = (long) effectiveWindow * 70 / 100;
        return (int) Math.max(0L, (long) currentTokens - safeTarget);
    }

    private int getEffectiveWindow(int contextWindow, int maxOutputTokens) {
        int effectiveWindow = contextWindow - Math.min(maxOutputTokens, DEFAULT_RESERVED_FOR_SUMMARY);
        if (effectiveWindow <= 0) {
            effectiveWindow = Math.max(contextWindow / 2, 10_000);
        }
        return effectiveWindow;
    }
}
