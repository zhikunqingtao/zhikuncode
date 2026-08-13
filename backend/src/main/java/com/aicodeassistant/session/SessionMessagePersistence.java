package com.aicodeassistant.session;

import com.aicodeassistant.engine.QueryLoopState;
import com.aicodeassistant.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists messages as they enter a query state and retries only messages whose
 * first persistence attempt failed. Message UUIDs remain the database idempotency key.
 */
public final class SessionMessagePersistence {
    private static final Logger log = LoggerFactory.getLogger(SessionMessagePersistence.class);

    private final SessionManager sessions;
    private final String sessionId;
    private final String channel;
    private final Set<String> persistedMessageIds = ConcurrentHashMap.newKeySet();

    private SessionMessagePersistence(SessionManager sessions, String sessionId, String channel) {
        this.sessions = sessions;
        this.sessionId = sessionId;
        this.channel = channel;
    }

    public static SessionMessagePersistence attach(
            QueryLoopState state, SessionManager sessions, String sessionId, String channel) {
        SessionMessagePersistence persistence =
                new SessionMessagePersistence(sessions, sessionId, channel);
        state.addMessageListener(persistence::persistBestEffort);
        return persistence;
    }

    /** Retry only messages not confirmed by the incremental listener. */
    public int reconcile(List<Message> messages) {
        int recovered = 0;
        for (Message message : messages) {
            if (persistedMessageIds.contains(message.uuid())) continue;
            if (persistBestEffort(message)) recovered++;
        }
        return recovered;
    }

    private boolean persistBestEffort(Message message) {
        if (message == null || message.uuid() == null
                || persistedMessageIds.contains(message.uuid())) return true;
        try {
            switch (message) {
                case Message.UserMessage user -> sessions.addMessageWithId(
                        user.uuid(), sessionId, "user", user.content(), null, 0, 0);
                case Message.AssistantMessage assistant -> sessions.addMessageWithId(
                        assistant.uuid(), sessionId, "assistant", assistant.content(),
                        assistant.stopReason(),
                        assistant.usage() == null ? 0 : assistant.usage().inputTokens(),
                        assistant.usage() == null ? 0 : assistant.usage().outputTokens());
                case Message.SystemMessage system -> sessions.addMessageWithId(
                        system.uuid(), sessionId, "system", system.content(), null, 0, 0);
            }
            persistedMessageIds.add(message.uuid());
            return true;
        } catch (RuntimeException failure) {
            log.error("{} message persistence failed, sessionId={}, messageId={}",
                    channel, sessionId, message.uuid(), failure);
            return false;
        }
    }
}
