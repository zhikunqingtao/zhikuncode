package com.aicodeassistant.workbench;

import com.aicodeassistant.artifact.ArtifactEntry;
import com.aicodeassistant.artifact.ArtifactManifest;
import com.aicodeassistant.artifact.ArtifactManifestService;
import com.aicodeassistant.interaction.DurableInteractionService;
import com.aicodeassistant.interaction.InteractionRequest;
import com.aicodeassistant.interaction.InteractionView;
import com.aicodeassistant.run.RunEnvelope;
import com.aicodeassistant.run.RunEnvelopeRepository;
import com.aicodeassistant.verify.EvidenceBundle;
import com.aicodeassistant.verify.EvidenceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.DriverManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkbenchProjectionServiceTest {

    @Test
    void projectsExactlyOneRootRunAndItsOwnResultAndArtifacts(@TempDir Path tempDir) throws Exception {
        var dataSource = new SingleConnectionDataSource(
                DriverManager.getConnection("jdbc:sqlite::memory:"), true);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE run_workbench_bindings(root_run_id TEXT PRIMARY KEY, request_message_id TEXT, result_message_id TEXT)");
        jdbc.execute("CREATE TABLE messages(id TEXT PRIMARY KEY, session_id TEXT, seq_num INTEGER, role TEXT, content_json TEXT, created_at TEXT)");
        jdbc.execute("CREATE TABLE run_acceptance_criteria(criterion_id TEXT, root_run_id TEXT, ordinal INTEGER, source_text TEXT, status TEXT, evidence_bundle_id TEXT)");
        jdbc.execute("CREATE TABLE interaction_requests(interaction_id TEXT, run_id TEXT, session_id TEXT, status TEXT, created_at TEXT)");
        jdbc.execute("CREATE TABLE run_event_log(id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT, seq INTEGER, event_type TEXT, event_data TEXT, ts INTEGER)");
        jdbc.execute("CREATE TABLE activities(id TEXT PRIMARY KEY, session_id TEXT, operation_type TEXT, summary TEXT, status TEXT, timestamp INTEGER, duration INTEGER, file_count INTEGER, decision TEXT, tool_result_json TEXT, changed_files_json TEXT, insight_json TEXT)");
        jdbc.update("INSERT INTO messages VALUES (?,?,?,?,?,?)", "request-current", "session-1", 1, "user",
                "[{\"type\":\"text\",\"text\":\"必须生成当前报告\"}]", "2026-08-12T01:00:00Z");
        jdbc.update("INSERT INTO messages VALUES (?,?,?,?,?,?)", "result-current", "session-1", 2, "assistant",
                "[{\"type\":\"text\",\"text\":\"当前报告已经生成。\"}]", "2026-08-12T01:01:00Z");
        jdbc.update("INSERT INTO messages VALUES (?,?,?,?,?,?)", "result-old", "session-1", 3, "assistant",
                "[{\"type\":\"text\",\"text\":\"旧结果不得出现。\"}]", "2026-08-11T01:01:00Z");
        jdbc.update("INSERT INTO run_workbench_bindings VALUES (?,?,?)",
                "root-current", "request-current", "result-current");
        jdbc.update("INSERT INTO run_acceptance_criteria VALUES (?,?,?,?,?,?)",
                "criterion-1", "root-current", 0, "必须生成当前报告", "not_verified", null);
        jdbc.update("INSERT INTO interaction_requests VALUES (?,?,?,?,?)",
                "pending-current", "root-current", "session-1", "pending", "2026-08-12T01:00:10Z");
        jdbc.update("INSERT INTO interaction_requests VALUES (?,?,?,?,?)",
                "pending-old", "root-old", "session-1", "pending", "2026-08-11T01:00:10Z");
        jdbc.update("INSERT INTO run_event_log(run_id,seq,event_type,event_data,ts) VALUES (?,?,?,?,?)",
                "root-current", 1, "tool_finished",
                "{\"schemaVersion\":2,\"entityId\":\"root-current\",\"toolUseId\":\"tool-current\",\"data\":{\"toolName\":\"WebBrowser\"}}", 1L);
        jdbc.update("INSERT INTO run_event_log(run_id,seq,event_type,event_data,ts) VALUES (?,?,?,?,?)",
                "root-old", 1, "tool_finished",
                "{\"schemaVersion\":2,\"entityId\":\"root-old\",\"toolUseId\":\"tool-old\",\"data\":{\"toolName\":\"Bash\"}}", 2L);
        jdbc.update("INSERT INTO activities VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                "tool-current", "session-1", "browser", "验收当前页面", "completed", 1L,
                20, 0, null, null, "[]", null);
        jdbc.update("INSERT INTO activities VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                "tool-old", "session-1", "bash", "旧活动不得出现", "completed", 2L,
                20, 0, null, null, "[]", null);

        RunEnvelopeRepository runs = mock(RunEnvelopeRepository.class);
        ArtifactManifestService artifacts = mock(ArtifactManifestService.class);
        EvidenceStore evidence = mock(EvidenceStore.class);
        RunEnvelope root = completedRoot("root-current", "session-1");
        when(runs.findLatestRootBySession("session-1")).thenReturn(Optional.of(root));
        when(runs.findTree("root-current")).thenReturn(List.of(root));
        Path currentFile = Files.writeString(tempDir.resolve("current.html"), "current");
        ArtifactEntry entry = new ArtifactEntry("entry-1", "manifest-1", "tool-1",
                currentFile.toString(), "created", "content_verified",
                null, null, 100L, null, null, null, Instant.now(), Instant.now());
        ArtifactManifest manifest = new ArtifactManifest("manifest-1", "root-current",
                "session-1", tempDir.toString(), "verified", Instant.now(), Instant.now(), List.of(entry));
        when(artifacts.getManifestsForRunTree("root-current")).thenReturn(List.of(manifest));
        EvidenceBundle accepted = EvidenceBundle.builder()
                .bundleId("ev-current")
                .sessionId("session-1")
                .runId("root-current")
                .kind("journey")
                .claim("必须生成当前报告")
                .verdict("verified")
                .build();
        when(evidence.findByRunIds(List.of("root-current"))).thenReturn(List.of(accepted));

        DurableInteractionService interactions = mock(DurableInteractionService.class);
        InteractionRequest pendingRequest = mock(InteractionRequest.class);
        InteractionView pendingView = mock(InteractionView.class);
        when(interactions.findById("pending-current")).thenReturn(pendingRequest);
        when(pendingRequest.status()).thenReturn(InteractionRequest.Status.PENDING);
        when(interactions.view(pendingRequest)).thenReturn(pendingView);

        WorkbenchProjectionService service = new WorkbenchProjectionService(jdbc,
                new ObjectMapper(), runs, artifacts, evidence,
                new StructuredSummaryExtractor(), new AcceptanceCriteriaExtractor(),
                interactions);
        WorkbenchCurrentView view = service.current("session-1");

        assertThat(view.correlationMode()).isEqualTo(WorkbenchCurrentView.CorrelationMode.EXACT);
        assertThat(view.request().text()).isEqualTo("必须生成当前报告");
        assertThat(view.result().text()).isEqualTo("当前报告已经生成。");
        assertThat(view.structuredSummary().conclusion()).isEqualTo("当前报告已经生成。");
        assertThat(view.delivery().totalFiles()).isEqualTo(1);
        assertThat(view.delivery().primaryArtifactPath()).isEqualTo(currentFile.toString());
        assertThat(view.delivery().files()).singleElement()
                .satisfies(file -> assertThat(file.primary()).isTrue());
        assertThat(view.pendingActionCount()).isEqualTo(1);
        assertThat(view.pendingActions()).containsExactly(pendingView);
        assertThat(view.activities()).singleElement().satisfies(activity -> {
            assertThat(activity.id()).isEqualTo("tool-current");
            assertThat(activity.runId()).isEqualTo("root-current");
            assertThat(activity.summary()).isEqualTo("验收当前页面");
        });
        assertThat(view.verification().businessCriteria()).extracting(WorkbenchCurrentView.CriterionView::text)
                .containsExactly("必须生成当前报告");
        assertThat(view.verification().businessCriteria().getFirst().status())
                .isEqualTo(WorkbenchCurrentView.CriterionStatus.PASSED);
        assertThat(view.verification().businessCriteria().getFirst().evidenceBundleId())
                .isEqualTo("ev-current");
        verify(artifacts).getManifestsForRunTree("root-current");
        dataSource.destroy();
    }

    @Test
    void exactBindingWithoutResultDoesNotFallBackToAnotherAssistantMessage() throws Exception {
        var dataSource = new SingleConnectionDataSource(
                DriverManager.getConnection("jdbc:sqlite::memory:"), true);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE run_workbench_bindings(root_run_id TEXT PRIMARY KEY, request_message_id TEXT, result_message_id TEXT)");
        jdbc.execute("CREATE TABLE messages(id TEXT PRIMARY KEY, session_id TEXT, seq_num INTEGER, role TEXT, content_json TEXT, created_at TEXT)");
        jdbc.execute("CREATE TABLE run_acceptance_criteria(criterion_id TEXT, root_run_id TEXT, ordinal INTEGER, source_text TEXT, status TEXT, evidence_bundle_id TEXT)");
        jdbc.execute("CREATE TABLE interaction_requests(interaction_id TEXT, run_id TEXT, session_id TEXT, status TEXT, created_at TEXT)");
        jdbc.execute("CREATE TABLE run_event_log(id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT, seq INTEGER, event_type TEXT, event_data TEXT, ts INTEGER)");
        jdbc.update("INSERT INTO messages VALUES (?,?,?,?,?,?)", "request-current", "session-1", 1, "user",
                "[{\"type\":\"text\",\"text\":\"生成当前报告\"}]", "2026-08-12T01:00:00Z");
        jdbc.update("INSERT INTO messages VALUES (?,?,?,?,?,?)", "unrelated-assistant", "session-1", 2, "assistant",
                "[{\"type\":\"text\",\"text\":\"这不是当前执行的最终回复。\"}]", "2026-08-12T01:00:30Z");
        jdbc.update("INSERT INTO run_workbench_bindings VALUES (?,?,?)",
                "root-current", "request-current", null);

        RunEnvelopeRepository runs = mock(RunEnvelopeRepository.class);
        ArtifactManifestService artifacts = mock(ArtifactManifestService.class);
        EvidenceStore evidence = mock(EvidenceStore.class);
        RunEnvelope root = completedRoot("root-current", "session-1");
        when(runs.findLatestRootBySession("session-1")).thenReturn(Optional.of(root));
        when(runs.findTree("root-current")).thenReturn(List.of(root));
        when(artifacts.getManifestsForRunTree("root-current")).thenReturn(List.of());
        when(evidence.findByRunIds(List.of("root-current"))).thenReturn(List.of());

        WorkbenchProjectionService service = new WorkbenchProjectionService(jdbc,
                new ObjectMapper(), runs, artifacts, evidence,
                new StructuredSummaryExtractor(), new AcceptanceCriteriaExtractor(),
                mock(DurableInteractionService.class));
        WorkbenchCurrentView view = service.current("session-1");

        assertThat(view.correlationMode()).isEqualTo(WorkbenchCurrentView.CorrelationMode.EXACT);
        assertThat(view.result()).isNull();
        assertThat(view.resultMessageId()).isNull();
        dataSource.destroy();
    }

    @Test
    void recoversFinalResultOnlyFromTheSameRootRunEvent() throws Exception {
        var dataSource = new SingleConnectionDataSource(
                DriverManager.getConnection("jdbc:sqlite::memory:"), true);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE run_workbench_bindings(root_run_id TEXT PRIMARY KEY, request_message_id TEXT, result_message_id TEXT)");
        jdbc.execute("CREATE TABLE messages(id TEXT PRIMARY KEY, session_id TEXT, seq_num INTEGER, role TEXT, content_json TEXT, created_at TEXT)");
        jdbc.execute("CREATE TABLE run_acceptance_criteria(criterion_id TEXT, root_run_id TEXT, ordinal INTEGER, source_text TEXT, status TEXT, evidence_bundle_id TEXT)");
        jdbc.execute("CREATE TABLE interaction_requests(interaction_id TEXT, run_id TEXT, session_id TEXT, status TEXT, created_at TEXT)");
        jdbc.execute("CREATE TABLE run_event_log(id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT, seq INTEGER, event_type TEXT, event_data TEXT, ts INTEGER)");
        jdbc.update("INSERT INTO messages VALUES (?,?,?,?,?,?)", "request-current", "session-1", 1, "user",
                "[{\"type\":\"text\",\"text\":\"生成当前报告\"}]", "2026-08-12T01:00:00Z");
        jdbc.update("INSERT INTO messages VALUES (?,?,?,?,?,?)", "result-current", "session-1", 2, "assistant",
                "[{\"type\":\"text\",\"text\":\"这是当前执行的最终回复。\"}]", "2026-08-12T01:01:00Z");
        jdbc.update("INSERT INTO messages VALUES (?,?,?,?,?,?)", "unrelated-assistant", "session-1", 3, "assistant",
                "[{\"type\":\"text\",\"text\":\"其他执行的回复。\"}]", "2026-08-12T01:02:00Z");
        jdbc.update("INSERT INTO run_workbench_bindings VALUES (?,?,?)",
                "root-current", "request-current", null);
        jdbc.update("INSERT INTO run_event_log(run_id,seq,event_type,event_data,ts) VALUES (?,?,?,?,?)", "root-current", 1,
                "message_completed", "{\"schemaVersion\":2,\"entityId\":\"root-current\",\"data\":{\"messageId\":\"result-current\",\"stopReason\":\"end_turn\"}}", 1L);

        RunEnvelopeRepository runs = mock(RunEnvelopeRepository.class);
        ArtifactManifestService artifacts = mock(ArtifactManifestService.class);
        EvidenceStore evidence = mock(EvidenceStore.class);
        RunEnvelope root = completedRoot("root-current", "session-1");
        when(runs.findLatestRootBySession("session-1")).thenReturn(Optional.of(root));
        when(runs.findTree("root-current")).thenReturn(List.of(root));
        when(artifacts.getManifestsForRunTree("root-current")).thenReturn(List.of());
        when(evidence.findByRunIds(List.of("root-current"))).thenReturn(List.of());

        WorkbenchProjectionService service = new WorkbenchProjectionService(jdbc,
                new ObjectMapper(), runs, artifacts, evidence,
                new StructuredSummaryExtractor(), new AcceptanceCriteriaExtractor(),
                mock(DurableInteractionService.class));
        WorkbenchCurrentView view = service.current("session-1");

        assertThat(view.correlationMode()).isEqualTo(WorkbenchCurrentView.CorrelationMode.EXACT);
        assertThat(view.result().messageId()).isEqualTo("result-current");
        assertThat(view.result().text()).isEqualTo("这是当前执行的最终回复。");
        dataSource.destroy();
    }

    @Test
    void completedRootWithFailedChildIsPartialRatherThanFailed() throws Exception {
        var dataSource = new SingleConnectionDataSource(
                DriverManager.getConnection("jdbc:sqlite::memory:"), true);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE run_workbench_bindings(root_run_id TEXT PRIMARY KEY, request_message_id TEXT, result_message_id TEXT)");
        jdbc.execute("CREATE TABLE messages(id TEXT PRIMARY KEY, session_id TEXT, seq_num INTEGER, role TEXT, content_json TEXT, created_at TEXT)");
        jdbc.execute("CREATE TABLE run_acceptance_criteria(criterion_id TEXT, root_run_id TEXT, ordinal INTEGER, source_text TEXT, status TEXT, evidence_bundle_id TEXT)");
        jdbc.execute("CREATE TABLE interaction_requests(interaction_id TEXT, run_id TEXT, session_id TEXT, status TEXT, created_at TEXT)");
        jdbc.execute("CREATE TABLE run_event_log(id INTEGER PRIMARY KEY AUTOINCREMENT, run_id TEXT, seq INTEGER, event_type TEXT, event_data TEXT, ts INTEGER)");
        jdbc.update("INSERT INTO messages VALUES (?,?,?,?,?,?)", "request-current", "session-1", 1, "user",
                "[{\"type\":\"text\",\"text\":\"生成报告\"}]", "2026-08-12T01:00:00Z");
        jdbc.update("INSERT INTO messages VALUES (?,?,?,?,?,?)", "result-current", "session-1", 2, "assistant",
                "[{\"type\":\"text\",\"text\":\"报告已生成。\"}]", "2026-08-12T01:01:00Z");
        jdbc.update("INSERT INTO run_workbench_bindings VALUES (?,?,?)",
                "root-current", "request-current", "result-current");

        RunEnvelopeRepository runs = mock(RunEnvelopeRepository.class);
        ArtifactManifestService artifacts = mock(ArtifactManifestService.class);
        EvidenceStore evidence = mock(EvidenceStore.class);
        RunEnvelope root = completedRoot("root-current", "session-1");
        RunEnvelope child = failedChild("child-1", "session-1", "root-current");
        when(runs.findLatestRootBySession("session-1")).thenReturn(Optional.of(root));
        when(runs.findTree("root-current")).thenReturn(List.of(root, child));
        when(artifacts.getManifestsForRunTree("root-current")).thenReturn(List.of());
        when(evidence.findByRunIds(List.of("root-current", "child-1"))).thenReturn(List.of());

        WorkbenchProjectionService service = new WorkbenchProjectionService(jdbc,
                new ObjectMapper(), runs, artifacts, evidence,
                new StructuredSummaryExtractor(), new AcceptanceCriteriaExtractor(),
                mock(DurableInteractionService.class));
        WorkbenchCurrentView view = service.current("session-1");

        WorkbenchCurrentView.CriterionView check = view.verification().technicalChecks().stream()
                .filter(item -> "technical-no-failure-evidence".equals(item.id()))
                .findFirst().orElseThrow();
        assertThat(check.status()).isEqualTo(WorkbenchCurrentView.CriterionStatus.PARTIAL);
        assertThat(check.detail()).contains("不等同于根任务失败");
        dataSource.destroy();
    }

    private static RunEnvelope completedRoot(String id, String sessionId) {
        Instant now = Instant.parse("2026-08-12T01:00:00Z");
        return new RunEnvelope(id, sessionId, null, RunEnvelope.RunStatus.COMPLETED,
                "query", "model", null, now, now.plusSeconds(60), null,
                0, 0, 0, 0, null, now, now.plusSeconds(60), 1,
                RunEnvelope.RunExitReason.MODEL_FINISHED, null,
                RunEnvelope.VerificationStatus.NOT_REQUESTED, now.plusSeconds(60), null);
    }

    private static RunEnvelope failedChild(String id, String sessionId, String parentRunId) {
        Instant now = Instant.parse("2026-08-12T01:00:00Z");
        return new RunEnvelope(id, sessionId, parentRunId, RunEnvelope.RunStatus.FAILED,
                "subagent", "model", null, now, now.plusSeconds(60), null,
                0, 0, 0, 0, "worker reached its limit", now, now.plusSeconds(60), 1,
                RunEnvelope.RunExitReason.DEADLINE_EXCEEDED, null,
                RunEnvelope.VerificationStatus.NOT_REQUESTED, now.plusSeconds(60), null);
    }
}
