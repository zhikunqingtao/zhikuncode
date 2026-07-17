package com.aicodeassistant.controller;

import com.aicodeassistant.interaction.DurableInteractionService;
import com.aicodeassistant.interaction.InteractionRequest;
import com.aicodeassistant.security.SessionAccessAuthorizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

class InteractionControllerTest {
    private DurableInteractionService interactions;
    private SessionAccessAuthorizer access;
    private InteractionController controller;

    @BeforeEach
    void setUp() {
        interactions = mock(DurableInteractionService.class);
        access = mock(SessionAccessAuthorizer.class);
        controller = new InteractionController(interactions, access, new ObjectMapper());
    }

    @Test
    void pendingRequiresMatchingOnlineSession() {
        assertThat(controller.pending("s1", "other").getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(interactions);
    }

    @Test
    void serverRejectsScopeNotAdvertisedByPermissionInteraction() {
        when(access.canAccessSession("s1", "s1")).thenReturn(true);
        when(interactions.findById("i1")).thenReturn(pendingPermission());

        var response = controller.decide("i1", "s1",
                new InteractionController.DecisionRequest(3, "allow", null, true, "workspace"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(interactions, never()).decideRequest(anyString(), anyLong(), any(), any(), anyString());
    }

    @Test
    void winningDecisionUsesExpectedVersionAndDatabaseResult() {
        InteractionRequest pending = pendingPermission();
        InteractionRequest answered = withStatus(pending, InteractionRequest.Status.ANSWERED, 4);
        when(access.canAccessSession("s1", "s1")).thenReturn(true);
        when(interactions.findById("i1")).thenReturn(pending);
        when(interactions.decideRequest(eq("i1"), eq(3L), eq(InteractionRequest.Status.ANSWERED),
                any(), eq("user_rest"))).thenReturn(answered);

        var response = controller.decide("i1", "s1",
                new InteractionController.DecisionRequest(3, "allow", null, true, "session"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(interactions).decideRequest(eq("i1"), eq(3L), eq(InteractionRequest.Status.ANSWERED),
                any(), eq("user_rest"));
    }

    @Test
    void permissionResponseCannotSmuggleAnUnvalidatedRememberScope() {
        InteractionRequest pending = pendingPermission();
        InteractionRequest answered = withStatus(pending, InteractionRequest.Status.ANSWERED, 4);
        when(access.canAccessSession("s1", "s1")).thenReturn(true);
        when(interactions.findById("i1")).thenReturn(pending);
        when(interactions.decideRequest(eq("i1"), eq(3L), eq(InteractionRequest.Status.ANSWERED),
                any(), eq("user_rest"))).thenReturn(answered);

        controller.decide("i1", "s1", new InteractionController.DecisionRequest(
                3, "allow", java.util.Map.of("remember", true, "scope", "workspace"),
                false, null));

        ArgumentCaptor<Object> response = ArgumentCaptor.forClass(Object.class);
        verify(interactions).decideRequest(eq("i1"), eq(3L),
                eq(InteractionRequest.Status.ANSWERED), response.capture(), eq("user_rest"));
        @SuppressWarnings("unchecked")
        var saved = (java.util.Map<String, Object>) response.getValue();
        assertThat(saved).containsEntry("remember", false).containsEntry("scope", "once");
    }

    private static InteractionRequest pendingPermission() {
        Instant now = Instant.now();
        return new InteractionRequest("i1", "tool-1", "s1", "r1",
                InteractionRequest.Type.PERMISSION, InteractionRequest.Status.PENDING,
                "{}", "[\"allow\",\"deny\"]", "[\"session\"]", null,
                now, now.plusSeconds(30), now, now.plusSeconds(5), now,
                now.plusSeconds(300), null, null, "direct", null,
                1, 1, "transport-1", now, 3);
    }

    private static InteractionRequest withStatus(InteractionRequest r, InteractionRequest.Status status,
                                                  long version) {
        return new InteractionRequest(r.interactionId(), r.correlationKey(), r.sessionId(), r.runId(),
                r.type(), status, r.promptJson(), r.allowedDecisionsJson(), r.scopeOptionsJson(),
                "{\"decision\":\"allow\"}", r.createdAt(), r.deliveryWindowEndsAt(),
                r.firstDispatchedAt(), r.deliveryAckDeadlineAt(), r.receivedAt(), r.decisionDeadlineAt(),
                Instant.now(), null, r.source(), r.childSessionId(), r.deliveryGeneration(), r.dispatchAttempts(),
                r.lastTransportId(), Instant.now(), version);
    }
}
