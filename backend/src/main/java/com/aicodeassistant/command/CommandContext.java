package com.aicodeassistant.command;

import com.aicodeassistant.state.AppState;

/**
 * 命令执行上下文 — 提供命令执行所需的环境信息。
 *
 * @param sessionId      当前会话 ID
 * @param workingDir     当前工作目录
 * @param currentModel   当前使用的模型
 * @param appState       应用状态快照
 * @param isAuthenticated 是否已认证
 * @param isRemoteMode   是否为远程模式
 * @param isBridgeMode   是否为桥接模式
 * @see <a href="SPEC §3.3">命令系统</a>
 */
public record CommandContext(
        String sessionId,
        String workingDir,
        String currentModel,
        AppState appState,
        boolean isAuthenticated,
        boolean isRemoteMode,
        boolean isBridgeMode
) {

    /**
     * 创建基础上下文 — 用于简单场景。
     */
    public static CommandContext of(String sessionId, String workingDir,
                                    String currentModel, AppState appState) {
        return new CommandContext(sessionId, workingDir, currentModel,
                appState, false, false, false);
    }
}
