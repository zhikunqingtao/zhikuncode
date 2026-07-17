package com.aicodeassistant.permission;

import com.aicodeassistant.config.database.DatabaseResolver;
import com.aicodeassistant.config.database.SqliteConfig;
import com.aicodeassistant.config.database.V015_AddInteractionSchema;
import com.aicodeassistant.config.database.V016_CreatePermissionGrantSchema;
import com.aicodeassistant.interaction.DurableInteractionService;
import com.aicodeassistant.interaction.InteractionRequest;
import com.aicodeassistant.run.RunControlService;
import com.aicodeassistant.run.RunEnvelope;
import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.ToolInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PersistentPermissionGrantStoreTest {
    @TempDir Path temp;
    private SqliteConfig sqlite;

    @AfterEach
    void close() {
        if (sqlite != null) sqlite.destroy();
    }

    @Test
    void sessionIsExactWhileWorkspaceUsesTypedExtensionBoundary() throws Exception {
        Fixture fixture = fixture();
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path first = Files.writeString(workspace.resolve("One.java"), "class One {}");
        Path second = Files.writeString(workspace.resolve("Two.java"), "class Two {}");
        Path text = Files.writeString(workspace.resolve("notes.txt"), "notes");
        Tool tool = mock(Tool.class);
        when(tool.getName()).thenReturn("FileReadTool");
        var firstInput = fileInput(tool, first);
        var secondInput = fileInput(tool, second);
        var textInput = fileInput(tool, text);
        PermissionGrantKeyFactory keys = new PermissionGrantKeyFactory();
        var firstKey = keys.create(tool, firstInput, workspace.toString(), "low").orElseThrow();
        var secondKey = keys.create(tool, secondInput, workspace.toString(), "low").orElseThrow();
        var textKey = keys.create(tool, textInput, workspace.toString(), "low").orElseThrow();

        fixture.grants.saveStandard("SESSION", "s1", null, firstKey, fixture.interactionId);
        assertThat(fixture.grants.matchesSession(fixture.runId, "s1", firstKey)).isTrue();
        assertThat(fixture.grants.matchesSession(fixture.runId, "s1", secondKey)).isFalse();

        String workspaceHash = keys.workspaceHash(firstKey.canonicalCwd());
        fixture.grants.saveStandard("WORKSPACE", null, workspaceHash, firstKey, fixture.interactionId);
        assertThat(fixture.grants.matchesWorkspace(fixture.runId, workspaceHash, secondKey)).isTrue();
        assertThat(fixture.grants.matchesWorkspace(fixture.runId, workspaceHash, textKey)).isFalse();
        assertThat(fixture.grants.listActiveForSession("s1", 10)).hasSize(2);
        assertThat(fixture.grants.listActiveForSession("s2", 10))
                .singleElement()
                .satisfies(grant -> assertThat(grant.scope()).isEqualTo("WORKSPACE"));

        String sessionGrantId = fixture.grants.listActiveForSession("s1", 10).stream()
                .filter(grant -> "SESSION".equals(grant.scope()))
                .findFirst().orElseThrow().grantId();
        assertThat(fixture.grants.revokeForSession(sessionGrantId, "s2")).isZero();
        assertThat(fixture.grants.revokeForSession(sessionGrantId, "s1")).isOne();
    }

    @Test
    void childExactGrantStopsMatchingAsSoonAsParentRunIsTerminal() throws Exception {
        Fixture fixture = fixture();
        fixture.jdbc.update("INSERT INTO sessions(id) VALUES('child-s')");
        var childRun = fixture.runs.start("child-s", fixture.runId, "subagent", "known");
        Tool bash = mock(Tool.class);
        when(bash.getName()).thenReturn("Bash");
        ToolInput input = ToolInput.from(Map.of("command", "git status"));
        var key = new PermissionGrantKeyFactory()
                .create(bash, input, temp.toString(), "low").orElseThrow();

        fixture.grants.saveChildExact("s1", childRun.id(), "child-s", "subagent", key,
                fixture.interactionId);
        assertThat(fixture.grants.matchesChildExact(
                "s1", childRun.id(), "child-s", "subagent", key)).isTrue();
        assertThat(fixture.jdbc.queryForList("""
                SELECT event_data FROM run_event_log
                WHERE run_id=? AND event_type='permission_grant_matched'
                ORDER BY seq DESC LIMIT 1
                """, String.class, fixture.runId).getFirst())
                .contains(fixture.interactionId)
                .contains("\"parentSessionId\":\"s1\"")
                .contains("\"childSessionId\":\"child-s\"")
                .contains("\"agentType\":\"subagent\"");

        assertThat(fixture.runs.fail(fixture.runId, RunEnvelope.RunExitReason.INTERNAL_ERROR, "done"))
                .isEqualTo(RunControlService.TransitionResult.APPLIED);
        assertThat(fixture.grants.matchesChildExact(
                "s1", childRun.id(), "child-s", "subagent", key)).isFalse();
    }

    private static ToolInput fileInput(Tool tool, Path path) {
        ToolInput input = ToolInput.from(Map.of("file_path", path.toString()));
        when(tool.getPath(input)).thenReturn(path.toString());
        when(tool.isReadOnly(input)).thenReturn(true);
        return input;
    }

    private Fixture fixture() {
        DatabaseResolver resolver = new DatabaseResolver("", temp.toString());
        sqlite = new SqliteConfig(resolver);
        var dataSource = sqlite.getProjectDataSource(Path.of("ignored"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createRunSchema(jdbc);
        new V015_AddInteractionSchema(jdbc).execute();
        new V016_CreatePermissionGrantSchema(jdbc).execute();
        jdbc.update("INSERT INTO sessions(id) VALUES('s1')");
        var tx = new DataSourceTransactionManager(dataSource);
        var json = new ObjectMapper();
        RunControlService runs = new RunControlService(jdbc, sqlite, resolver, tx, json);
        var run = runs.start("s1", null, "main", "known");
        DurableInteractionService interactions = new DurableInteractionService(
                jdbc, sqlite, resolver, tx, json, runs, ignored -> { });
        InteractionRequest interaction = interactions.create("tool-1", "s1", run.id(),
                InteractionRequest.Type.PERMISSION, Map.of("toolName", "FileReadTool"),
                List.of("allow", "deny"), List.of("session", "workspace"), "direct", null);
        PersistentPermissionGrantStore grants = new PersistentPermissionGrantStore(
                jdbc, sqlite, resolver, tx, runs);
        return new Fixture(jdbc, runs, grants, run.id(), interaction.interactionId());
    }

    private static void createRunSchema(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE sessions(id TEXT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE run_envelopes(id TEXT PRIMARY KEY,session_id TEXT NOT NULL,parent_run_id TEXT,status TEXT NOT NULL,version INTEGER NOT NULL DEFAULT 0,agent_type TEXT,model TEXT NOT NULL,prompt_hash TEXT,started_at TEXT NOT NULL,finished_at TEXT,terminal_at TEXT,exit_reason TEXT,requested_exit_reason TEXT,verification_status TEXT NOT NULL,waiting_reason TEXT,abort_reason TEXT,total_tokens INTEGER NOT NULL,total_cost_usd REAL NOT NULL,tool_call_count INTEGER NOT NULL,turn_count INTEGER NOT NULL,error_summary TEXT,created_at TEXT NOT NULL,updated_at TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE run_event_log(id INTEGER PRIMARY KEY AUTOINCREMENT,run_id TEXT NOT NULL,seq INTEGER NOT NULL,event_type TEXT NOT NULL,event_data TEXT NOT NULL,ts INTEGER NOT NULL,UNIQUE(run_id,seq))");
    }

    private record Fixture(JdbcTemplate jdbc, RunControlService runs,
                           PersistentPermissionGrantStore grants,
                           String runId, String interactionId) { }
}
