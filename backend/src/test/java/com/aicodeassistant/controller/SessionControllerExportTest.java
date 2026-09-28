package com.aicodeassistant.controller;

import com.aicodeassistant.engine.CompactService;
import com.aicodeassistant.llm.LlmProviderRegistry;
import com.aicodeassistant.model.Usage;
import com.aicodeassistant.permission.PermissionModeManager;
import com.aicodeassistant.run.RunEnvelopeRepository;
import com.aicodeassistant.service.ProjectWorkspaceService;
import com.aicodeassistant.service.PublicMessageProjection;
import com.aicodeassistant.session.SessionData;
import com.aicodeassistant.session.SessionExecutionGate;
import com.aicodeassistant.session.SessionManager;
import com.aicodeassistant.websocket.WebSocketSessionManager;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SessionControllerExportTest {

    @Test
    void markdownExportKeepsAttachmentDispositionAndPlainTextBody() {
        SessionController controller = controllerWithSession("session-1");

        ResponseEntity<byte[]> response = controller.exportSession("session-1", "markdown");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_PLAIN);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"session-session-1.md\";"
                        + " filename*=UTF-8''session-session-1.md");
        assertThat(new String(response.getBody(), StandardCharsets.UTF_8))
                .startsWith("# Session: ");
    }

    @Test
    void jsonExportKeepsJsonContentTypeAndAttachmentDisposition() {
        SessionController controller = controllerWithSession("session-2");

        ResponseEntity<byte[]> response = controller.exportSession("session-2", "json");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"session-session-2.json\";"
                        + " filename*=UTF-8''session-session-2.json");
    }

    private static SessionController controllerWithSession(String sessionId) {
        SessionManager sessions = mock(SessionManager.class);
        when(sessions.loadSession(sessionId))
                .thenReturn(Optional.of(session(sessionId)));
        PublicMessageProjection projection = mock(PublicMessageProjection.class);
        when(projection.project(any(SessionData.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        return new SessionController(
                sessions,
                mock(CompactService.class),
                mock(LlmProviderRegistry.class),
                mock(SimpMessagingTemplate.class),
                mock(WebSocketSessionManager.class),
                mock(ProjectWorkspaceService.class),
                mock(PermissionModeManager.class),
                projection,
                mock(RunEnvelopeRepository.class),
                mock(SessionExecutionGate.class),
                "");
    }

    private static SessionData session(String id) {
        Instant now = Instant.now();
        return new SessionData(
                id, "model", "/tmp/workspace", "Title", "active",
                List.of(), Map.of(), Usage.zero(), 0.0, null, now, now);
    }
}
