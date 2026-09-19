package com.aicodeassistant.session;

public final class SessionExecutionBusyException extends RuntimeException {
    public SessionExecutionBusyException(String sessionId) {
        super("Session is already executing: " + sessionId);
    }
}
