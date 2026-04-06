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
        boolean userModified
) {

    /** 简化构造 — 最小必要参数 */
    public static ToolUseContext of(String workingDirectory, String sessionId) {
        return new ToolUseContext(workingDirectory, sessionId, null, null, List.of(), false);
    }

    /** 带 toolUseId */
    public ToolUseContext withToolUseId(String toolUseId) {
        return new ToolUseContext(workingDirectory, sessionId, toolUseId, onProgress, additionalDirs, userModified);
    }
}
