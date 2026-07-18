package com.aicodeassistant.controller;

import com.aicodeassistant.authorization.PermissionGrantRepository;
import com.aicodeassistant.security.SessionAccessAuthorizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PermissionGrantControllerTest {
    private PermissionGrantRepository grants;
    private SessionAccessAuthorizer access;
    private PermissionGrantController controller;

    @BeforeEach
    void setUp() {
        grants = mock(PermissionGrantRepository.class);
        access = mock(SessionAccessAuthorizer.class);
        controller = new PermissionGrantController(grants, access);
    }

    @Test
    void listIsRestrictedToAnExistingAssertedSession() {
        when(access.canAccessSession("session-a", "session-a")).thenReturn(true);
        when(grants.listActiveForSession("session-a", 20)).thenReturn(List.of());

        assertThat(controller.listActive("session-a", 20).get("grants")).isEmpty();
        verify(grants).listActiveForSession("session-a", 20);
    }

    @Test
    void listRejectsUnknownSessionBeforeReadingGrantData() {
        when(access.canAccessSession("missing", "missing")).thenReturn(false);

        assertThatThrownBy(() -> controller.listActive("missing", 20))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403 FORBIDDEN");
        verify(grants, never()).listActiveForSession("missing", 20);
    }

    @Test
    void revokeCannotCrossTheSessionAuthorizationBoundary() {
        when(access.canAccessSession("missing", "missing")).thenReturn(false);

        assertThat(controller.revoke("grant-1", "missing").getStatusCode().value()).isEqualTo(403);
        verify(grants, never()).revokeForSession("grant-1", "missing");
    }

    @Test
    void revokeUsesTheSessionScopedStoreOperation() {
        when(access.canAccessSession("session-a", "session-a")).thenReturn(true);
        when(grants.revokeForSession("grant-1", "session-a")).thenReturn(1);

        assertThat(controller.revoke("grant-1", "session-a").getStatusCode().value()).isEqualTo(204);
        verify(grants).revokeForSession("grant-1", "session-a");
    }
}
