package com.aicodeassistant.llm;

/**
 * 模型能力信息 — 描述特定模型支持的功能。
 * 用于前端展示模型列表和功能适配。
 *
 * @see <a href="SPEC §3.1.3">流式处理</a>
 */
public record ModelCapabilities(
        String modelId,
        String displayName,
        int maxOutputTokens,
        int contextWindow,
        boolean supportsStreaming,
        boolean supportsThinking,
        boolean supportsImages,
        boolean supportsToolUse,
        double costPer1kInput,
        double costPer1kOutput
) {

    /** 保守默认值 — 未知模型使用 */
    public static final ModelCapabilities DEFAULT = new ModelCapabilities(
            "unknown", "Unknown Model",
            4096, 8192,
            true, false, false, false,
            0.0, 0.0
    );
}
