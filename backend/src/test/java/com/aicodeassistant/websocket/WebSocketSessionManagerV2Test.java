package com.aicodeassistant.websocket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WebSocketSessionManagerV2Test {
    private final WebSocketSessionManager manager = new WebSocketSessionManager();

    @AfterEach void close() { manager.destroy(); }

    private void bind(String principal, String session) {
        manager.registerTransport(principal, principal);
        manager.bindSession(principal, principal, session, 1);
    }

    @Test
    void disconnectRemovesOnlyMatchingTransport() {
        bind("tab-a", "session-1");
        bind("tab-b", "session-1");

        assertThat(manager.getPrincipalsForSession("session-1"))
                .containsExactlyInAnyOrder("tab-a", "tab-b");
        manager.disconnectTransport("tab-a");

        assertThat(manager.isSessionOnline("session-1")).isTrue();
        assertThat(manager.getPrincipalsForSession("session-1")).containsExactly("tab-b");
    }

    @Test
    void lateDisconnectForOldTransportCannotRemoveNewTransport() {
        bind("old-tab", "session-1");
        bind("new-tab", "session-1");
        manager.disconnectTransport("old-tab");
        manager.disconnectTransport("old-tab");

        assertThat(manager.getPrincipalForSession("session-1")).isEqualTo("new-tab");
    }
}
