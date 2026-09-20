package com.aicodeassistant.authorization;

import com.aicodeassistant.interaction.*;
import com.aicodeassistant.model.*;
import com.aicodeassistant.permission.PermissionModeManager;
import com.aicodeassistant.run.RunControlService;
import com.aicodeassistant.service.ProjectWorkspaceService;
import com.aicodeassistant.tool.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AuthorizationModeChangeTest {
    final PermissionModeManager modes = mock(PermissionModeManager.class);
    final PermissionGrantRepository grants = mock(PermissionGrantRepository.class);
    final DurableInteractionService interactions = mock(DurableInteractionService.class);
    final AuthorizationSubject subject = new AuthorizationSubject("root-session", "root-run", "child-run", "workspace", Path.of("/workspace"));
    final AuthorizationService service = new AuthorizationService(mock(AuthorizationSubjectResolver.class),
            mock(OperationAnalyzerRegistry.class), grants, interactions, modes, mock(RunControlService.class),
            new ObjectMapper(), mock(ProjectWorkspaceService.class));
    final PermissionGrantRepository.Match grant = new PermissionGrantRepository.Match("grant", null, PermissionScope.SESSION);

    AuthorizedOperation authorize(RiskClass risk) {
        var op = new OperationDescriptor(1, "Bash", "invoke", "input", "bash-v2", List.of(EffectClass.PROCESS),
                List.of(), List.of(), List.of(), risk, "hash", "summary");
        Tool tool = mock(Tool.class);
        when(tool.getName()).thenReturn("Bash");
        ToolInput input = ToolInput.from(Map.of());
        try (var frozen = new FrozenToolInputFactory(new ObjectMapper(), 1024, 4096).freeze("Bash", input)) {
            return service.authorizePrepared(tool, frozen, input, ToolUseContext.of("/workspace", "child-session"),
                    new PreparedOperation(subject, op, "attempt"));
        }
    }
    void answered(boolean remembered) {
        InteractionRequest interaction = mock(InteractionRequest.class);
        when(interaction.interactionId()).thenReturn("interaction");
        when(interaction.status()).thenReturn(InteractionRequest.Status.ANSWERED);
        when(interaction.responseJson()).thenReturn("{\"operationHash\":\"hash\",\"remember\":" + remembered + "}");
        when(interactions.findByCorrelationKey(any(), any())).thenReturn(interaction);
        when(interactions.findById("interaction")).thenReturn(interaction);
    }
    @Test void childUsesLatestRootModeAndPlanCannotUseOldGrant() {
        when(modes.getMode("root-session")).thenReturn(PermissionMode.AUTO_APPROVE, PermissionMode.PLAN);
        when(grants.findMatch(any(), any())).thenReturn(grant);
        assertThat(authorize(RiskClass.GUARDED).reasonCode()).isEqualTo("AUTO_APPROVE");
        assertThatThrownBy(() -> authorize(RiskClass.GUARDED)).isInstanceOf(AuthorizationException.class).hasMessageContaining("Plan mode");
        verify(modes, times(2)).getMode("root-session");
        verify(grants, never()).findMatch(any(), any());
    }
    @Test void planRejectsBothOnceAndRememberedApprovalAfterWaiting() {
        for (boolean remembered : List.of(false, true)) {
            when(modes.getMode("root-session")).thenReturn(PermissionMode.DEFAULT, PermissionMode.PLAN);
            answered(remembered);
            assertThatThrownBy(() -> authorize(RiskClass.GUARDED)).isInstanceOf(AuthorizationException.class)
                    .hasMessageContaining("current session permission");
        }
    }
    @Test void dontAskRejectsOnceAndHighButHonorsValidRememberedGrant() {
        answered(false);
        when(modes.getMode("root-session")).thenReturn(PermissionMode.DEFAULT, PermissionMode.DONT_ASK);
        assertThatThrownBy(() -> authorize(RiskClass.GUARDED)).isInstanceOf(AuthorizationException.class);
        when(modes.getMode("root-session")).thenReturn(PermissionMode.DEFAULT, PermissionMode.DONT_ASK);
        assertThatThrownBy(() -> authorize(RiskClass.HIGH)).isInstanceOf(AuthorizationException.class);
        answered(true);
        when(modes.getMode("root-session")).thenReturn(PermissionMode.DEFAULT, PermissionMode.DONT_ASK);
        when(grants.findMatch(any(), any())).thenReturn(null, grant, grant);
        assertThat(authorize(RiskClass.GUARDED).grantId()).isEqualTo("grant");
    }
    @Test void denialIsNotReversedByLaterAutoApprove() {
        InteractionRequest interaction = mock(InteractionRequest.class);
        when(interaction.interactionId()).thenReturn("interaction");
        when(interaction.status()).thenReturn(InteractionRequest.Status.DENIED);
        when(interactions.findByCorrelationKey(any(), any())).thenReturn(interaction);
        when(interactions.findById("interaction")).thenReturn(interaction);
        when(modes.getMode("root-session")).thenReturn(PermissionMode.DEFAULT, PermissionMode.AUTO_APPROVE);
        assertThatThrownBy(() -> authorize(RiskClass.GUARDED)).isInstanceOf(AuthorizationException.class).hasMessageContaining("DENIED");
        verify(modes).getMode("root-session");
    }
}
