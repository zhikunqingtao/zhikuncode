package com.aicodeassistant.session;

import com.aicodeassistant.engine.QueryLoopState;
import com.aicodeassistant.model.Message;
import java.util.concurrent.atomic.AtomicReference;

/** Strict synchronous message writer; the first failure is latched for the run. */
public final class SessionMessagePersistence implements QueryLoopState.MessagePersistenceSink {
    private final SessionManager sessions;
    private final String sessionId;
    private final String channel;
    private final AtomicReference<MessagePersistenceException> failure = new AtomicReference<>();

    private SessionMessagePersistence(SessionManager sessions, String sessionId, String channel) {
        this.sessions = sessions;
        this.sessionId = sessionId;
        this.channel = channel;
    }

    public static SessionMessagePersistence attach(
            QueryLoopState state, SessionManager sessions, String sessionId, String channel) {
        SessionMessagePersistence persistence =
                new SessionMessagePersistence(sessions, sessionId, channel);
        state.setPersistenceSink(persistence);
        return persistence;
    }

    @Override
    public void assertHealthy() {
        MessagePersistenceException latched = failure.get();
        if (latched != null) throw latched;
    }

    /**
     * SystemMessage 的可持久化 meta：messages 表只有 meta_json 一列，
     * subtype 无独立列，故合并进 metadata 一并序列化（读取端按 "subtype" 键拆出还原）。
     */
    private static java.util.Map<String, Object> persistableMeta(Message.SystemMessage system) {
        java.util.Map<String, Object> meta = system.metadata() == null
                ? new java.util.LinkedHashMap<>()
                : new java.util.LinkedHashMap<>(system.metadata());
        if (system.subtype() != null && !system.subtype().isBlank()) {
            meta.put("subtype", system.subtype());
        }
        return meta.isEmpty() ? null : meta;
    }

    @Override
    public void persist(Message message) {
        assertHealthy();
        if (message == null || message.uuid() == null) {
            throw latch(new MessagePersistenceException(
                    "PERSISTENCE_FAILED", channel + " cannot persist a message without an id"));
        }
        try {
            switch (message) {
                case Message.UserMessage user -> sessions.addMessageWithId(
                        user.uuid(), sessionId, "user", user.content(), null, 0, 0,
                        user.meta());
                case Message.AssistantMessage assistant -> sessions.addMessageWithId(
                        assistant.uuid(), sessionId, "assistant", assistant.content(),
                        assistant.stopReason(),
                        assistant.usage() == null ? 0 : assistant.usage().inputTokens(),
                        assistant.usage() == null ? 0 : assistant.usage().outputTokens(),
                        null);
                case Message.SystemMessage system -> sessions.addMessageWithId(
                        system.uuid(), sessionId, "system", system.content(), null, 0, 0,
                        persistableMeta(system));
            }
        } catch (RuntimeException failure) {
            if (failure instanceof MessagePersistenceException persistenceFailure) {
                throw latch(persistenceFailure);
            }
            throw latch(new MessagePersistenceException(
                    "PERSISTENCE_FAILED",
                    channel + " message persistence failed for " + message.uuid(), failure));
        }
    }

    private MessagePersistenceException latch(MessagePersistenceException candidate) {
        failure.compareAndSet(null, candidate);
        return failure.get();
    }
}
