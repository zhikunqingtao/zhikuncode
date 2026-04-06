package com.aicodeassistant.state;

import java.util.Map;

/**
 * 上下文管理状态 — 自动压缩、文件状态缓存、CLAUDE.md 内容、记忆。
 *
 * @see <a href="SPEC §3.5.1">AppState 结构 - 上下文管理</a>
 */
public record ContextState(
        AutoCompactTrackingState autoCompactTracking,
        Map<String, String> claudeMdContents,
        String memoryContent,
        Map<String, Object> settings,
        boolean verbose
) {
    /**
     * 自动压缩追踪状态。
     */
    public record AutoCompactTrackingState(
            boolean enabled,
            double threshold,
            int compactCount
    ) {
        public static AutoCompactTrackingState defaultState() {
            return new AutoCompactTrackingState(true, 0.8, 0);
        }
    }

    public static ContextState empty() {
        return new ContextState(
                AutoCompactTrackingState.defaultState(),
                Map.of(), null, Map.of(), false
        );
    }

    public ContextState withClaudeMdContents(Map<String, String> contents) {
        return new ContextState(autoCompactTracking, contents, memoryContent, settings, verbose);
    }

    public ContextState withMemoryContent(String content) {
        return new ContextState(autoCompactTracking, claudeMdContents, content, settings, verbose);
    }

    public ContextState withSettings(Map<String, Object> settings) {
        return new ContextState(autoCompactTracking, claudeMdContents, memoryContent, settings, verbose);
    }

    public ContextState withVerbose(boolean verbose) {
        return new ContextState(autoCompactTracking, claudeMdContents, memoryContent, settings, verbose);
    }
}
