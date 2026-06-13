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
        double costPer1kOutput
) {

    /** 保守默认值 — 未知模型使用 */
    public static final ModelCapabilities DEFAULT = new ModelCapabilities(
            "unknown", "Unknown Model",
            4096, 8192,
            true, false, false, 0, false,
            0.0, 0.0
    );
}
