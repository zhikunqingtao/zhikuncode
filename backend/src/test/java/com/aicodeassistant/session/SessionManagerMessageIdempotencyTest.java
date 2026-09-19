package com.aicodeassistant.session;

import com.aicodeassistant.config.database.SqliteConfig;
import com.aicodeassistant.hook.HookService;
import com.aicodeassistant.run.RunExecutionRegistry;
import com.aicodeassistant.run.RunTerminationCoordinator;
import com.aicodeassistant.state.AppStateStore;
import com.aicodeassistant.tool.agent.BackgroundAgentTracker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.DriverManager;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class SessionManagerMessageIdempotencyTest {
    private JdbcTemplate jdbc;
    private SessionManager manager;

    @BeforeEach
    void setUp() throws Exception {
        var ds = new SingleConnectionDataSource(
                DriverManager.getConnection("jdbc:sqlite::memory:"), true);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("""
                CREATE TABLE sessions (id TEXT PRIMARY KEY, model TEXT NOT NULL,
                    working_dir TEXT NOT NULL, status TEXT NOT NULL,
                    created_at TEXT NOT NULL, updated_at TEXT NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE messages (id TEXT PRIMARY KEY, session_id TEXT NOT NULL,
                    role TEXT NOT NULL, content_json TEXT NOT NULL, stop_reason TEXT,
                    input_tokens INTEGER NOT NULL, output_tokens INTEGER NOT NULL,
                    created_at TEXT NOT NULL, seq_num INTEGER NOT NULL, meta_json TEXT)
                """);
        jdbc.update("INSERT INTO sessions VALUES (?,?,?,?,?,?)",
                "s1", "m", ".", "active", "now", "now");
        jdbc.update("INSERT INTO sessions VALUES (?,?,?,?,?,?)",
                "s2", "m", ".", "active", "now", "now");
        manager = new SessionManager(jdbc, new ObjectMapper(), mock(SqliteConfig.class),
                mock(AppStateStore.class), mock(HookService.class),
                mock(SessionSnapshotService.class), mock(BackgroundAgentTracker.class),
                mock(RunExecutionRegistry.class), mock(RunTerminationCoordinator.class));
    }

    @Test
    void identicalUuidIsIdempotentButDifferentContentConflicts() {
        manager.addMessageWithId("m1", "s1", "user", Map.of("b", 2, "a", 1),
                null, 0, 0, Map.of("x", 1, "y", 2));
        manager.addMessageWithId("m1", "s1", "user", Map.of("a", 1, "b", 2),
                null, 0, 0, Map.of("y", 2, "x", 1));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM messages", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> manager.addMessageWithId(
                "m1", "s1", "user", Map.of("a", 9), null, 0, 0, null))
                .isInstanceOf(MessagePersistenceException.class)
                .extracting("code").isEqualTo("MESSAGE_ID_CONFLICT");
        assertThatThrownBy(() -> manager.addMessageWithId(
                "m1", "s2", "user", Map.of("a", 1, "b", 2), null, 0, 0,
                Map.of("x", 1, "y", 2)))
                .isInstanceOf(MessagePersistenceException.class)
                .extracting("code").isEqualTo("MESSAGE_ID_CONFLICT");
    }
}
