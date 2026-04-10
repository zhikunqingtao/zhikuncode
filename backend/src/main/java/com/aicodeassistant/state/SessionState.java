package com.aicodeassistant.state;

import com.aicodeassistant.model.Message;

import java.time.Instant;
import java.util.List;

/**
 * 会话核心状态 — 会话 ID、模型、消息历史、工作目录。
 *
 * @see <a href="SPEC §3.5.1">AppState 结构 - 会话核心</a>
 */
public record SessionState(
        String sessionId,
        String currentModel,
        String mainLoopModelForSession,
        List<Message> messages,
        String workingDirectory,
        List<String> additionalDirs,
        Instant sessionStartTime,
        int turnCount,
        String lastStopReason,
        boolean hasUnsavedChanges,
        // === 新增字段 ===
        boolean isMainAgent,       // 是否为主代理（非子代理）
        String parentTaskId,       // 父任务 ID（子代理时非 null）
        int nestingDepth,          // 代理嵌套深度（主代理=0）
        String projectRoot         // ★ 新增：项目根目录（用于路径安全验证） ★
) {
    public static SessionState empty() {
        return new SessionState(
                null, null, null, List.of(), null, List.of(),
                Instant.now(), 0, null, false,
                true, null, 0, null  // 默认为主代理, projectRoot 默认 null
        );
    }

    public SessionState withSessionId(String sessionId) {
        return new SessionState(sessionId, currentModel, mainLoopModelForSession, messages,
                workingDirectory, additionalDirs, sessionStartTime, turnCount,
                lastStopReason, hasUnsavedChanges, isMainAgent, parentTaskId, nestingDepth, projectRoot);
    }

    public SessionState withCurrentModel(String model) {
        return new SessionState(sessionId, model, mainLoopModelForSession, messages,
                workingDirectory, additionalDirs, sessionStartTime, turnCount,
                lastStopReason, hasUnsavedChanges, isMainAgent, parentTaskId, nestingDepth, projectRoot);
    }

    public SessionState withMessages(List<Message> messages) {
        return new SessionState(sessionId, currentModel, mainLoopModelForSession, messages,
                workingDirectory, additionalDirs, sessionStartTime, turnCount,
                lastStopReason, hasUnsavedChanges, isMainAgent, parentTaskId, nestingDepth, projectRoot);
    }

    public SessionState withWorkingDirectory(String workingDirectory) {
        return new SessionState(sessionId, currentModel, mainLoopModelForSession, messages,
                workingDirectory, additionalDirs, sessionStartTime, turnCount,
                lastStopReason, hasUnsavedChanges, isMainAgent, parentTaskId, nestingDepth, projectRoot);
    }

    public SessionState withTurnCount(int turnCount) {
        return new SessionState(sessionId, currentModel, mainLoopModelForSession, messages,
                workingDirectory, additionalDirs, sessionStartTime, turnCount,
                lastStopReason, hasUnsavedChanges, isMainAgent, parentTaskId, nestingDepth, projectRoot);
    }

    public SessionState withLastStopReason(String lastStopReason) {
        return new SessionState(sessionId, currentModel, mainLoopModelForSession, messages,
                workingDirectory, additionalDirs, sessionStartTime, turnCount,
                lastStopReason, hasUnsavedChanges, isMainAgent, parentTaskId, nestingDepth, projectRoot);
    }

    // === 新增 withXxx 方法 ===

    public SessionState withIsMainAgent(boolean isMainAgent) {
        return new SessionState(sessionId, currentModel, mainLoopModelForSession, messages,
                workingDirectory, additionalDirs, sessionStartTime, turnCount,
                lastStopReason, hasUnsavedChanges, isMainAgent, parentTaskId, nestingDepth, projectRoot);
    }

    public SessionState withParentTaskId(String parentTaskId) {
        return new SessionState(sessionId, currentModel, mainLoopModelForSession, messages,
                workingDirectory, additionalDirs, sessionStartTime, turnCount,
                lastStopReason, hasUnsavedChanges, isMainAgent, parentTaskId, nestingDepth, projectRoot);
    }

    public SessionState withNestingDepth(int nestingDepth) {
        return new SessionState(sessionId, currentModel, mainLoopModelForSession, messages,
                workingDirectory, additionalDirs, sessionStartTime, turnCount,
                lastStopReason, hasUnsavedChanges, isMainAgent, parentTaskId, nestingDepth, projectRoot);
    }

    // ★ 新增 withProjectRoot ★
    public SessionState withProjectRoot(String projectRoot) {
        return new SessionState(sessionId, currentModel, mainLoopModelForSession, messages,
                workingDirectory, additionalDirs, sessionStartTime, turnCount,
                lastStopReason, hasUnsavedChanges, isMainAgent, parentTaskId,
                nestingDepth, projectRoot);
    }
}
