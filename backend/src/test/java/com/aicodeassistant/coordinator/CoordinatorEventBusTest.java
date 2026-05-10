package com.aicodeassistant.coordinator;

import com.aicodeassistant.websocket.WebSocketSessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * CoordinatorEventBus 单元测试骨架。
 * 引用：docs/Task3-5差异化升级功能测试方案.md §3.1（TC-T3-EB-01..13）
 *
 * 本骨架先落地 P0 红线 TC-T3-EB-09 + TC-T3-EB-13 + TC-T3-EB-01 + TC-T3-EB-12，
 * 其余用例由后续迭代补齐。
 */
class CoordinatorEventBusTest {

    private SimpMessagingTemplate messaging;
    private WebSocketSessionManager wsSessionManager;
    private CoordinatorEventBus bus;

    @BeforeEach
    void setUp() {
        messaging = mock(SimpMessagingTemplate.class);
        wsSessionManager = mock(WebSocketSessionManager.class);
        when(wsSessionManager.getPrincipalForSession(anyString())).thenReturn("user-a");
        bus = new CoordinatorEventBus(messaging, wsSessionManager);
    }

    /**
     * TC-T3-EB-09（P0 红线）：safeSend 遇到下游 STOMP 异常不得侵入业务线程。
     */
    @Test
    void safeSend_whenTemplateThrows_shouldSwallowAndNotPropagate() {
        doThrow(new RuntimeException("STOMP broker down"))
                .when(messaging).convertAndSendToUser(anyString(), anyString(), any(Object.class));

        assertThatCode(() -> bus.publishPhaseTransition("sess-1", "wf-1", "PLANNING", "EXECUTING"))
                .doesNotThrowAnyException();

        verify(messaging, atLeastOnce())
                .convertAndSendToUser(anyString(), anyString(), any(Object.class));
    }

    /**
     * TC-T3-EB-13：publishMailboxWrite 的 content=null 应兜底 contentLength=0、content="", 不抛 NPE。
     */
    @Test
    @SuppressWarnings("unchecked")
    void publishMailboxWrite_withNullContent_shouldFallbackToZeroLength() {
        ArgumentCaptor<Object> envelopeCaptor = ArgumentCaptor.forClass(Object.class);

        assertThatCode(() -> bus.publishMailboxWrite("sess-1", "wf-1", "agentA", "agentB", null))
                .doesNotThrowAnyException();

        verify(messaging).convertAndSendToUser(anyString(), anyString(), envelopeCaptor.capture());
        Map<String, Object> envelope = (Map<String, Object>) envelopeCaptor.getValue();
        Map<String, Object> payload = (Map<String, Object>) envelope.get("payload");
        assertThat(payload).isNotNull();
        assertThat(payload.get("contentLength")).isEqualTo(0);
        assertThat(payload.get("content")).isEqualTo("");
    }

    /**
     * TC-T3-EB-01：publishPhaseTransition 正常路径产出合法 envelope。
     */
    @Test
    @SuppressWarnings("unchecked")
    void publishPhaseTransition_shouldBuildEnvelopeWithRequiredFields() {
        ArgumentCaptor<Object> envelopeCaptor = ArgumentCaptor.forClass(Object.class);

        bus.publishPhaseTransition("sess-42", "wf-99", "IDLE", "PLANNING");

        verify(messaging).convertAndSendToUser(anyString(), anyString(), envelopeCaptor.capture());
        Map<String, Object> envelope = (Map<String, Object>) envelopeCaptor.getValue();
        assertThat(envelope.get("type")).isEqualTo("coordinator_event");
        assertThat(envelope.get("sessionId")).isEqualTo("sess-42");
        assertThat(envelope.get("workflowId")).isEqualTo("wf-99");
        assertThat(envelope.get("eventType")).isEqualTo("phase_transition");
        Map<String, Object> payload = (Map<String, Object>) envelope.get("payload");
        assertThat(payload.get("previousPhase")).isEqualTo("IDLE");
        assertThat(payload.get("nextPhase")).isEqualTo("PLANNING");
    }

    /**
     * TC-T3-EB-12：blank / null sessionId 不得触发 STOMP 调用。
     */
    @Test
    void publishMailboxWrite_withBlankSessionId_shouldSkipStomp() {
        bus.publishMailboxWrite("   ", "wf-1", "a", "b", "hello");
        bus.publishMailboxWrite("", "wf-1", "a", "b", "hello");
        bus.publishMailboxWrite(null, "wf-1", "a", "b", "hello");

        verifyNoInteractions(messaging);
    }

    /**
     * TC-T3-EB-11：workflowId=null 时 fallback 到 sessionId。
     */
    @Test
    @SuppressWarnings("unchecked")
    void publishPhaseTransition_withNullWorkflowId_shouldFallbackToSessionId() {
        ArgumentCaptor<Object> envelopeCaptor = ArgumentCaptor.forClass(Object.class);

        bus.publishPhaseTransition("sess-x", null, "IDLE", "PLANNING");

        verify(messaging).convertAndSendToUser(anyString(), anyString(), envelopeCaptor.capture());
        Map<String, Object> envelope = (Map<String, Object>) envelopeCaptor.getValue();
        assertThat(envelope.get("workflowId")).isEqualTo("sess-x");
    }
}
