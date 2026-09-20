package com.aicodeassistant.command.impl;

import com.aicodeassistant.command.CommandContext;
import com.aicodeassistant.model.PermissionMode;
import com.aicodeassistant.permission.PermissionModeManager;
import com.aicodeassistant.session.SessionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.mockito.Mockito.*;

class ClearCommandTest {
    @ParameterizedTest @EnumSource(PermissionMode.class)
    void replacementInheritsPersistedPermission(PermissionMode mode) {
        var sessions = mock(SessionManager.class);
        var permissions = mock(PermissionModeManager.class);
        when(permissions.getMode("old")).thenReturn(mode);
        new ClearCommand(sessions, permissions).execute("", CommandContext.of("old", "/workspace", "model", null));
        verify(sessions).createSession("model", "/workspace", mode);
        verifyNoMoreInteractions(sessions);
    }
    @Test void unreadablePermissionDoesNotCreateAnElevatedReplacement() {
        var sessions = mock(SessionManager.class);
        var permissions = mock(PermissionModeManager.class);
        when(permissions.getMode("old")).thenThrow(new IllegalStateException("database unavailable"));
        new ClearCommand(sessions, permissions).execute("", CommandContext.of("old", "/workspace", "model", null));
        verifyNoInteractions(sessions);
    }
}
