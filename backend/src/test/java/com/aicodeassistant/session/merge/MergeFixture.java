package com.aicodeassistant.session.merge;

import com.aicodeassistant.config.database.*;
import com.aicodeassistant.coordinator.SwarmService;
import com.aicodeassistant.hook.HookService;
import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.run.*;
import com.aicodeassistant.security.SystemScratchpadPathPolicy;
import com.aicodeassistant.session.*;
import com.aicodeassistant.state.AppStateStore;
import com.aicodeassistant.tool.agent.BackgroundAgentTracker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.*;

import static org.mockito.Mockito.*;

final class MergeFixture {
    final SQLiteDataSource ds = new SQLiteDataSource();
    final JdbcTemplate jdbc;
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final SessionExecutionGate gate = new SessionExecutionGate();
    final BackgroundAgentTracker agents = mock(BackgroundAgentTracker.class);
    final SwarmService swarms = mock(SwarmService.class);
    final HookService hooks = mock(HookService.class);
    final AppStateStore app = mock(AppStateStore.class);
    final SessionManager sessions;
    final MergePackageService packages;
    final Path root;

    MergeFixture(Path root) {
        this.root = root;
        ds.setUrl("jdbc:sqlite:" + root.resolve("test.db"));
        jdbc = new JdbcTemplate(ds);
        new V002_InitProjectSchema(jdbc).execute();
        jdbc.execute("ALTER TABLE messages ADD COLUMN meta_json TEXT");
        new V012_AddCheckpointSchema(jdbc).execute();
        jdbc.execute("CREATE TABLE run_envelopes (id TEXT PRIMARY KEY, session_id TEXT, status TEXT, exit_reason TEXT, abort_reason TEXT, error_summary TEXT, verification_status TEXT NOT NULL DEFAULT 'not_requested')");
        new V024_CreateSessionMerges(jdbc).execute();
        new V025_AddSessionPermissionMode(jdbc).execute();
        new V026_ExtendSessionMerges(jdbc).execute();
        var sqlite = mock(SqliteConfig.class);
        doAnswer(invocation -> { ((Runnable) invocation.getArgument(1)).run(); return null; })
                .when(sqlite).executeWriteVoid(any(), any());
        sessions = new SessionManager(jdbc, json, sqlite, app, hooks, mock(SessionSnapshotService.class),
                agents, mock(RunExecutionRegistry.class), mock(RunTerminationCoordinator.class));
        sessions.setExecutionGate(gate);
        packages = new MergePackageService(jdbc, json, new SystemScratchpadPathPolicy(root.resolve("scratch")), swarms, agents);
        for (String id : List.of("A", "B", "C")) sessions.createSessionRecord(id, "test-model", root.toString(), id);
    }
    void message(String session, String text) {
        sessions.addMessageWithId(UUID.randomUUID().toString(), session, "user", List.of(new ContentBlock.TextBlock(text)), null, 0, 0, Map.of());
    }
}
