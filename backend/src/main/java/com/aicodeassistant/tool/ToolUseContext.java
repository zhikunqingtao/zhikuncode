package com.aicodeassistant.tool;

import java.util.List;
import java.util.function.Consumer;

/**
 * 工具使用上下文 — 包含工具执行所需的所有环境信息。
 * 每个 query loop 轮次构建一个共享的上下文实例。
 *
 * @see <a href="SPEC §3.2.1">工具接口定义</a>
 */
public record ToolUseContext(
        String workingDirectory,
        String sessionId,
        String toolUseId,
        Consumer<String> onProgress,
        List<String> additionalDirs,
        boolean userModified,
        int nestingDepth,
        String currentTaskId
) {

    /** 兼容旧构造 — 无 nestingDepth/currentTaskId 时默认 0/null */
    public ToolUseContext(String workingDirectory, String sessionId, String toolUseId,
                          Consumer<String> onProgress, List<String> additionalDirs,
                          boolean userModified) {
        this(workingDirectory, sessionId, toolUseId, onProgress, additionalDirs, userModified, 0, null);
    }

    /** 兼容旧构造 — 无 currentTaskId 时默认 null */
    public ToolUseContext(String workingDirectory, String sessionId, String toolUseId,
                          Consumer<String> onProgress, List<String> additionalDirs,
                          boolean userModified, int nestingDepth) {
        this(workingDirectory, sessionId, toolUseId, onProgress, additionalDirs, userModified, nestingDepth, null);
    }

    /** 简化构造 — 最小必要参数 */
    public static ToolUseContext of(String workingDirectory, String sessionId) {
        return new ToolUseContext(workingDirectory, sessionId, null, null, List.of(), false, 0, null);
    }

    /** 带 toolUseId */
    public ToolUseContext withToolUseId(String toolUseId) {
        return new ToolUseContext(workingDirectory, sessionId, toolUseId, onProgress, additionalDirs, userModified, nestingDepth, currentTaskId);
    }

    /** 带 nestingDepth — 子代理递增使用 */
    public ToolUseContext withNestingDepth(int nestingDepth) {
        return new ToolUseContext(workingDirectory, sessionId, toolUseId, onProgress, additionalDirs, userModified, nestingDepth, currentTaskId);
    }

    /** 带 currentTaskId — 子任务上下文使用 */
    public ToolUseContext withCurrentTaskId(String currentTaskId) {
        return new ToolUseContext(workingDirectory, sessionId, toolUseId, onProgress, additionalDirs, userModified, nestingDepth, currentTaskId);
    }
}
