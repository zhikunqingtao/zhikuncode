package com.aicodeassistant.permission;

import com.aicodeassistant.config.database.V002_InitProjectSchema;
import com.aicodeassistant.config.database.V025_AddSessionPermissionMode;
import com.aicodeassistant.model.PermissionMode;
import com.aicodeassistant.websocket.WebSocketController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class PermissionModeManagerTest {
    @TempDir Path temp;
    private JdbcTemplate jdbc;

    private JdbcTemplate openDatabase() {
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + temp.resolve("permissions.db"));
        return new JdbcTemplate(source);
    }

    private PermissionModeManager manager(WebSocketController pusher, JdbcTemplate database) {
        return new PermissionModeManager(pusher, database,
                new DataSourceTransactionManager(database.getDataSource()));
    }

    @BeforeEach
    void setUp() {
        jdbc = openDatabase();
        new V002_InitProjectSchema(jdbc).execute();
        new V025_AddSessionPermissionMode(jdbc).execute();
        for (String id : java.util.List.of("session", "other")) {
            jdbc.update("INSERT INTO sessions(id,model,working_dir,created_at,updated_at) VALUES(?,?,?,?,?)",
                    id, "kimi-k3", "/workspace", "2026-09-20T00:00:00Z", "2026-09-20T00:00:00Z");
        }
    }

    @ParameterizedTest
    @EnumSource(PermissionMode.class)
    void selectionSurvivesRestartAndRemainsSessionScoped(PermissionMode mode) {
        PermissionModeManager beforeRestart = manager(null, jdbc);
        beforeRestart.setMode("session", PermissionMode.AUTO_APPROVE);
        beforeRestart.setMode("session", mode);

        // Reopen the file through a new connection source and a fresh service instance.
        PermissionModeManager afterRestart = manager(null, openDatabase());
        assertThat(afterRestart.getMode("session")).isEqualTo(mode);
        assertThat(afterRestart.getMode("other")).isEqualTo(PermissionMode.DEFAULT);
        assertThat(jdbc.queryForObject("SELECT model FROM sessions WHERE id='session'", String.class))
                .isEqualTo("kimi-k3");
    }

    @Test
    void existingReadersObserveSavedChangesWithoutStaleCaches() {
        PermissionModeManager first = manager(null, jdbc);
        PermissionModeManager second = manager(null, openDatabase());
        assertThat(first.getMode("session")).isEqualTo(PermissionMode.DEFAULT);
        second.setMode("session", PermissionMode.AUTO_APPROVE);
        assertThat(first.getMode("session")).isEqualTo(PermissionMode.AUTO_APPROVE);
        second.setMode("session", PermissionMode.DEFAULT);
        assertThat(first.getMode("session")).isEqualTo(PermissionMode.DEFAULT);
    }

    @Test void confirmationCarriesRequestIdentityOnlyAfterCommit() {
        var pusher = mock(WebSocketController.class);
        var manager = manager(pusher, jdbc);
        new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())).executeWithoutResult(status -> {
            manager.setMode("session", PermissionMode.PLAN, "request-2");
            verifyNoInteractions(pusher);
        });
        verify(pusher).pushToUser(eq("session"), eq("permission_mode_changed"),
                eq(Map.of("mode", "PLAN", "previous", "DEFAULT", "requestId", "request-2")));
    }

    @Test
    void failedSaveKeepsPreviousModeAndDoesNotConfirmTheChange() {
        WebSocketController pusher = mock(WebSocketController.class);
        PermissionModeManager manager = manager(pusher, jdbc);
        jdbc.execute("""
                CREATE TRIGGER reject_permission_update BEFORE UPDATE OF permission_mode ON sessions
                BEGIN SELECT RAISE(ABORT, 'simulated write failure'); END
                """);

        assertThatThrownBy(() -> manager.setMode("session", PermissionMode.AUTO_APPROVE))
                .isInstanceOf(DataAccessException.class);
        assertThat(manager.getMode("session")).isEqualTo(PermissionMode.DEFAULT);
        verifyNoInteractions(pusher);
    }

    @Test
    void missingOrDeletedSessionsCannotRetainElevatedPermissions() {
        WebSocketController pusher = mock(WebSocketController.class);
        PermissionModeManager manager = manager(pusher, jdbc);
        assertThatThrownBy(() -> manager.getMode("missing")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.setMode("missing", PermissionMode.AUTO_APPROVE))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(pusher);
        manager.setMode("session", PermissionMode.AUTO_APPROVE);
        jdbc.update("DELETE FROM sessions WHERE id='session'");
        assertThatThrownBy(() -> manager.getMode("session")).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> manager.clearSession("session")).doesNotThrowAnyException();
    }

    @Test
    void clearingSelectionPersistsTheDefault() {
        PermissionModeManager manager = manager(null, jdbc);
        manager.setMode("session", PermissionMode.AUTO_APPROVE);
        manager.clearSession("session");
        assertThat(manager(null, openDatabase()).getMode("session"))
                .isEqualTo(PermissionMode.DEFAULT);
    }

    @Test
    void rolledBackTransactionNeitherPersistsNorConfirmsSelection() {
        WebSocketController pusher = mock(WebSocketController.class);
        PermissionModeManager manager = manager(pusher, jdbc);
        TransactionTemplate outer = new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource()));
        outer.executeWithoutResult(status -> {
            manager.setMode("session", PermissionMode.AUTO_APPROVE);
            verifyNoInteractions(pusher);
            status.setRollbackOnly();
        });
        assertThat(manager.getMode("session")).isEqualTo(PermissionMode.DEFAULT);
        assertThat(manager(null, openDatabase()).getMode("session")).isEqualTo(PermissionMode.DEFAULT);
        verifyNoInteractions(pusher);
    }

    @Test
    void autoApproveIsCommittedBeforeBestEffortConfirmation() {
        WebSocketController pusher = mock(WebSocketController.class);
        PermissionModeManager manager = manager(pusher, jdbc);

        org.mockito.Mockito.doAnswer(invocation -> {
            assertThat(manager(null, openDatabase()).getMode("session"))
                    .isEqualTo(PermissionMode.AUTO_APPROVE);
            return null;
        }).when(pusher).pushToUser(eq("session"), eq("permission_mode_changed"),
                org.mockito.ArgumentMatchers.any());
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
        PermissionModeManager manager = manager(pusher, jdbc);

        assertThatCode(() -> manager.setMode("session", PermissionMode.AUTO_APPROVE))
                .doesNotThrowAnyException();

        assertThat(manager.getMode("session")).isEqualTo(PermissionMode.AUTO_APPROVE);
    }

    @Test
    void sameModeRequestStillConfirmsSavedValue() {
        WebSocketController pusher = mock(WebSocketController.class);
        PermissionModeManager manager = manager(pusher, jdbc);

        manager.setMode("session", PermissionMode.DEFAULT);

        assertThat(manager.getMode("session")).isEqualTo(PermissionMode.DEFAULT);
        verify(pusher).pushToUser(eq("session"), eq("permission_mode_changed"),
                eq(Map.of("mode", "DEFAULT", "previous", "DEFAULT")));
    }
}
