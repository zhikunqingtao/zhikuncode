package com.aicodeassistant.session;

import com.aicodeassistant.engine.QueryLoopState;
import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.aicodeassistant.tool.ToolUseContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SessionMessagePersistenceTest {
    @Test
    void normalListenerWriteIsNotRepeatedByReconciliation() {
        SessionManager sessions = mock(SessionManager.class);
        QueryLoopState state = new QueryLoopState(List.of(), ToolUseContext.of(".", "session-1"));
        SessionMessagePersistence persistence = SessionMessagePersistence.attach(
                state, sessions, "session-1", "test");
        Message.UserMessage message = user("message-1");

        state.addMessage(message);
        persistence.reconcile(List.of(message));

        verify(sessions, times(1)).addMessageWithId(eq("message-1"), eq("session-1"),
                eq("user"), eq(message.content()), eq(null), eq(0), eq(0));
    }

    @Test
    void reconciliationRetriesAListenerFailure() {
        SessionManager sessions = mock(SessionManager.class);
        QueryLoopState state = new QueryLoopState(List.of(), ToolUseContext.of(".", "session-1"));
        Message.UserMessage message = user("message-2");
        doThrow(new RuntimeException("temporary"))
                .doNothing()
                .when(sessions).addMessageWithId(eq("message-2"), eq("session-1"),
                        eq("user"), eq(message.content()), eq(null), eq(0), eq(0));
        SessionMessagePersistence persistence = SessionMessagePersistence.attach(
                state, sessions, "session-1", "test");

        state.addMessage(message);
        persistence.reconcile(List.of(message));

        verify(sessions, times(2)).addMessageWithId(eq("message-2"), eq("session-1"),
                eq("user"), eq(message.content()), eq(null), eq(0), eq(0));
    }

    private static Message.UserMessage user(String id) {
        return new Message.UserMessage(id, Instant.now(),
                List.of(new ContentBlock.TextBlock("hello")), null, null);
    }
}
