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
        boolean hasUnsavedChanges
) {
    public static SessionState empty() {
        return new SessionState(
                null, null, null, List.of(), null, List.of(),
                Instant.now(), 0, null, false
        );
    }

    public SessionState withSessionId(String sessionId) {
        return new SessionState(sessionId, currentModel, mainLoopModelForSession, messages,
                workingDirectory, additionalDirs, sessionStartTime, turnCount, lastStopReason, hasUnsavedChanges);
    }

    public SessionState withCurrentModel(String model) {
        return new SessionState(sessionId, model, mainLoopModelForSession, messages,
                workingDirectory, additionalDirs, sessionStartTime, turnCount, lastStopReason, hasUnsavedChanges);
    }

    public SessionState withMessages(List<Message> messages) {
        return new SessionState(sessionId, currentModel, mainLoopModelForSession, messages,
                workingDirectory, additionalDirs, sessionStartTime, turnCount, lastStopReason, hasUnsavedChanges);
    }

    public SessionState withWorkingDirectory(String workingDirectory) {
        return new SessionState(sessionId, currentModel, mainLoopModelForSession, messages,
                workingDirectory, additionalDirs, sessionStartTime, turnCount, lastStopReason, hasUnsavedChanges);
    }

    public SessionState withTurnCount(int turnCount) {
        return new SessionState(sessionId, currentModel, mainLoopModelForSession, messages,
                workingDirectory, additionalDirs, sessionStartTime, turnCount, lastStopReason, hasUnsavedChanges);
    }

    public SessionState withLastStopReason(String lastStopReason) {
        return new SessionState(sessionId, currentModel, mainLoopModelForSession, messages,
                workingDirectory, additionalDirs, sessionStartTime, turnCount, lastStopReason, hasUnsavedChanges);
    }
}
