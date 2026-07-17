package com.aicodeassistant.llm;

/**
 * 模型能力信息 — 描述特定模型支持的功能。
 * 用于前端展示模型列表和功能适配。
 *
 */
public record ModelCapabilities(
        String modelId,
        String displayName,
        int maxOutputTokens,
        int contextWindow,
        boolean supportsStreaming,
        boolean supportsThinking,
        boolean supportsImages,
        int maxImages,
        boolean supportsToolUse,
        double costPer1kInput,
        double costPer1kOutput,
        double tokenCharRatio,
        boolean supportsCache
) {

    public ModelCapabilities(String modelId, String displayName, int maxOutputTokens, int contextWindow,
                             boolean supportsStreaming, boolean supportsThinking, boolean supportsImages,
                             int maxImages, boolean supportsToolUse, double costPer1kInput,
                             double costPer1kOutput, double tokenCharRatio) {
        this(modelId, displayName, maxOutputTokens, contextWindow, supportsStreaming, supportsThinking,
                supportsImages, maxImages, supportsToolUse, costPer1kInput, costPer1kOutput,
                tokenCharRatio, false);
    }

    public ModelCapabilities(String modelId, String displayName, int maxOutputTokens, int contextWindow,
                             boolean supportsStreaming, boolean supportsThinking, boolean supportsImages,
                             int maxImages, boolean supportsToolUse, double costPer1kInput,
                             double costPer1kOutput) {
        this(modelId, displayName, maxOutputTokens, contextWindow, supportsStreaming, supportsThinking,
                supportsImages, maxImages, supportsToolUse, costPer1kInput, costPer1kOutput, 3.5, false);
    }

    public ModelCapabilities {
        if (maxOutputTokens <= 0 || contextWindow <= 0 || maxOutputTokens >= contextWindow)
            throw new IllegalArgumentException("Invalid model token limits for " + modelId);
        if (!Double.isFinite(tokenCharRatio) || tokenCharRatio <= 0.5 || tokenCharRatio > 16.0)
            throw new IllegalArgumentException("Invalid tokenCharRatio for " + modelId);
    }

    /** 保守默认值 — 未知模型使用 */
    public static final ModelCapabilities DEFAULT = new ModelCapabilities(
            "unknown", "Unknown Model",
            4096, 8192,
            true, false, false, 0, false,
            0.0, 0.0, 3.5, false
    );
}
