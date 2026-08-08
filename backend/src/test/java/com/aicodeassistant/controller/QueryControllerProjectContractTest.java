package com.aicodeassistant.controller;

import com.aicodeassistant.engine.QueryEngine;
import com.aicodeassistant.engine.TokenCounter;
import com.aicodeassistant.exception.RequestValidationException;
import com.aicodeassistant.llm.LlmProviderRegistry;
import com.aicodeassistant.llm.ModelRegistry;
import com.aicodeassistant.model.Usage;
import com.aicodeassistant.model.PermissionMode;
import com.aicodeassistant.permission.PermissionModeManager;
import com.aicodeassistant.prompt.EffectiveSystemPromptBuilder;
import com.aicodeassistant.service.ProjectWorkspaceService;
import com.aicodeassistant.session.SessionData;
import com.aicodeassistant.session.SessionManager;
import com.aicodeassistant.tool.ToolRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class QueryControllerProjectContractTest {

    @TempDir
    Path workspace;

    @Test
    void queryAndConversationContractsAcceptAutoApprove() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        QueryController.QueryRequest query = mapper.readValue("""
                {
                  "prompt": "hello",
                  "permissionMode": "AUTO_APPROVE"
                }
                """, QueryController.QueryRequest.class);
        QueryController.ConversationRequest conversation = mapper.readValue("""
                {
                  "sessionId": "session-1",
                  "prompt": "hello",
                  "permissionMode": "AUTO_APPROVE"
                }
                """, QueryController.ConversationRequest.class);

        assertThat(query.permissionMode()).isEqualTo(PermissionMode.AUTO_APPROVE);
        assertThat(conversation.permissionMode()).isEqualTo(PermissionMode.AUTO_APPROVE);
    }

    @Test
    void rejectsClientWorkingDirectoryBeforeSessionResolution()
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SessionManager sessions = mock(SessionManager.class);
        ProjectWorkspaceService projects =
                mock(ProjectWorkspaceService.class);
        QueryController.QueryRequest request = mapper.readValue("""
                {
                  "prompt": "hello",
                  "workingDirectory": "/client-controlled"
                }
                """, QueryController.QueryRequest.class);

        assertThatThrownBy(() ->
                ReflectionTestUtils.invokeMethod(
                        controller(mapper, sessions, projects),
                        "resolveSession", request))
                .isInstanceOfSatisfying(
                        RequestValidationException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo(
                                        "QUERY_WORKING_DIRECTORY_UNSUPPORTED"));
        verifyNoInteractions(sessions, projects);
    }

    @Test
    void projectCreatesSessionWithCanonicalWorkspace()
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SessionManager sessions = mock(SessionManager.class);
        ProjectWorkspaceService projects =
                mock(ProjectWorkspaceService.class);
        LlmProviderRegistry providers =
                mock(LlmProviderRegistry.class);
        SessionData created = session(
                "session-1", workspace.toString());
        when(projects.resolveWorkspace("project-1"))
                .thenReturn(workspace);
        when(sessions.createSession(
                "model-1", workspace.toString()))
                .thenReturn("session-1");
        when(sessions.loadSession("session-1"))
                .thenReturn(Optional.of(created));
        QueryController.QueryRequest request = mapper.readValue("""
                {
                  "prompt": "hello",
                  "model": "model-1",
                  "projectId": "project-1"
                }
                """, QueryController.QueryRequest.class);

        SessionData resolved = ReflectionTestUtils.invokeMethod(
                controller(mapper, sessions, providers, projects),
                "resolveSession", request);

        assertThat(resolved).isSameAs(created);
        verify(sessions).createSession(
                "model-1", workspace.toString());
    }

    @Test
    void existingSessionUsesItsSavedRootAndRejectsProjectOverride()
            throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        SessionManager sessions = mock(SessionManager.class);
        ProjectWorkspaceService projects =
                mock(ProjectWorkspaceService.class);
        SessionData existing = session(
                "session-1", workspace.toString());
        when(sessions.loadSession("session-1"))
                .thenReturn(Optional.of(existing));
        QueryController controller =
                controller(mapper, sessions, projects);

        QueryController.QueryRequest existingRequest =
                mapper.readValue("""
                        {
                          "prompt": "hello",
                          "sessionId": "session-1"
                        }
                        """, QueryController.QueryRequest.class);
        SessionData resolved = ReflectionTestUtils.invokeMethod(
                controller, "resolveSession", existingRequest);
        assertThat(resolved.workingDir())
                .isEqualTo(workspace.toString());
        verify(projects).requireCurrentBinding(
                workspace.toString());

        QueryController.QueryRequest conflicting =
                mapper.readValue("""
                        {
                          "prompt": "hello",
                          "sessionId": "session-1",
                          "projectId": "project-2"
                        }
                        """, QueryController.QueryRequest.class);
        assertThatThrownBy(() ->
                ReflectionTestUtils.invokeMethod(
                        controller, "resolveSession", conflicting))
                .isInstanceOfSatisfying(
                        RequestValidationException.class,
                        error -> assertThat(error.getCode())
                                .isEqualTo(
                                        "QUERY_PROJECT_WITH_SESSION_UNSUPPORTED"));
    }

    private static QueryController controller(
            ObjectMapper mapper,
            SessionManager sessions,
            ProjectWorkspaceService projects) {
        return controller(
                mapper, sessions,
                mock(LlmProviderRegistry.class), projects);
    }

    private static QueryController controller(
            ObjectMapper mapper,
            SessionManager sessions,
            LlmProviderRegistry providers,
            ProjectWorkspaceService projects) {
        return new QueryController(
                mock(QueryEngine.class),
                mock(ToolRegistry.class),
                sessions,
                providers,
                mock(TokenCounter.class),
                mapper,
                mock(EffectiveSystemPromptBuilder.class),
                mock(PermissionModeManager.class),
                mock(ModelRegistry.class),
                projects);
    }

    private static SessionData session(
            String id, String workingDirectory) {
        Instant now = Instant.now();
        return new SessionData(
                id, "model", workingDirectory,
                null, "active", List.of(), Map.of(),
                Usage.zero(), 0.0, null, now, now);
    }
}
