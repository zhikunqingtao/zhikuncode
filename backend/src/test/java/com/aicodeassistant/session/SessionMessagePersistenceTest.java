package com.aicodeassistant.session;

import com.aicodeassistant.engine.QueryLoopState;
import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.aicodeassistant.model.SystemMessageType;
import com.aicodeassistant.tool.ToolUseContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionMessagePersistenceTest {
    @Test
    void normalAppendIsPersistedExactlyOnce() {
        SessionManager sessions = mock(SessionManager.class);
        QueryLoopState state = new QueryLoopState(List.of(), ToolUseContext.of(".", "session-1"));
        SessionMessagePersistence.attach(
                state, sessions, "session-1", "test");
        Message.UserMessage message = user("message-1");

        state.addMessage(message);
        verify(sessions, times(1)).addMessageWithId(eq("message-1"), eq("session-1"),
                eq("user"), eq(message.content()), eq(null), eq(0), eq(0), eq(null));
    }

    @Test
    void userMessageMetaIsForwardedToPersistence() {
        SessionManager sessions = mock(SessionManager.class);
        QueryLoopState state = new QueryLoopState(List.of(), ToolUseContext.of(".", "session-1"));
        SessionMessagePersistence.attach(state, sessions, "session-1", "test");
        Message.UserMessage steering = new Message.UserMessage(
                "message-steering", Instant.now(),
                List.of(new ContentBlock.TextBlock("change direction")),
                null, null, java.util.Map.of("steering", true));

        state.addMessage(steering);

        verify(sessions, times(1)).addMessageWithId(eq("message-steering"), eq("session-1"),
                eq("user"), eq(steering.content()), eq(null), eq(0), eq(0),
                eq(java.util.Map.of("steering", true)));
    }

    @Test
    void firstFailureIsLatchedAndNeverRetried() {
        SessionManager sessions = mock(SessionManager.class);
        QueryLoopState state = new QueryLoopState(List.of(), ToolUseContext.of(".", "session-1"));
        Message.UserMessage message = user("message-2");
        doThrow(new RuntimeException("temporary"))
                .when(sessions).addMessageWithId(eq("message-2"), eq("session-1"),
                        eq("user"), eq(message.content()), eq(null), eq(0), eq(0), eq(null));
        SessionMessagePersistence persistence = SessionMessagePersistence.attach(
                state, sessions, "session-1", "test");

        assertThatThrownBy(() -> state.addMessage(message))
                .isInstanceOf(MessagePersistenceException.class);
        assertThatThrownBy(() -> state.addMessage(user("message-3")))
                .isInstanceOf(MessagePersistenceException.class);
        assertThatThrownBy(persistence::assertHealthy)
                .isInstanceOf(MessagePersistenceException.class);

        verify(sessions, times(1)).addMessageWithId(eq("message-2"), eq("session-1"),
                eq("user"), eq(message.content()), eq(null), eq(0), eq(0), eq(null));
    }

    @Test
    void systemMessageSubtypeIsMergedIntoPersistedMeta() {
        SessionManager sessions = mock(SessionManager.class);
        QueryLoopState state = new QueryLoopState(List.of(), ToolUseContext.of(".", "session-1"));
        SessionMessagePersistence.attach(state, sessions, "session-1", "test");
        Message.SystemMessage boundary = new Message.SystemMessage(
                "sys-1", Instant.now(), "", SystemMessageType.INFO,
                "task_boundary", java.util.Map.of("task_id", "t1", "seq", 1));

        state.addMessage(boundary);

        // messages 表只有 meta_json 一列：subtype 合并进 metadata 一并持久化
        verify(sessions, times(1)).addMessageWithId(eq("sys-1"), eq("session-1"),
                eq("system"), eq(""), eq(null), eq(0), eq(0),
                eq(java.util.Map.of("task_id", "t1", "seq", 1, "subtype", "task_boundary")));
    }

    @Test
    void systemMessageWithoutSubtypeOrMetadataPersistsNullMeta() {
        SessionManager sessions = mock(SessionManager.class);
        QueryLoopState state = new QueryLoopState(List.of(), ToolUseContext.of(".", "session-1"));
        SessionMessagePersistence.attach(state, sessions, "session-1", "test");
        Message.SystemMessage plain = new Message.SystemMessage(
                "sys-2", Instant.now(), "note", SystemMessageType.INFO);

        state.addMessage(plain);

        verify(sessions, times(1)).addMessageWithId(eq("sys-2"), eq("session-1"),
                eq("system"), eq("note"), eq(null), eq(0), eq(0), eq(null));
    }

    private static Message.UserMessage user(String id) {
        return new Message.UserMessage(id, Instant.now(),
                List.of(new ContentBlock.TextBlock("hello")), null, null);
    }
}
