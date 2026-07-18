package com.aicodeassistant.run;

import com.aicodeassistant.config.database.DatabaseResolver;
import com.aicodeassistant.config.database.SqliteConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunControlServiceCasTest {
    @TempDir Path temp;
    private SqliteConfig sqlite;

    @AfterEach void close() { if (sqlite != null) sqlite.destroy(); }

    @Test
    void concurrentTerminalWritersProduceExactlyOneTerminal() throws Exception {
        DatabaseResolver resolver = new DatabaseResolver("", temp.toString());
        sqlite = new SqliteConfig(resolver);
        var dataSource = sqlite.getProjectDataSource(Path.of("ignored"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);
        jdbc.update("INSERT INTO sessions(id) VALUES('s1')");
        RunControlService service = new RunControlService(jdbc, sqlite, resolver,
                new DataSourceTransactionManager(dataSource), new ObjectMapper());
        RunEnvelope run = service.start("s1", null, "main", "known");

        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var complete = pool.submit(() -> { start.await(); return service.complete(run.id(), 1, 0, 1); });
            var cancel = pool.submit(() -> { start.await(); return service.cancel(run.id()); });
            start.countDown();
            List<RunControlService.TransitionResult> results = List.of(complete.get(), cancel.get());
            assertThat(results).contains(RunControlService.TransitionResult.APPLIED);
            assertThat(results.stream().filter(r -> r == RunControlService.TransitionResult.APPLIED)).hasSize(1);
        }

        String status = jdbc.queryForObject("SELECT status FROM run_envelopes WHERE id=?", String.class, run.id());
        assertThat(status).isIn("completed", "cancelled");
        Integer terminals = jdbc.queryForObject(
                "SELECT COUNT(*) FROM run_event_log WHERE run_id=? AND event_type='run_status_changed'",
                Integer.class, run.id());
        assertThat(terminals).isEqualTo(1);
    }

    @Test
    void rootAndChildRunCreationValidateTheirTrustedPersistenceAnchors() {
        DatabaseResolver resolver = new DatabaseResolver("", temp.toString());
        sqlite = new SqliteConfig(resolver);
        var dataSource = sqlite.getProjectDataSource(Path.of("ignored"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);
        RunControlService service = new RunControlService(jdbc, sqlite, resolver,
                new DataSourceTransactionManager(dataSource), new ObjectMapper());

        assertThatThrownBy(() -> service.start("missing", null, "main", "known"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RUN_ROOT_SESSION_NOT_FOUND");

        jdbc.update("INSERT INTO sessions(id) VALUES('s1')");
        RunEnvelope root = service.start("s1", null, "main", "known");
        assertThat(service.start("synthetic-child", root.id(), "subagent", "known").parentRunId())
                .isEqualTo(root.id());
        assertThatThrownBy(() -> service.start("synthetic-child", "missing", "subagent", "known"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("RUN_PARENT_NOT_FOUND");
    }

    private static void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE sessions(id TEXT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE run_envelopes(id TEXT PRIMARY KEY,session_id TEXT NOT NULL,parent_run_id TEXT,status TEXT NOT NULL,version INTEGER NOT NULL DEFAULT 0,agent_type TEXT,model TEXT NOT NULL,prompt_hash TEXT,started_at TEXT NOT NULL,finished_at TEXT,terminal_at TEXT,exit_reason TEXT,requested_exit_reason TEXT,verification_status TEXT NOT NULL,waiting_reason TEXT,abort_reason TEXT,total_tokens INTEGER NOT NULL,total_cost_usd REAL NOT NULL,tool_call_count INTEGER NOT NULL,turn_count INTEGER NOT NULL,error_summary TEXT,created_at TEXT NOT NULL,updated_at TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE run_event_log(id INTEGER PRIMARY KEY AUTOINCREMENT,run_id TEXT NOT NULL,seq INTEGER NOT NULL,event_type TEXT NOT NULL,event_data TEXT NOT NULL,ts INTEGER NOT NULL,UNIQUE(run_id,seq))");
        jdbc.execute("CREATE TABLE permission_grants(grant_id TEXT PRIMARY KEY,grant_kind TEXT,scope TEXT,root_run_id TEXT,expires_at TEXT,revoked_at TEXT)");
    }
}
