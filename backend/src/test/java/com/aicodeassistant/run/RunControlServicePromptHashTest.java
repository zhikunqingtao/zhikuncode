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
    private static final String UNICODE_PROMPT = "你是智坤助手。\n验证 UTF-8：🧪";
    private static final String UNICODE_HASH = "85b60a4ff152ff48";
    @TempDir Path temp;
    private SqliteConfig sqlite;
    private JdbcTemplate jdbc;
    private RunControlService service;

    @BeforeEach
    void setUp() {
        DatabaseResolver resolver = new DatabaseResolver(temp.resolve("global.db").toString(), temp.toString());
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
        RunEnvelope run = service.start("s1", null, "query", "m", UNICODE_HASH);

        assertThat(run.promptHash()).isEqualTo(UNICODE_HASH);
        assertThat(promptHashColumn(run.id())).isEqualTo(UNICODE_HASH);
        assertThat(jdbc.queryForObject(
                "SELECT event_data FROM run_event_log WHERE run_id=? AND event_type='run_started'",
                String.class, run.id())).contains("\"promptHash\":\"" + UNICODE_HASH + "\"");
    }

    @Test
    void legacyStartLeavesPromptHashNull() {
        RunEnvelope run = service.start("s1", null, "query", "m");

        assertThat(run.promptHash()).isNull();
        assertThat(promptHashColumn(run.id())).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT event_data FROM run_event_log WHERE run_id=? AND event_type='run_started'",
                String.class, run.id())).contains("\"promptHash\":\"unknown\"");
    }

    @Test
    void promptHashHelperIsDeterministicSixteenLowercaseHex() {
        String hash = RunTracker.promptHash(UNICODE_PROMPT);
        assertThat(hash).isEqualTo(UNICODE_HASH);
        assertThat(hash).matches("[0-9a-f]{16}");
        assertThat(RunTracker.promptHash(UNICODE_PROMPT)).isEqualTo(hash);
        assertThat(RunTracker.promptHash(UNICODE_PROMPT + "!")).isNotEqualTo(hash);
    }

    @Test
    void fingerprintsConfiguredTextWithoutNormalizingAbsenceOrWhitespace() {
        assertThat(RunTracker.promptHash(null)).isNull();
        assertThat(RunTracker.promptHash("")).isEqualTo("e3b0c44298fc1c14");
        assertThat(RunTracker.promptHash(" \t\n")).isEqualTo("7d7713ab8ff9ccbf");
        assertThat(RunTracker.promptHash(" " + UNICODE_PROMPT + "\n"))
                .isEqualTo("0469631dc965fd64")
                .isNotEqualTo(UNICODE_HASH);
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
