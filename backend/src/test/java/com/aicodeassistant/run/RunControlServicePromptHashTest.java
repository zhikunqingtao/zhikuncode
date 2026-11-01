package com.aicodeassistant.run;

import com.aicodeassistant.config.database.DatabaseResolver;
import com.aicodeassistant.config.database.SqliteConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** run_envelopes.prompt_hash 写入语义：显式传入则持久化，旧 4 参入口保持 NULL。 */
class RunControlServicePromptHashTest {
    @TempDir Path temp;
    private SqliteConfig sqlite;
    private JdbcTemplate jdbc;
    private RunControlService service;

    @BeforeEach
    void setUp() {
        DatabaseResolver resolver = new DatabaseResolver("", temp.toString());
        sqlite = new SqliteConfig(resolver);
        var dataSource = sqlite.getProjectDataSource(Path.of("ignored"));
        jdbc = new JdbcTemplate(dataSource);
        createSchema(jdbc);
        jdbc.update("INSERT INTO sessions(id) VALUES('s1')");
        service = new RunControlService(jdbc, sqlite, resolver,
                new DataSourceTransactionManager(dataSource), new ObjectMapper());
    }

    @AfterEach
    void close() {
        if (sqlite != null) sqlite.destroy();
    }

    @Test
    void persistsExplicitPromptHash() {
        RunEnvelope run = service.start("s1", null, "query", "m", "abc123");

        assertThat(run.promptHash()).isEqualTo("abc123");
        assertThat(promptHashColumn(run.id())).isEqualTo("abc123");
        assertThat(jdbc.queryForObject(
                "SELECT event_data FROM run_event_log WHERE run_id=? AND event_type='run_started'",
                String.class, run.id())).contains("\"promptHash\":\"abc123\"");
    }

    @Test
    void legacyStartLeavesPromptHashNull() {
        RunEnvelope run = service.start("s1", null, "query", "m");

        assertThat(run.promptHash()).isNull();
        assertThat(promptHashColumn(run.id())).isNull();
    }

    @Test
    void promptHashHelperIsDeterministicSixteenLowercaseHex() {
        assertThat(RunTracker.promptHash(null)).isNull();
        String hash = RunTracker.promptHash("You are a helpful assistant.");
        assertThat(hash).matches("[0-9a-f]{16}");
        assertThat(RunTracker.promptHash("You are a helpful assistant.")).isEqualTo(hash);
        assertThat(RunTracker.promptHash("You are a helpful assistant!")).isNotEqualTo(hash);
        // SHA-256("") = e3b0c44298fc1c14...
        assertThat(RunTracker.promptHash("")).isEqualTo("e3b0c44298fc1c14");
    }

    private String promptHashColumn(String runId) {
        return jdbc.queryForObject("SELECT prompt_hash FROM run_envelopes WHERE id=?", String.class, runId);
    }

    private static void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE sessions(id TEXT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE run_envelopes(id TEXT PRIMARY KEY,session_id TEXT NOT NULL,parent_run_id TEXT,status TEXT NOT NULL,version INTEGER NOT NULL DEFAULT 0,agent_type TEXT,model TEXT NOT NULL,prompt_hash TEXT,started_at TEXT NOT NULL,finished_at TEXT,terminal_at TEXT,exit_reason TEXT,requested_exit_reason TEXT,verification_status TEXT NOT NULL,waiting_reason TEXT,abort_reason TEXT,total_tokens INTEGER NOT NULL,total_cost_usd REAL NOT NULL,tool_call_count INTEGER NOT NULL,turn_count INTEGER NOT NULL,error_summary TEXT,created_at TEXT NOT NULL,updated_at TEXT NOT NULL,usage_status TEXT NOT NULL DEFAULT 'unknown',usage_snapshot_seq INTEGER NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE run_event_log(id INTEGER PRIMARY KEY AUTOINCREMENT,run_id TEXT NOT NULL,seq INTEGER NOT NULL,event_type TEXT NOT NULL,event_data TEXT NOT NULL,ts INTEGER NOT NULL,UNIQUE(run_id,seq))");
        jdbc.execute("CREATE TABLE permission_grants(grant_id TEXT PRIMARY KEY,grant_kind TEXT,scope TEXT,root_run_id TEXT,expires_at TEXT,revoked_at TEXT)");
    }
}
