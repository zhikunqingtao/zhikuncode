package com.aicodeassistant.engine;

import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.model.Message;
import com.aicodeassistant.session.SessionManager;
import com.aicodeassistant.session.SessionMessagePersistence;
import com.aicodeassistant.tool.ToolUseContext;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class TaskBoundaryEmissionTest {
    private final QueryEngine engine = mock(QueryEngine.class, CALLS_REAL_METHODS);
    private QueryLoopState state() {
        return new QueryLoopState(List.of(), ToolUseContext.of(".", "session"));
    }
    private void emit(QueryLoopState state, QueryMessageHandler handler, String payload, boolean failed) {
        ReflectionTestUtils.invokeMethod(engine, "emitTaskBoundaries", state, handler,
                List.of(new Message.UserMessage("result", Instant.now(), List.of(
                        new ContentBlock.ToolResultBlock("todo", payload, failed)), null, null)),
                List.of(new ContentBlock.ToolUseBlock("todo", "TodoWrite", com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode())));
    }
    private final String payload = "{\"oldTodos\":[],\"newTodos\":[{\"id\":\"A\",\"content\":\"Task A\",\"status\":\"IN_PROGRESS\"}]}";

    @Test void stateAndPersistenceAttemptPrecedePushAndShareId() {
        QueryLoopState state = state();
        SessionManager sessions = mock(SessionManager.class);
        SessionMessagePersistence.attach(state, sessions, "session", "test");
        QueryMessageHandler handler = mock(QueryMessageHandler.class);
        doAnswer(invocation -> {
            Message.SystemMessage message = invocation.getArgument(0);
            assertThat(state.getMessages()).contains(message);
            verify(sessions).addMessageWithId(eq(message.uuid()), eq("session"), eq("system"), any(), isNull(), eq(0), eq(0), anyMap());
            return null;
        }).when(handler).onTaskBoundary(any());
        emit(state, handler, payload, false);
        verify(handler).onTaskBoundary(any());
        assertThat(state.getMessages()).hasSize(1);
    }
    @Test void failedPushDoesNotLoseBoundaryAndDetectionSkipsInvalidResults() {
        QueryLoopState state = state();
        QueryMessageHandler handler = mock(QueryMessageHandler.class);
        doThrow(new IllegalStateException("offline")).when(handler).onTaskBoundary(any());
        emit(state, handler, "not json", false);
        emit(state, handler, payload, true);
        emit(state, handler, payload, false);
        emit(state, handler, payload, false);
        assertThat(state.getMessages()).hasSize(1);
        verify(handler, times(1)).onTaskBoundary(any());
    }
    @Test void turnIndexCountsSessionInstructionsButNotSteeringOrToolResults() {
        QueryLoopState state = state();
        state.addMessage(new Message.UserMessage("u1", Instant.now(), List.of(new ContentBlock.TextBlock("first")), null, null));
        state.addMessage(new Message.UserMessage("steer", Instant.now(), List.of(new ContentBlock.TextBlock("steer")), null, null, Map.of("steering", true)));
        state.addMessage(new Message.UserMessage("u2", Instant.now(), List.of(new ContentBlock.TextBlock("second")), null, null));
        emit(state, mock(QueryMessageHandler.class), payload, false);
        assertThat(((Message.SystemMessage) state.getMessages().getLast()).metadata()).containsEntry("turn_index", 2);
    }
    @Test void failedDatabaseAttemptStopsBeforeStateAndPush() {
        QueryLoopState state = state();
        SessionManager sessions = mock(SessionManager.class);
        doThrow(new IllegalStateException("temporary")).when(sessions)
                .addMessageWithId(anyString(), anyString(), eq("system"), any(), isNull(), eq(0), eq(0), anyMap());
        SessionMessagePersistence.attach(state, sessions, "session", "test");
        QueryMessageHandler handler = mock(QueryMessageHandler.class);
        assertThatThrownBy(() -> emit(state, handler, payload, false))
                .isInstanceOf(com.aicodeassistant.session.MessagePersistenceException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(state.getMessages()).isEmpty();
        verify(handler, never()).onTaskBoundary(any());
        verify(sessions, times(1)).addMessageWithId(anyString(), anyString(), eq("system"), any(), isNull(), eq(0), eq(0), anyMap());
    }
}
