package com.aicodeassistant.interaction;

import com.aicodeassistant.config.database.DatabaseResolver;
import com.aicodeassistant.config.database.SqliteConfig;
import com.aicodeassistant.config.database.V015_AddInteractionSchema;
import com.aicodeassistant.run.RunControlService;
import com.aicodeassistant.run.RunEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class DurableInteractionServiceCasTest {
    @TempDir Path temp;
    private SqliteConfig sqlite;

    @AfterEach void close() { if (sqlite != null) sqlite.destroy(); }

    @Test
    void concurrentDecisionsHaveOneDatabaseAuthorityAndResumeRun() throws Exception {
        Fixture fixture = fixture();
        InteractionRequest request = fixture.interactions.create("tool-1", "s1", fixture.run.id(),
                InteractionRequest.Type.PERMISSION, java.util.Map.of("tool", "Bash"),
                List.of("allow", "deny"), List.of("session"), "direct", null);
        long decisionVersion = request.version();
        assertThat(fixture.interactions.markDispatched(request.interactionId(), "transport-1")).isTrue();
        InteractionRequest delivered = fixture.interactions.findById(request.interactionId());
        assertThat(fixture.interactions.acknowledgeReceived(
                delivered.interactionId(), delivered.deliveryGeneration(), "transport-1")).isTrue();
        long version = fixture.interactions.findById(request.interactionId()).version();
        assertThat(version).as("delivery metadata must not invalidate a displayed decision version")
                .isEqualTo(decisionVersion);

        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var allow = pool.submit(() -> { start.await(); return fixture.interactions.decide(
                    request.interactionId(), version, InteractionRequest.Status.ANSWERED,
                    java.util.Map.of("decision", "allow"), "USER_WS"); });
            var deny = pool.submit(() -> { start.await(); return fixture.interactions.decide(
                    request.interactionId(), version, InteractionRequest.Status.DENIED,
                    java.util.Map.of("decision", "deny"), "USER_WS"); });
            start.countDown();
            assertThat(List.of(allow.get(), deny.get()))
                    .allMatch(status -> status == InteractionRequest.Status.ANSWERED
                            || status == InteractionRequest.Status.DENIED);
        }

        InteractionRequest terminal = fixture.interactions.findById(request.interactionId());
        assertThat(terminal.status()).isIn(InteractionRequest.Status.ANSWERED,
                InteractionRequest.Status.DENIED);
        assertThat(fixture.jdbc.queryForObject(
                "SELECT status FROM run_envelopes WHERE id=?", String.class, fixture.run.id()))
                .isEqualTo("running");
    }

    @Test
    void unacknowledgedDeliveryExpiresBeforeRequestingRunTermination() {
        Fixture fixture = fixture();
        InteractionRequest request = fixture.interactions.create("tool-2", "s1", fixture.run.id(),
                InteractionRequest.Type.PERMISSION, java.util.Map.of("tool", "Bash"),
                List.of("allow", "deny"), List.of("session"), "direct", null);
        fixture.jdbc.update("UPDATE interaction_requests SET delivery_window_ends_at=? WHERE interaction_id=?",
                Instant.EPOCH.toString(), request.interactionId());

        fixture.interactions.expireDeadlines();

        assertThat(fixture.interactions.findById(request.interactionId()).status())
                .isEqualTo(InteractionRequest.Status.UNDELIVERABLE);
        assertThat(fixture.jdbc.queryForObject(
                "SELECT status FROM run_envelopes WHERE id=?", String.class, fixture.run.id()))
                .isEqualTo("waiting_interaction");
        assertThat(fixture.events).anyMatch(event -> event instanceof com.aicodeassistant.run.RunTerminationRequestedEvent requested
                && requested.runId().equals(fixture.run.id())
                && requested.reason() == RunEnvelope.RunExitReason.INTERACTION_EXPIRED);
    }

    @Test
    void redeliveryClaimsAreAtomicAndBounded() {
        Fixture fixture = fixture();
        InteractionRequest request = fixture.interactions.create("tool-3", "s1", fixture.run.id(),
                InteractionRequest.Type.PERMISSION, java.util.Map.of("tool", "Bash"),
                List.of("allow", "deny"), List.of("session"), "direct", null);
        assertThat(fixture.interactions.markDispatched(request.interactionId(), "transport-1")).isTrue();
        fixture.jdbc.update("UPDATE interaction_requests SET first_dispatched_at=?,delivery_ack_deadline_at=? WHERE interaction_id=?",
                Instant.now().minusSeconds(2).toString(), Instant.now().plusSeconds(10).toString(),
                request.interactionId());

        assertThat(fixture.interactions.redeliveryCandidates(Instant.now()))
                .extracting(InteractionRequest::interactionId).contains(request.interactionId());
        assertThat(fixture.interactions.claimRedelivery(request.interactionId(), 1, "transport-2")).isTrue();
        assertThat(fixture.interactions.claimRedelivery(request.interactionId(), 1, "transport-3")).isFalse();
        assertThat(fixture.interactions.findById(request.interactionId()).dispatchAttempts()).isEqualTo(2);
    }

    @Test
    void anyBoundSessionTransportMayAcknowledgeABroadcastDelivery() {
        Fixture fixture = fixture();
        InteractionRequest request = fixture.interactions.create("tool-secondary-tab", "s1", fixture.run.id(),
                InteractionRequest.Type.PERMISSION, java.util.Map.of("tool", "Read"),
                List.of("allow", "deny"), List.of("session"), "direct", null);
        assertThat(fixture.interactions.markDispatched(request.interactionId(), "transport-a")).isTrue();
        InteractionRequest dispatched = fixture.interactions.findById(request.interactionId());

        assertThat(fixture.interactions.acknowledgeReceived(
                dispatched.interactionId(), dispatched.deliveryGeneration(), "transport-b")).isTrue();
        InteractionRequest acknowledged = fixture.interactions.findById(request.interactionId());
        assertThat(acknowledged.receivedAt()).isNotNull();
        assertThat(acknowledged.lastTransportId()).isEqualTo("transport-b");
        assertThat(acknowledged.version()).isEqualTo(request.version());
    }

    @Test
    void decisionRollsBackWhenRunCannotResume() {
        Fixture fixture = fixture();
        InteractionRequest request = fixture.interactions.create("tool-4", "s1", fixture.run.id(),
                InteractionRequest.Type.PERMISSION, java.util.Map.of("tool", "Bash"),
                List.of("allow", "deny"), List.of("session"), "direct", null);
        assertThat(fixture.runs.requestCancel(fixture.run.id(), RunEnvelope.RunExitReason.USER_CANCELLED))
                .isEqualTo(RunControlService.TransitionResult.APPLIED);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> fixture.interactions.decide(
                        request.interactionId(), request.version(), InteractionRequest.Status.ANSWERED,
                        java.util.Map.of("decision", "allow"), "USER_REST"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INTERACTION_RUN_TRANSITION_FAILED");

        assertThat(fixture.interactions.findById(request.interactionId()).status())
                .isEqualTo(InteractionRequest.Status.PENDING);
        assertThat(fixture.jdbc.queryForObject(
                "SELECT status FROM run_envelopes WHERE id=?", String.class, fixture.run.id()))
                .isEqualTo("cancelling");
    }

    private Fixture fixture() {
        DatabaseResolver resolver = new DatabaseResolver("", temp.toString());
        sqlite = new SqliteConfig(resolver);
        var dataSource = sqlite.getProjectDataSource(Path.of("ignored"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createRunSchema(jdbc);
        new V015_AddInteractionSchema(jdbc).execute();
        jdbc.update("INSERT INTO sessions(id) VALUES('s1')");
        var tx = new DataSourceTransactionManager(dataSource);
        RunControlService runs = new RunControlService(jdbc, sqlite, resolver, tx, new ObjectMapper());
        RunEnvelope run = runs.start("s1", null, "main", "known");
        java.util.List<Object> publishedEvents = new java.util.concurrent.CopyOnWriteArrayList<>();
        ApplicationEventPublisher events = publishedEvents::add;
        DurableInteractionService interactions = new DurableInteractionService(
                jdbc, sqlite, resolver, tx, new ObjectMapper(), runs, events);
        interactions.reconcileCapacityAfterRestart();
        return new Fixture(jdbc, runs, interactions, run, publishedEvents);
    }

    private static void createRunSchema(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE sessions(id TEXT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE run_envelopes(id TEXT PRIMARY KEY,session_id TEXT NOT NULL,parent_run_id TEXT,status TEXT NOT NULL,version INTEGER NOT NULL DEFAULT 0,agent_type TEXT,model TEXT NOT NULL,prompt_hash TEXT,started_at TEXT NOT NULL,finished_at TEXT,terminal_at TEXT,exit_reason TEXT,requested_exit_reason TEXT,verification_status TEXT NOT NULL,waiting_reason TEXT,abort_reason TEXT,total_tokens INTEGER NOT NULL,total_cost_usd REAL NOT NULL,tool_call_count INTEGER NOT NULL,turn_count INTEGER NOT NULL,error_summary TEXT,created_at TEXT NOT NULL,updated_at TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE run_event_log(id INTEGER PRIMARY KEY AUTOINCREMENT,run_id TEXT NOT NULL,seq INTEGER NOT NULL,event_type TEXT NOT NULL,event_data TEXT NOT NULL,ts INTEGER NOT NULL,UNIQUE(run_id,seq))");
        jdbc.execute("CREATE TABLE permission_grants(grant_id TEXT PRIMARY KEY,grant_kind TEXT,parent_run_id TEXT,expires_at TEXT,revoked_at TEXT)");
    }

    private record Fixture(JdbcTemplate jdbc, RunControlService runs,
                           DurableInteractionService interactions, RunEnvelope run,
                           java.util.List<Object> events) { }
}
