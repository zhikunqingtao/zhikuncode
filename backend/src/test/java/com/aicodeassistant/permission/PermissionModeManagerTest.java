package com.aicodeassistant.permission;

import com.aicodeassistant.model.PermissionMode;
import com.aicodeassistant.websocket.WebSocketController;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PermissionModeManagerTest {

    @Test
    void autoApproveIsCommittedBeforeBestEffortConfirmation() {
        WebSocketController pusher = mock(WebSocketController.class);
        PermissionModeManager manager = new PermissionModeManager(pusher);

        manager.setMode("session", PermissionMode.AUTO_APPROVE);

        assertThat(manager.getMode("session")).isEqualTo(PermissionMode.AUTO_APPROVE);
        verify(pusher).pushToUser(eq("session"), eq("permission_mode_changed"),
                eq(Map.of("mode", "AUTO_APPROVE", "previous", "DEFAULT")));
    }

    @Test
    void confirmationFailureNeverRollsBackOrEscapes() {
        WebSocketController pusher = mock(WebSocketController.class);
        doThrow(new IllegalStateException("transport unavailable"))
                .when(pusher).pushToUser(eq("session"), eq("permission_mode_changed"),
                        eq(Map.of("mode", "AUTO_APPROVE", "previous", "DEFAULT")));
        PermissionModeManager manager = new PermissionModeManager(pusher);

        assertThatCode(() -> manager.setMode("session", PermissionMode.AUTO_APPROVE))
                .doesNotThrowAnyException();

        assertThat(manager.getMode("session")).isEqualTo(PermissionMode.AUTO_APPROVE);
    }

    @Test
    void explicitlySettingImplicitDefaultDoesNotEmitFalseChange() {
        WebSocketController pusher = mock(WebSocketController.class);
        PermissionModeManager manager = new PermissionModeManager(pusher);

        manager.setMode("session", PermissionMode.DEFAULT);

        assertThat(manager.getMode("session")).isEqualTo(PermissionMode.DEFAULT);
        verify(pusher, org.mockito.Mockito.never()).pushToUser(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }
}
