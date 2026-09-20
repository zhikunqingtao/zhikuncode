package com.aicodeassistant.interaction;

import com.aicodeassistant.config.database.DatabaseResolver;
import com.aicodeassistant.config.database.SqliteConfig;
import com.aicodeassistant.config.database.V015_CreateInteractionSchema;
import com.aicodeassistant.config.database.V019_CreateAuthorizationSchema;
import com.aicodeassistant.controller.InteractionController;
import com.aicodeassistant.engine.ElicitationService;
import com.aicodeassistant.run.RunControlService;
import com.aicodeassistant.run.RunEnvelope;
import com.aicodeassistant.security.SessionAccessAuthorizer;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolUseContext;
import com.aicodeassistant.tool.interaction.AskUserQuestionTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DurableInteractionServiceCasTest {
    @TempDir Path temp;
    private SqliteConfig sqlite;

    @AfterEach void close() { if (sqlite != null) sqlite.destroy(); }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void questionSelectionModeSurvivesRecoveryAndAnswersRoundTrip(boolean multiSelect) throws Exception {
        CompletableFuture<InteractionRequest> created = new CompletableFuture<>();
        Fixture fixture = fixture(event -> {
            if (event instanceof InteractionCreatedEvent interaction) created.complete(interaction.request());
        });
        ObjectMapper json = new ObjectMapper();
        AskUserQuestionTool tool = new AskUserQuestionTool(new ElicitationService(fixture.interactions, json));
        Map<String, Object> question = new java.util.LinkedHashMap<>();
        question.put("question", "Which directions?");
        question.put("options", List.of(Map.of("label", "A"), Map.of("label", "B")));
        // An omitted flag must retain the existing single-select behavior.
        if (multiSelect) question.put("multiSelect", true);
        Object answer = multiSelect ? List.of("A", "B") : "A";

        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = pool.submit(() -> tool.call(ToolInput.from(Map.of("questions", List.of(question))),
                    ToolUseContext.of("/tmp", "s1").withCurrentRunId(fixture.run.id())));
            InteractionRequest pending = created.get(5, TimeUnit.SECONDS);
            try {
                assertThat(fixture.interactions.view(pending).prompt()).containsEntry("multiSelect", multiSelect);
                assertThat(fixture.interactions.pendingViews("s1").getFirst().prompt())
                        .containsEntry("multiSelect", multiSelect);
                InteractionRequest recovered = fixture.interactions.prepareRecoveryDelivery(
                        pending.interactionId(), "transport-reconnected");
                assertThat(fixture.interactions.view(recovered).prompt()).containsEntry("multiSelect", multiSelect);

                SessionAccessAuthorizer access = org.mockito.Mockito.mock(SessionAccessAuthorizer.class);
                org.mockito.Mockito.when(access.canAccessSession("s1", "s1")).thenReturn(true);
                InteractionController controller = new InteractionController(fixture.interactions, access, json);
                var decision = controller.decide(pending.interactionId(), "s1", new InteractionController.DecisionRequest(
                        recovered.version(), "answer", answer, false, null, null, null,
                        recovered.deliveryGeneration()));

                assertThat(decision.getStatusCode().is2xxSuccessful()).isTrue();
                var completed = result.get(5, TimeUnit.SECONDS);
                assertThat(completed.isError()).isFalse();
                assertThat(json.readTree(completed.content()).path("answers").path("q1"))
                        .isEqualTo(json.valueToTree(answer));
                assertThat(fixture.interactions.view(fixture.interactions.findById(pending.interactionId())).response())
                        .isEqualTo(answer);
            } finally {
                // Release the waiting tool even when an assertion fails before the answer is submitted.
                fixture.interactions.decide(pending.interactionId(), pending.version(),
                        InteractionRequest.Status.CANCELLED, null, "test_cleanup");
            }
        }
    }

    @Test
    void offlineRecoveryIsBoundedAndRetryable() {
        Fixture f = fixture();
        InteractionRequest r = f.interactions.create("offline", "s1", f.run.id(),
                InteractionRequest.Type.ELICITATION, Map.of("question", "Continue?"),
                List.of("answer", "cancel"), List.of(), "direct", null);
        assertThat(java.time.Duration.between(r.createdAt(), r.deliveryWindowEndsAt()).toSeconds()).isEqualTo(600);
        f.jdbc.update("UPDATE interaction_requests SET created_at=?,delivery_window_ends_at=? WHERE interaction_id=?",
                Instant.now().minusSeconds(60).toString(), Instant.now().plusSeconds(540).toString(), r.interactionId());
        f.interactions.expireDeadlines();
        InteractionRequest recovered = f.interactions.prepareRecoveryDelivery(r.interactionId(), "t1");
        assertThat(recovered.status()).isEqualTo(InteractionRequest.Status.PENDING);
        assertThat(recovered.firstDispatchedAt()).isNotNull();
        assertThat(java.time.Duration.between(recovered.firstDispatchedAt(), recovered.deliveryWindowEndsAt()).toSeconds()).isEqualTo(30);
        assertThat(f.interactions.redeliveryCandidates(Instant.now().plusSeconds(2)))
                .extracting(InteractionRequest::interactionId).contains(r.interactionId());
        InteractionRequest again = f.interactions.prepareRecoveryDelivery(r.interactionId(), "t2");
        assertThat(again.firstDispatchedAt()).isEqualTo(recovered.firstDispatchedAt());
        assertThat(again.deliveryWindowEndsAt()).isEqualTo(recovered.deliveryWindowEndsAt());
        assertThat(f.interactions.acknowledgeReceived(r.interactionId(), again.deliveryGeneration(), "t2")).isTrue();
        // A scheduler candidate selected before ACK must not terminate the newly acknowledged question.
        f.jdbc.update("UPDATE interaction_requests SET delivery_window_ends_at=? WHERE interaction_id=?",
                Instant.EPOCH.toString(), r.interactionId());
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(f.interactions, "expire",
                r.interactionId(), InteractionRequest.Status.UNDELIVERABLE, "delivery_not_acknowledged");
        assertThat(f.interactions.findById(r.interactionId()).status()).isEqualTo(InteractionRequest.Status.PENDING);
        assertThat(f.events).noneMatch(e -> e instanceof com.aicodeassistant.run.RunTerminationRequestedEvent);
        assertThat(f.interactions.decide(r.interactionId(), again.version(), InteractionRequest.Status.ANSWERED,
                "yes", "user_answered")).isEqualTo(InteractionRequest.Status.ANSWERED);
    }

    @Test
    void overdueRecoveryCannotResurrectQuestion() {
        Fixture f = fixture();
        InteractionRequest r = f.interactions.create("overdue", "s1", f.run.id(),
                InteractionRequest.Type.ELICITATION, Map.of("question", "Continue?"),
                List.of("answer", "cancel"), List.of(), "direct", null);
        f.jdbc.update("UPDATE interaction_requests SET delivery_window_ends_at=? WHERE interaction_id=?",
                Instant.EPOCH.toString(), r.interactionId());
        InteractionRequest recovered = f.interactions.prepareRecoveryDelivery(r.interactionId(), "t1");
        assertThat(recovered.status()).isEqualTo(InteractionRequest.Status.UNDELIVERABLE);
        assertThat(recovered.terminalReason()).isEqualTo("delivery_not_dispatched");
        assertThat(recovered.dispatchAttempts()).isZero();
        assertThat(f.interactions.prepareRecoveryDelivery(r.interactionId(), "t2").status())
                .isEqualTo(InteractionRequest.Status.UNDELIVERABLE);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void deliveryFailureReachesToolWithoutBlamingUser(int stage) throws Exception {
        CompletableFuture<InteractionRequest> created = new CompletableFuture<>();
        Fixture f = fixture(e -> { if (e instanceof InteractionCreatedEvent event) created.complete(event.request()); });
        AskUserQuestionTool tool = new AskUserQuestionTool(new ElicitationService(f.interactions, new ObjectMapper()));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = pool.submit(() -> tool.call(ToolInput.from(Map.of("questions", List.of(Map.of(
                    "question", "Continue?", "options", List.of(Map.of("label", "Yes"), Map.of("label", "No")))))),
                    ToolUseContext.of("/tmp", "s1").withCurrentRunId(f.run.id())));
            InteractionRequest r = created.get(5, TimeUnit.SECONDS);
            try {
                if (stage > 0) f.interactions.markDispatched(r.interactionId(), "t1");
                if (stage == 2) {
                    assertThat(f.interactions.acknowledgeReceived(r.interactionId(), 1, "t1")).isTrue();
                    f.jdbc.update("UPDATE interaction_requests SET decision_deadline_at=? WHERE interaction_id=?",
                            Instant.EPOCH.toString(), r.interactionId());
                } else {
                    f.jdbc.update("UPDATE interaction_requests SET delivery_window_ends_at=? WHERE interaction_id=?",
                            Instant.EPOCH.toString(), r.interactionId());
                }
                f.interactions.expireDeadlines();
                var completed = result.get(5, TimeUnit.SECONDS);
                assertThat(completed.isError()).isTrue();
                assertThat(completed.failureCode()).isEqualTo(stage == 2 ? "ELICITATION_EXPIRED" : "ELICITATION_UNDELIVERABLE");
                assertThat(completed.content()).contains(stage == 2 ? "no answer was received"
                        : stage == 1 ? "did not acknowledge" : "could not be dispatched")
                        .doesNotContain("User did not respond");
            } finally {
                f.interactions.decide(r.interactionId(), r.version(), InteractionRequest.Status.CANCELLED, null, "cleanup");
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lateAnswerAndCancelledQuestionCannotBeRecovered(boolean cancelled) {
        Fixture f = fixture();
        InteractionRequest r = f.interactions.create("terminal", "s1", f.run.id(),
                InteractionRequest.Type.ELICITATION, Map.of("question", "Continue?"),
                List.of("answer", "cancel"), List.of(), "direct", null);
        if (cancelled) {
            f.interactions.decide(r.interactionId(), r.version(), InteractionRequest.Status.CANCELLED, null, "user_cancelled");
        } else {
            f.interactions.markDispatched(r.interactionId(), "t1");
            f.interactions.acknowledgeReceived(r.interactionId(), 1, "t1");
            f.jdbc.update("UPDATE interaction_requests SET decision_deadline_at=? WHERE interaction_id=?",
                    Instant.EPOCH.toString(), r.interactionId());
            assertThat(f.interactions.decide(r.interactionId(), r.version(), InteractionRequest.Status.ANSWERED,
                    "late", "user_answered")).isEqualTo(InteractionRequest.Status.EXPIRED);
        }
        assertThat(f.interactions.prepareRecoveryDelivery(r.interactionId(), "t2").status())
                .isEqualTo(cancelled ? InteractionRequest.Status.CANCELLED : InteractionRequest.Status.EXPIRED);
    }

    @Test
    void concurrentDifferentInteractionsShareOneProjectWriterWithoutBusyFailures() throws Exception {
        Fixture fixture = fixture();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = IntStream.range(0, 20).mapToObj(index -> pool.submit(() -> {
                start.await();
                return fixture.interactions.create("parallel-" + index, "s1", fixture.run.id(),
                        InteractionRequest.Type.ELICITATION, java.util.Map.of("question", index),
                        List.of("answer", "cancel"), List.of(), "direct", null);
            })).toList();
            start.countDown();
            for (var future : futures) assertThat(future.get().status()).isEqualTo(InteractionRequest.Status.PENDING);
        }

        assertThat(fixture.jdbc.queryForObject(
                "SELECT COUNT(*) FROM interaction_requests WHERE run_id=?", Integer.class, fixture.run.id()))
                .isEqualTo(20);
    }

    @Test
    void runRemainsWaitingUntilAllConcurrentInteractionsResolve() {
        Fixture fixture = fixture();
        InteractionRequest first = fixture.interactions.create("first", "s1", fixture.run.id(),
                InteractionRequest.Type.ELICITATION, java.util.Map.of("question", "first"),
                List.of("answer", "cancel"), List.of(), "direct", null);
        InteractionRequest second = fixture.interactions.create("second", "s1", fixture.run.id(),
                InteractionRequest.Type.ELICITATION, java.util.Map.of("question", "second"),
                List.of("answer", "cancel"), List.of(), "direct", null);

        fixture.interactions.decide(first.interactionId(), first.version(),
                InteractionRequest.Status.ANSWERED, java.util.Map.of("decision", "answer"), "user_rest");
        assertThat(fixture.jdbc.queryForObject("SELECT status FROM run_envelopes WHERE id=?",
                String.class, fixture.run.id())).isEqualTo("waiting_interaction");

        fixture.interactions.decide(second.interactionId(), second.version(),
                InteractionRequest.Status.ANSWERED, java.util.Map.of("decision", "answer"), "user_rest");
        assertThat(fixture.jdbc.queryForObject("SELECT status FROM run_envelopes WHERE id=?",
                String.class, fixture.run.id())).isEqualTo("running");
    }

    @Test
    void concurrentDecisionsHaveOneDatabaseAuthorityAndResumeRun() throws Exception {
        Fixture fixture = fixture();
        InteractionRequest request = fixture.interactions.create("tool-1", "s1", fixture.run.id(),
                InteractionRequest.Type.ELICITATION, java.util.Map.of("tool", "Bash"),
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
    void concurrentCreationWithSameCorrelationJoinsSingleDatabaseInteraction() throws Exception {
        Fixture fixture = fixture();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                start.await();
                return fixture.interactions.create("same-logical-tool", "s1", fixture.run.id(),
                        InteractionRequest.Type.ELICITATION, java.util.Map.of("question", "same"),
                        List.of("answer", "cancel"), List.of(), "direct", null);
            });
            var second = pool.submit(() -> {
                start.await();
                return fixture.interactions.create("same-logical-tool", "s1", fixture.run.id(),
                        InteractionRequest.Type.ELICITATION, java.util.Map.of("question", "same"),
                        List.of("answer", "cancel"), List.of(), "direct", null);
            });
            start.countDown();
            assertThat(first.get().interactionId()).isEqualTo(second.get().interactionId());
        }
        assertThat(fixture.jdbc.queryForObject(
                "SELECT COUNT(*) FROM interaction_requests WHERE run_id=? AND correlation_key=?",
                Integer.class, fixture.run.id(), "same-logical-tool")).isEqualTo(1);
    }

    @Test
    void unacknowledgedDeliveryExpiresBeforeRequestingRunTermination() {
        Fixture fixture = fixture();
        InteractionRequest request = fixture.interactions.create("tool-2", "s1", fixture.run.id(),
                InteractionRequest.Type.ELICITATION, java.util.Map.of("tool", "Bash"),
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
    void shortAckTimeoutRemainsRecoverableUntilFinalDeliveryWindow() {
        Fixture fixture = fixture();
        InteractionRequest request = fixture.interactions.create("tool-recoverable-ack", "s1", fixture.run.id(),
                InteractionRequest.Type.ELICITATION, java.util.Map.of("tool", "Agent"),
                List.of("allow", "deny"), List.of("session"), "direct", null);
        assertThat(fixture.interactions.markDispatched(request.interactionId(), "transport-1")).isTrue();
        fixture.jdbc.update("""
                UPDATE interaction_requests SET first_dispatched_at=?,delivery_ack_deadline_at=?,
                  delivery_window_ends_at=? WHERE interaction_id=?
                """, Instant.now().minusSeconds(6).toString(), Instant.EPOCH.toString(),
                Instant.now().plusSeconds(20).toString(), request.interactionId());

        fixture.interactions.expireDeadlines();

        InteractionRequest recoverable = fixture.interactions.findById(request.interactionId());
        assertThat(recoverable.status()).isEqualTo(InteractionRequest.Status.PENDING);
        assertThat(fixture.interactions.redeliveryCandidates(Instant.now()))
                .extracting(InteractionRequest::interactionId).contains(request.interactionId());
        assertThat(fixture.interactions.acknowledgeReceived(
                recoverable.interactionId(), recoverable.deliveryGeneration(), "transport-reconnected"))
                .isTrue();
        assertThat(fixture.interactions.findById(request.interactionId()).receivedAt()).isNotNull();
    }

    @Test
    void redeliveryClaimsAreAtomicAndBounded() {
        Fixture fixture = fixture();
        InteractionRequest request = fixture.interactions.create("tool-3", "s1", fixture.run.id(),
                InteractionRequest.Type.ELICITATION, java.util.Map.of("tool", "Bash"),
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
                InteractionRequest.Type.ELICITATION, java.util.Map.of("tool", "Read"),
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
    void acknowledgementReplacesDeliveryDeadlineWithoutCompletingTerminalWait() throws Exception {
        Fixture fixture = fixture();
        InteractionRequest request = fixture.interactions.create("tool-ack-deadline", "s1", fixture.run.id(),
                InteractionRequest.Type.ELICITATION, java.util.Map.of("tool", "Bash"),
                List.of("answer", "cancel"), List.of(), "direct", null);
        assertThat(fixture.interactions.markDispatched(request.interactionId(), "transport-1")).isTrue();

        // 缩短首次投递 ACK 窗口，使测试稳定覆盖“等待已按旧窗口调度、随后 ACK 延长决策期限”的竞态。
        fixture.jdbc.update("UPDATE interaction_requests SET delivery_ack_deadline_at=? WHERE interaction_id=?",
                Instant.now().plusMillis(250).toString(), request.interactionId());
        CompletableFuture<InteractionRequest.Status> terminal =
                fixture.interactions.awaitTerminal(request.interactionId());

        InteractionRequest dispatched = fixture.interactions.findById(request.interactionId());
        assertThat(fixture.interactions.acknowledgeReceived(
                dispatched.interactionId(), dispatched.deliveryGeneration(), "transport-1")).isTrue();
        InteractionRequest acknowledged = fixture.interactions.findById(request.interactionId());
        assertThat(acknowledged.decisionDeadlineAt()).isAfter(Instant.now().plusSeconds(290));

        assertThatThrownBy(() -> terminal.get(3, TimeUnit.SECONDS))
                .isInstanceOf(TimeoutException.class);
        assertThat(terminal).isNotDone();

        fixture.interactions.decide(request.interactionId(), acknowledged.version(),
                InteractionRequest.Status.ANSWERED, java.util.Map.of("decision", "answer"), "user_rest");
        assertThat(terminal.get(2, TimeUnit.SECONDS)).isEqualTo(InteractionRequest.Status.ANSWERED);
    }

    @Test
    void decisionRollsBackWhenRunCannotResume() {
        Fixture fixture = fixture();
        InteractionRequest request = fixture.interactions.create("tool-4", "s1", fixture.run.id(),
                InteractionRequest.Type.ELICITATION, java.util.Map.of("tool", "Bash"),
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
        return fixture(event -> {});
    }

    private Fixture fixture(java.util.function.Consumer<Object> eventListener) {
        DatabaseResolver resolver = new DatabaseResolver("", temp.toString());
        sqlite = new SqliteConfig(resolver);
        var dataSource = sqlite.getProjectDataSource(Path.of("ignored"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createRunSchema(jdbc);
        new V015_CreateInteractionSchema(jdbc).execute();
        new V019_CreateAuthorizationSchema(jdbc).execute();
        jdbc.update("INSERT INTO sessions(id) VALUES('s1')");
        var tx = new DataSourceTransactionManager(dataSource);
        RunControlService runs = new RunControlService(jdbc, sqlite, resolver, tx, new ObjectMapper());
        RunEnvelope run = runs.start("s1", null, "main", "known");
        java.util.List<Object> publishedEvents = new java.util.concurrent.CopyOnWriteArrayList<>();
        ApplicationEventPublisher events = event -> {
            publishedEvents.add(event);
            eventListener.accept(event);
        };
        DurableInteractionService interactions = new DurableInteractionService(
                jdbc, sqlite, resolver, tx, new ObjectMapper(), runs, events,
                org.mockito.Mockito.mock(com.aicodeassistant.authorization.PermissionGrantRepository.class));
        interactions.reconcileCapacityAfterRestart();
        return new Fixture(jdbc, runs, interactions, run, publishedEvents);
    }

    private static void createRunSchema(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE sessions(id TEXT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE run_envelopes(id TEXT PRIMARY KEY,session_id TEXT NOT NULL,parent_run_id TEXT,status TEXT NOT NULL,version INTEGER NOT NULL DEFAULT 0,agent_type TEXT,model TEXT NOT NULL,prompt_hash TEXT,started_at TEXT NOT NULL,finished_at TEXT,terminal_at TEXT,exit_reason TEXT,requested_exit_reason TEXT,verification_status TEXT NOT NULL,waiting_reason TEXT,abort_reason TEXT,total_tokens INTEGER NOT NULL,total_cost_usd REAL NOT NULL,tool_call_count INTEGER NOT NULL,turn_count INTEGER NOT NULL,error_summary TEXT,created_at TEXT NOT NULL,updated_at TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE run_event_log(id INTEGER PRIMARY KEY AUTOINCREMENT,run_id TEXT NOT NULL,seq INTEGER NOT NULL,event_type TEXT NOT NULL,event_data TEXT NOT NULL,ts INTEGER NOT NULL,UNIQUE(run_id,seq))");
    }

    private record Fixture(JdbcTemplate jdbc, RunControlService runs,
                           DurableInteractionService interactions, RunEnvelope run,
                           java.util.List<Object> events) { }
}
