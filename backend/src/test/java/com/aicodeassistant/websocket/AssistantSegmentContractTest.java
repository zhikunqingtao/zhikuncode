package com.aicodeassistant.websocket;

import com.aicodeassistant.engine.QueryMessageHandler;
import com.aicodeassistant.model.*;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AssistantSegmentContractTest {
    private final WebSocketController controller = mock(WebSocketController.class, CALLS_REAL_METHODS);
    private final WebSocketSessionManager sessions = mock(WebSocketSessionManager.class);
    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    private QueryMessageHandler handler;
    @BeforeEach void setUp() throws Exception {
        ReflectionTestUtils.setField(controller, "wsSessionManager", sessions);
        ReflectionTestUtils.setField(controller, "messaging", messaging);
        when(sessions.getPrincipalsForSession("session", true)).thenReturn(Set.of("principal"));
        when(sessions.getBindingEpochForPrincipal("principal")).thenReturn(3L);
        Class<?> handlerType = Arrays.stream(WebSocketController.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals("WsMessageHandler")).findFirst().orElseThrow();
        var constructor = handlerType.getDeclaredConstructor(WebSocketController.class, String.class);
        constructor.setAccessible(true);
        handler = (QueryMessageHandler) constructor.newInstance(controller, "session");
    }
    @Test void segmentUsesHistoryWireShapeAndBoundarySharesStoredUuidAndEnvelopeTimestamp() {
        Message.AssistantMessage assistant = new Message.AssistantMessage("assistant", Instant.ofEpochMilli(123),
                List.of(new ContentBlock.ToolUseBlock("tool", "Read", JsonNodeFactory.instance.objectNode().put("path", "file"))),
                "tool_use", new Usage(1, 2, 0, 0));
        Message.SystemMessage boundary = new Message.SystemMessage("boundary", Instant.now(), "", SystemMessageType.INFO,
                "task_boundary", Map.of("task_id", "A", "title", "Task A", "seq", 1, "turn_index", 2));
        handler.onAssistantMessage(assistant);
        handler.onTaskBoundary(boundary);
        ArgumentCaptor<Object> payloads = ArgumentCaptor.forClass(Object.class);
        verify(messaging, times(2)).convertAndSendToUser(eq("principal"), eq("/queue/messages"), payloads.capture());
        Map<?, ?> segmentFrame = (Map<?, ?>) payloads.getAllValues().getFirst();
        assertThat(segmentFrame.get("type")).isEqualTo("assistant_segment_complete");
        assertThat(segmentFrame.get("ts")).isInstanceOf(Long.class);
        assertThat(segmentFrame.get("_sessionId")).isEqualTo("session");
        assertThat(segmentFrame.get("_bindingEpoch")).isEqualTo(3L);
        Map<?, ?> wireMessage = (Map<?, ?>) segmentFrame.get("message");
        assertThat(wireMessage.get("uuid")).isEqualTo("assistant");
        assertThat(wireMessage.get("timestamp")).isEqualTo(123L);
        Map<?, ?> tool = (Map<?, ?>) ((List<?>) wireMessage.get("content")).getFirst();
        assertThat(tool.get("toolUseId")).isEqualTo("tool");
        assertThat(tool.get("toolName")).isEqualTo("Read");
        Map<?, ?> boundaryFrame = (Map<?, ?>) payloads.getAllValues().get(1);
        assertThat(boundaryFrame.get("message_id")).isEqualTo(boundary.uuid());
        assertThat(boundaryFrame.get("task_id")).isEqualTo("A");
        assertThat(boundaryFrame.get("ts")).isInstanceOf(Long.class);
        verify(sessions, times(2)).getPrincipalsForSession("session", true);
    }
    @Test void segmentPushFailureDoesNotAbortRun() {
        doThrow(new IllegalStateException("offline")).when(messaging).convertAndSendToUser(anyString(), anyString(), any(Object.class));
        handler.onAssistantMessage(new Message.AssistantMessage("assistant", Instant.now(), List.of(), "end_turn", new Usage(0, 0, 0, 0)));
        verify(messaging).convertAndSendToUser(anyString(), anyString(), any(Object.class));
    }
}
