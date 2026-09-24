package com.aicodeassistant.controller;

import com.aicodeassistant.engine.CompactService;
import com.aicodeassistant.llm.LlmProviderRegistry;
import com.aicodeassistant.permission.PermissionModeManager;
import com.aicodeassistant.run.RunEnvelopeRepository;
import com.aicodeassistant.service.ProjectWorkspaceService;
import com.aicodeassistant.service.PublicMessageProjection;
import com.aicodeassistant.session.SessionExecutionGate;
import com.aicodeassistant.session.SessionManager;
import com.aicodeassistant.websocket.WebSocketSessionManager;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 删除会话二次确认验证码 — 后端强制校验。
 * 配置 zhikun.delete-confirm-code 后，删除必须携带匹配的
 * X-Delete-Confirm-Code 请求头，否则 403 且不删除会话。
 */
class SessionControllerDeleteTest {

    @Test
    void rejectsDeleteWhenConfirmCodeMissing() {
        SessionManager sessions = mock(SessionManager.class);
        SessionExecutionGate gate = mock(SessionExecutionGate.class);
        SessionController controller = controller(sessions, gate, "secret");

        var response = controller.deleteSession("s1", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("success", false);
        verify(sessions, never()).deleteSession(any());
        org.mockito.Mockito.verifyNoInteractions(gate);
    }

    @Test
    void rejectsDeleteWhenConfirmCodeWrong() {
        SessionManager sessions = mock(SessionManager.class);
        SessionExecutionGate gate = mock(SessionExecutionGate.class);
        SessionController controller = controller(sessions, gate, "secret");

        var response = controller.deleteSession("s1", "wrong");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("success", false);
        verify(sessions, never()).deleteSession(any());
        org.mockito.Mockito.verifyNoInteractions(gate);
    }

    @Test
    void deletesSessionWhenConfirmCodeMatches() {
        SessionManager sessions = mock(SessionManager.class);
        SessionExecutionGate gate = mock(SessionExecutionGate.class);
        when(gate.tryAcquire(any(), eq("s1")))
                .thenReturn(mock(SessionExecutionGate.Token.class));
        SessionController controller = controller(sessions, gate, "secret");

        var response = controller.deleteSession("s1", "secret");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("success", true);
        verify(sessions).deleteSession("s1");
    }

    @Test
    void deletesSessionWithoutCodeWhenNotConfigured() {
        SessionManager sessions = mock(SessionManager.class);
        SessionExecutionGate gate = mock(SessionExecutionGate.class);
        when(gate.tryAcquire(any(), eq("s1")))
                .thenReturn(mock(SessionExecutionGate.Token.class));
        SessionController controller = controller(sessions, gate, "");

        var response = controller.deleteSession("s1", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("success", true);
        verify(sessions).deleteSession("s1");
    }

    private static SessionController controller(
            SessionManager sessions,
            SessionExecutionGate gate,
            String deleteConfirmCode) {
        return new SessionController(
                sessions,
                mock(CompactService.class),
                mock(LlmProviderRegistry.class),
                mock(SimpMessagingTemplate.class),
                mock(WebSocketSessionManager.class),
                mock(ProjectWorkspaceService.class),
                mock(PermissionModeManager.class),
                new PublicMessageProjection(),
                mock(RunEnvelopeRepository.class),
                gate,
                deleteConfirmCode);
    }
}
