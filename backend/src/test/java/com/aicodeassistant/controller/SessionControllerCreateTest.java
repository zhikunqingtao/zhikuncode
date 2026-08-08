package com.aicodeassistant.controller;

import com.aicodeassistant.engine.CompactService;
import com.aicodeassistant.exception.RequestValidationException;
import com.aicodeassistant.llm.LlmProviderRegistry;
import com.aicodeassistant.model.PermissionMode;
import com.aicodeassistant.permission.PermissionModeManager;
import com.aicodeassistant.service.ProjectWorkspaceService;
import com.aicodeassistant.session.SessionManager;
import com.aicodeassistant.websocket.WebSocketSessionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionControllerCreateTest {

    @TempDir
    Path workspace;

    @Test
    void createsSessionFromProjectWorkspaceSnapshot() {
        SessionManager sessions = mock(SessionManager.class);
        LlmProviderRegistry providers =
                mock(LlmProviderRegistry.class);
        ProjectWorkspaceService projects =
                mock(ProjectWorkspaceService.class);
        PermissionModeManager permissionModes =
                mock(PermissionModeManager.class);
        when(projects.resolveWorkspace("project-1"))
                .thenReturn(workspace);
        when(sessions.createSession(
                "model-1", workspace.toString()))
                .thenReturn("session-1");
        SessionController controller =
                controller(sessions, providers, projects, permissionModes);

        var response = controller.createSession(
                new SessionController.CreateSessionRequest(
                        "project-1", null, "model-1",
                        PermissionMode.AUTO_APPROVE, null));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().sessionId())
                .isEqualTo("session-1");
        assertThat(response.getBody().permissionMode())
                .isEqualTo(PermissionMode.AUTO_APPROVE);
        verify(sessions).createSession(
                "model-1", workspace.toString());
        verify(permissionModes).setMode(
                "session-1", PermissionMode.AUTO_APPROVE);
    }

    @Test
    void usesServerDefaultAndRejectsRawDirectory() {
        SessionManager sessions = mock(SessionManager.class);
        LlmProviderRegistry providers =
                mock(LlmProviderRegistry.class);
        ProjectWorkspaceService projects =
                mock(ProjectWorkspaceService.class);
        PermissionModeManager permissionModes =
                mock(PermissionModeManager.class);
        when(providers.getDefaultModel())
                .thenReturn("default-model");
        when(projects.resolveWorkspace(null))
                .thenReturn(workspace);
        when(sessions.createSession(
                "default-model", workspace.toString()))
                .thenReturn("session-default");
        SessionController controller =
                controller(sessions, providers, projects, permissionModes);

        assertThat(controller.createSession(null)
                .getBody())
                .satisfies(body -> {
                    assertThat(body.sessionId()).isEqualTo("session-default");
                    assertThat(body.permissionMode()).isEqualTo(PermissionMode.DEFAULT);
                });
        verify(permissionModes).setMode(
                "session-default", PermissionMode.DEFAULT);
        assertThatThrownBy(() -> controller.createSession(
                new SessionController.CreateSessionRequest(
                        null, "/client/path", null,
                        null, null)))
                .isInstanceOfSatisfying(
                        RequestValidationException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo(
                                        "SESSION_WORKING_DIRECTORY_UNSUPPORTED"));
    }

    private static SessionController controller(
            SessionManager sessions,
            LlmProviderRegistry providers,
            ProjectWorkspaceService projects,
            PermissionModeManager permissionModes) {
        return new SessionController(
                sessions,
                mock(CompactService.class),
                providers,
                mock(SimpMessagingTemplate.class),
                mock(WebSocketSessionManager.class),
                projects,
                permissionModes);
    }
}
