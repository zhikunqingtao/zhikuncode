package com.aicodeassistant.state;

import java.util.Map;
import java.util.Set;

/**
 * UI 状态 — 流式响应、压缩中、加载指示器、中断、代理视图。
 *
 * @see <a href="SPEC §3.5.1">AppState 结构 - UI 状态</a>
 */
public record UiState(
        boolean isStreaming,
        boolean isCompacting,
        String spinnerMessage,
        boolean isAborted,
        String expandedView,
        boolean isBriefOnly,
        String viewSelectionMode,
        String foregroundedTaskId,
        String viewingAgentTaskId,
        Map<String, String> agentNameRegistry,
        Set<String> activeOverlays,
        boolean fastMode,
        String theme,
        String outputStyle,
        String language
) {
    public static UiState empty() {
        return new UiState(
                false, false, null, false,
                "none", false, "none",
                null, null, Map.of(), Set.of(),
                false, "dark", "default", "zh-CN"
        );
    }

    public UiState withStreaming(boolean streaming) {
        return new UiState(streaming, isCompacting, spinnerMessage, isAborted,
                expandedView, isBriefOnly, viewSelectionMode,
                foregroundedTaskId, viewingAgentTaskId, agentNameRegistry, activeOverlays,
                fastMode, theme, outputStyle, language);
    }

    public UiState withCompacting(boolean compacting) {
        return new UiState(isStreaming, compacting, spinnerMessage, isAborted,
                expandedView, isBriefOnly, viewSelectionMode,
                foregroundedTaskId, viewingAgentTaskId, agentNameRegistry, activeOverlays,
                fastMode, theme, outputStyle, language);
    }

    public UiState withSpinnerMessage(String message) {
        return new UiState(isStreaming, isCompacting, message, isAborted,
                expandedView, isBriefOnly, viewSelectionMode,
                foregroundedTaskId, viewingAgentTaskId, agentNameRegistry, activeOverlays,
                fastMode, theme, outputStyle, language);
    }

    public UiState withAborted(boolean aborted) {
        return new UiState(isStreaming, isCompacting, spinnerMessage, aborted,
                expandedView, isBriefOnly, viewSelectionMode,
                foregroundedTaskId, viewingAgentTaskId, agentNameRegistry, activeOverlays,
                fastMode, theme, outputStyle, language);
    }

    public UiState withTheme(String theme) {
        return new UiState(isStreaming, isCompacting, spinnerMessage, isAborted,
                expandedView, isBriefOnly, viewSelectionMode,
                foregroundedTaskId, viewingAgentTaskId, agentNameRegistry, activeOverlays,
                fastMode, theme, outputStyle, language);
    }

    public UiState withForegroundedTaskId(String taskId) {
        return new UiState(isStreaming, isCompacting, spinnerMessage, isAborted,
                expandedView, isBriefOnly, viewSelectionMode,
                taskId, viewingAgentTaskId, agentNameRegistry, activeOverlays,
                fastMode, theme, outputStyle, language);
    }

    public UiState withViewingAgentTaskId(String taskId) {
        return new UiState(isStreaming, isCompacting, spinnerMessage, isAborted,
                expandedView, isBriefOnly, viewSelectionMode,
                foregroundedTaskId, taskId, agentNameRegistry, activeOverlays,
                fastMode, theme, outputStyle, language);
    }
}
