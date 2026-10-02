package com.aicodeassistant.authorization;

import com.aicodeassistant.config.database.DatabaseResolver;
import com.aicodeassistant.config.database.SqliteConfig;
import com.aicodeassistant.config.database.V015_CreateInteractionSchema;
import com.aicodeassistant.config.database.V019_CreateAuthorizationSchema;
import com.aicodeassistant.interaction.DurableInteractionService;
import com.aicodeassistant.interaction.InteractionCreatedEvent;
import com.aicodeassistant.interaction.InteractionRequest;
import com.aicodeassistant.model.PermissionMode;
import com.aicodeassistant.permission.PermissionModeManager;
import com.aicodeassistant.run.RunControlService;
import com.aicodeassistant.run.RunEnvelope;
import com.aicodeassistant.security.PathSecurityService;
import com.aicodeassistant.security.SensitiveDataFilter;
import com.aicodeassistant.service.ProjectWorkspaceService;
import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import com.aicodeassistant.tool.bash.BashSecurityAnalyzer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Real temporary SQLite Run/permission transactions; the tool itself has no external effects. */
class TaskRunAuthorizationCancellationTest {
    @TempDir Path temp;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void pendingCancellationRejectsLateApprovalWithoutCancellingSibling(boolean delivered) throws Exception {
        try (Fixture f = new Fixture(temp, PermissionMode.DEFAULT)) {
            ToolUseContext target = f.child("target");
            ToolUseContext sibling = f.child("sibling");
            Future<ToolResult> targetResult = f.execute(target);
            InteractionRequest targetRequest = f.created(target);
            if (delivered) targetRequest = f.deliver(targetRequest);
            Future<ToolResult> siblingResult = f.execute(sibling);
            InteractionRequest siblingRequest = f.deliver(f.created(sibling));

            var cancelled = f.interactions.beginRunCancellation(target.currentRunId(), "task_stop");

            assertThat(cancelled.runTransition()).isEqualTo(RunControlService.TransitionResult.APPLIED);
            assertThat(cancelled.interactionsCancelled()).isEqualTo(1);
            assertThat(f.approve(targetRequest).status()).isEqualTo(InteractionRequest.Status.CANCELLED);
            assertThatThrownBy(() -> targetResult.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(AuthorizationException.class)
                    .hasRootCauseMessage("Permission interaction ended with CANCELLED");
            verify(f.tool, never()).call(any(), eq(target));
            assertThat(f.started(target)).isZero();
            assertThat(f.state(f.parent.id())).isEqualTo("running");
            assertThat(f.state(sibling.currentRunId())).isEqualTo("waiting_interaction");
            assertThat(siblingResult.isDone()).isFalse();

            assertThat(f.approve(siblingRequest).status()).isEqualTo(InteractionRequest.Status.ANSWERED);
            assertThat(siblingResult.get(5, TimeUnit.SECONDS).isError()).isFalse();
            verify(f.tool).call(any(), eq(sibling));
            assertThat(f.started(sibling)).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @EnumSource(value = PermissionMode.class, names = {"DEFAULT", "AUTO_APPROVE"})
    void cancellationAfterApprovalStillRejectsFinalAdmission(PermissionMode mode) throws Exception {
        try (Fixture f = new Fixture(temp, mode)) {
            ToolUseContext target = f.child("approved-before-cancel");
            Future<AuthorizedOperation> pending = f.executor.submit(() -> f.authorize(target));
            if (mode == PermissionMode.DEFAULT) f.approve(f.deliver(f.created(target)));
            AuthorizedOperation allowed = pending.get(5, TimeUnit.SECONDS);
            assertThat(f.interactions.beginRunCancellation(target.currentRunId(), "task_stop").runTransition())
                    .isEqualTo(RunControlService.TransitionResult.APPLIED);

            assertThatThrownBy(() -> f.gateway.execute(f.tool, allowed, target))
                    .isInstanceOfSatisfying(AuthorizationException.class,
                            denied -> assertThat(denied.code()).isEqualTo("RUN_TOOL_ADMISSION_CLOSED"));
            verify(f.tool, never()).call(any(), any());
            assertThat(f.started(target)).isZero();
            assertThat(f.state(f.parent.id())).isEqualTo("running");
        }
    }

    @Test
    void completedParentDoesNotCloseActiveChildAdmission() throws Exception {
        try (Fixture f = new Fixture(temp, PermissionMode.AUTO_APPROVE)) {
            ToolUseContext child = f.child("outlives-parent");
            assertThat(f.runs.complete(f.parent.id(), 0, 0, 0))
                    .isEqualTo(RunControlService.TransitionResult.APPLIED);

            AuthorizedOperation allowed = f.authorize(child);
            assertThat(allowed.subject().rootRunId()).isEqualTo(f.parent.id());
            assertThat(f.gateway.execute(f.tool, allowed, child).isError()).isFalse();
            assertThat(f.started(child)).isEqualTo(1);
            assertThat(f.state(f.parent.id())).isEqualTo("completed");
        }
    }

    @Test
    void anotherPendingInteractionDoesNotRejectAlreadyAuthorizedTool() throws Exception {
        try (Fixture f = new Fixture(temp, PermissionMode.AUTO_APPROVE)) {
            ToolUseContext child = f.child("parallel-tool");
            AuthorizedOperation allowed = f.authorize(child);
            f.interactions.create("another-tool", f.parent.sessionId(), child.currentRunId(),
                    InteractionRequest.Type.ELICITATION, Map.of("question", "Continue?"),
                    List.of("answer", "cancel"), List.of(), "descendant", null);
            assertThat(f.state(child.currentRunId())).isEqualTo("waiting_interaction");

            assertThat(f.gateway.execute(f.tool, allowed, child).isError()).isFalse();
            assertThat(f.started(child)).isEqualTo(1);
        }
    }

    static final class Fixture implements AutoCloseable {
        final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final Map<String, CompletableFuture<InteractionRequest>> created = new ConcurrentHashMap<>();
        final List<String> childRunIds = new ArrayList<>();
        final ToolInput input = ToolInput.from(Map.of("value", "isolated-fixture"));
        final Tool tool = mock(Tool.class);
        final Path workspace;
        final SqliteConfig sqlite;
        final JdbcTemplate jdbc;
        final RunControlService runs;
        final RunEnvelope parent;
        final DurableInteractionService interactions;
        final AuthorizationService authorization;
        final ToolExecutionGateway gateway;

        Fixture(Path workspace, PermissionMode mode) {
            this.workspace = workspace;
            var resolver = new DatabaseResolver(workspace.resolve("global.db").toString(), workspace.toString());
            sqlite = new SqliteConfig(resolver);
            var dataSource = sqlite.getProjectDataSource(workspace);
            jdbc = new JdbcTemplate(dataSource);
            jdbc.execute("CREATE TABLE sessions(id TEXT PRIMARY KEY,working_dir TEXT)");
            jdbc.execute("""
                    CREATE TABLE run_envelopes(id TEXT PRIMARY KEY,session_id TEXT NOT NULL,parent_run_id TEXT,
                      status TEXT NOT NULL,version INTEGER NOT NULL DEFAULT 0,agent_type TEXT,model TEXT NOT NULL,
                      prompt_hash TEXT,started_at TEXT NOT NULL,finished_at TEXT,terminal_at TEXT,exit_reason TEXT,
                      requested_exit_reason TEXT,verification_status TEXT NOT NULL,waiting_reason TEXT,abort_reason TEXT,
                      total_tokens INTEGER NOT NULL,total_cost_usd REAL NOT NULL,tool_call_count INTEGER NOT NULL,
                      turn_count INTEGER NOT NULL,error_summary TEXT,created_at TEXT NOT NULL,updated_at TEXT NOT NULL)
                    """);
            jdbc.execute("""
                    CREATE TABLE run_event_log(id INTEGER PRIMARY KEY AUTOINCREMENT,run_id TEXT NOT NULL,
                      seq INTEGER NOT NULL,event_type TEXT NOT NULL,event_data TEXT NOT NULL,ts INTEGER NOT NULL,
                      UNIQUE(run_id,seq))
                    """);
            new V015_CreateInteractionSchema(jdbc).execute();
            new V019_CreateAuthorizationSchema(jdbc).execute();
            jdbc.update("INSERT INTO sessions(id,working_dir) VALUES(?,?)", "root-session", workspace.toString());
            var tx = new DataSourceTransactionManager(dataSource);
            runs = new RunControlService(jdbc, sqlite, resolver, tx, json);
            parent = runs.start("root-session", null, "main", "test");
            var workspaces = new WorkspaceIdentityService();
            var grants = new PermissionGrantRepository(jdbc, sqlite, resolver, tx, json, workspaces);
            interactions = new DurableInteractionService(jdbc, sqlite, resolver, tx, json, runs, event -> {
                if (event instanceof InteractionCreatedEvent interaction) {
                    created.computeIfAbsent(interaction.request().runId(), ignored -> new CompletableFuture<>())
                            .complete(interaction.request());
                }
            }, grants);
            var modes = mock(PermissionModeManager.class);
            when(modes.getMode(anyString())).thenReturn(mode);
            var analyzers = new OperationAnalyzerRegistry(json, mock(BashSecurityAnalyzer.class),
                    new SensitiveDataFilter(), mock(PathSecurityService.class));
            authorization = new AuthorizationService(new AuthorizationSubjectResolver(jdbc, workspaces),
                    analyzers, grants, interactions, modes, runs, json, mock(ProjectWorkspaceService.class));
            gateway = new ToolExecutionGateway(authorization, runs);
            when(tool.getName()).thenReturn("TaskCancellationProbe");
            when(tool.call(any(), any())).thenReturn(ToolResult.success("ok"));
        }

        ToolUseContext child(String name) {
            RunEnvelope child = runs.start("task-" + name, parent.id(), "task", "test");
            childRunIds.add(child.id());
            return ToolUseContext.of(workspace.toString(), child.sessionId())
                    .withCurrentRunId(child.id()).withToolUseId("tool-" + name);
        }

        AuthorizedOperation authorize(ToolUseContext context) {
            try (var frozen = new FrozenToolInputFactory(json, 1024, 4096).freeze(tool.getName(), input)) {
                return authorization.authorize(tool, frozen, input, context);
            }
        }

        Future<ToolResult> execute(ToolUseContext context) {
            return executor.submit(() -> gateway.execute(tool, authorize(context), context));
        }

        InteractionRequest created(ToolUseContext context) throws Exception {
            return created.computeIfAbsent(context.currentRunId(), ignored -> new CompletableFuture<>())
                    .get(5, TimeUnit.SECONDS);
        }

        InteractionRequest deliver(InteractionRequest request) {
            assertThat(interactions.markDispatched(request.interactionId(), "test-transport")).isTrue();
            var dispatched = interactions.findById(request.interactionId());
            assertThat(interactions.acknowledgeReceived(request.interactionId(),
                    dispatched.deliveryGeneration(), "test-transport")).isTrue();
            return interactions.findById(request.interactionId());
        }

        InteractionRequest approve(InteractionRequest request) throws Exception {
            return interactions.decideRequest(request.interactionId(), request.version(),
                    InteractionRequest.Status.ANSWERED, Map.of(
                            "operationHash", json.readTree(request.promptJson()).path("operationHash").asText(),
                            "deliveryGeneration", request.deliveryGeneration(), "optionId", "allow_once",
                            "decision", "allow", "scope", "once", "remember", false), "USER_APPROVED");
        }

        String state(String runId) {
            return jdbc.queryForObject("SELECT status FROM run_envelopes WHERE id=?", String.class, runId);
        }

        int started(ToolUseContext context) {
            return jdbc.queryForObject("SELECT COUNT(*) FROM run_event_log WHERE run_id=? AND event_type='tool_started'",
                    Integer.class, context.currentRunId());
        }

        @Override public void close() throws Exception {
            try {
                // A failed assertion must not strand a permission join in the executor.
                for (String runId : childRunIds) interactions.beginRunCancellation(runId, "test_cleanup");
                executor.shutdownNow();
                assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            } finally {
                sqlite.destroy();
            }
        }
    }
}
