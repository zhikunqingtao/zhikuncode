package com.aicodeassistant.controller;

import com.aicodeassistant.engine.CompactService;
import com.aicodeassistant.llm.LlmProviderRegistry;
import com.aicodeassistant.model.SessionSummary;
import com.aicodeassistant.permission.PermissionModeManager;
import com.aicodeassistant.run.RunEnvelope;
import com.aicodeassistant.run.RunEnvelopeRepository;
import com.aicodeassistant.service.ProjectWorkspaceService;
import com.aicodeassistant.service.PublicMessageProjection;
import com.aicodeassistant.session.SessionManager;
import com.aicodeassistant.session.SessionPage;
import com.aicodeassistant.websocket.WebSocketSessionManager;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 会话列表 running 标记语义 — 仅 RunStatus.RUNNING 视为运行中。
 * 回归场景：等待审批（WAITING_INTERACTION）的会话切到后台后不得误标"运行中"。
 */
class SessionControllerListTest {

    @Test
    void marksOnlyRunningRunsAsRunning() {
        SessionManager sessions = mock(SessionManager.class);
        RunEnvelopeRepository runs = mock(RunEnvelopeRepository.class);
        SessionSummary summary = new SessionSummary(
                "s1", "title", null, "model", "/tmp", 3, 0.0, Instant.now(), Instant.now());
        when(sessions.listSessionsPaginated(true, null, 20, null))
                .thenReturn(new SessionPage(List.of(summary), false, null));

        SessionController controller = controller(sessions, runs);

        when(runs.findLatestRootBySession("s1"))
                .thenReturn(Optional.of(runWithStatus(RunEnvelope.RunStatus.RUNNING)));
        var runningBody = controller.listSessions(null, 20, null, null).getBody();
        assertNotNull(runningBody);
        assertTrue(runningBody.sessions().getFirst().running(),
                "RUNNING 状态的会话应标记为运行中");

        when(runs.findLatestRootBySession("s1"))
                .thenReturn(Optional.of(runWithStatus(RunEnvelope.RunStatus.WAITING_INTERACTION)));
        var waitingBody = controller.listSessions(null, 20, null, null).getBody();
        assertNotNull(waitingBody);
        assertFalse(waitingBody.sessions().getFirst().running(),
                "等待审批（WAITING_INTERACTION）的会话不得标记为运行中");

        when(runs.findLatestRootBySession("s1")).thenReturn(Optional.empty());
        var noRunBody = controller.listSessions(null, 20, null, null).getBody();
        assertNotNull(noRunBody);
        assertFalse(noRunBody.sessions().getFirst().running(),
                "无 run 记录的会话不得标记为运行中");
    }

    private static RunEnvelope runWithStatus(RunEnvelope.RunStatus status) {
        Instant now = Instant.now();
        return new RunEnvelope("r1", "s1", null, status, "agent", "model", null,
                now, null, null, 0, 0.0, 0, 0, null, now, now, 0,
                null, null, RunEnvelope.VerificationStatus.NOT_REQUESTED, null, null);
    }

    private static SessionController controller(SessionManager sessions, RunEnvelopeRepository runs) {
        return new SessionController(
                sessions,
                mock(CompactService.class),
                mock(LlmProviderRegistry.class),
                mock(SimpMessagingTemplate.class),
                mock(WebSocketSessionManager.class),
                mock(ProjectWorkspaceService.class),
                mock(PermissionModeManager.class),
                new PublicMessageProjection(),
                runs,
                mock(com.aicodeassistant.session.SessionExecutionGate.class));
    }
}
