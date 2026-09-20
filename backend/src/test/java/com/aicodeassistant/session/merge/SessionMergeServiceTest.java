package com.aicodeassistant.session.merge;

import com.aicodeassistant.controller.GlobalExceptionHandler;
import com.aicodeassistant.controller.SessionMergeController;
import com.aicodeassistant.engine.AbortContext;
import com.aicodeassistant.llm.LlmProvider;
import com.aicodeassistant.model.*;
import com.aicodeassistant.permission.PermissionModeManager;
import com.aicodeassistant.service.ProjectWorkspaceService;
import com.aicodeassistant.session.SessionExecutionBusyException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class SessionMergeServiceTest {
    private static final List<String> FIVE = List.of("A", "B", "C", "D", "E");
    @TempDir Path root;
    MergeFixture f;
    MergeSummaryService summary;
    ProjectWorkspaceService workspaces;
    PermissionModeManager permissions;
    SessionMergeService service;
    @BeforeEach void setup() throws Exception {
        f = new MergeFixture(root);
        summary = mock(MergeSummaryService.class);
        workspaces = mock(ProjectWorkspaceService.class);
        permissions = mock(PermissionModeManager.class);
        when(workspaces.requireCurrentBinding(root.toString())).thenReturn(root);
        when(summary.select(anyString())).thenReturn(new MergeSummaryService.Selection("test-model", mock(LlmProvider.class), MergeSummaryServiceTest.CAPS));
        when(summary.summarize(any(), anyList(), any(), anyString(), any(), any(), any())).thenReturn("来源 A/B 的合并交接资料");
        service = newService(new DataSourceTransactionManager(f.ds), f.packages);
        service.recover();
        f.message("A", "A original"); f.message("B", "B original");
    }
    SessionMergeService newService(DataSourceTransactionManager tx, MergePackageService packages) {
        return new SessionMergeService(f.jdbc, f.sessions, f.gate, packages, summary, workspaces, permissions, f.agents, f.swarms, f.json, tx);
    }
    @AfterEach void stop() { service.shutdown(); }
    SessionMergeService.Request request() { return new SessionMergeService.Request(List.of("A", "B"), "A", "E", "test-model"); }
    void fiveSources() {
        for (String id : List.of("D", "E")) {
            f.sessions.createSessionRecord(id, "test-model", root.toString(), id);
            f.message(id, id + " unique history");
        }
    }
    SessionMergeService.Request request(List<String> ids) { return new SessionMergeService.Request(ids, "A", "merged", "test-model"); }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {2, 3, 4, 5})
    void acceptsTwoThroughFivePreservesSourcesAndNormalizesIdempotency(int count) throws Exception {
        fiveSources();
        var ids = FIVE.subList(0, count);
        var before = f.jdbc.queryForList("SELECT * FROM sessions ORDER BY id");
        var messages = f.jdbc.queryForList("SELECT * FROM messages ORDER BY id");
        var result = await(service.start(request(ids), "count").operationId());
        assertThat(result.status()).isEqualTo("completed");
        var reversed = new ArrayList<>(ids); Collections.reverse(reversed);
        assertThat(service.start(request(reversed), "count").targetSessionId()).isEqualTo(result.targetSessionId());
        assertThat(f.jdbc.queryForList("SELECT * FROM sessions WHERE id<>? ORDER BY id", result.targetSessionId())).isEqualTo(before);
        assertThat(f.jdbc.queryForList("SELECT * FROM messages WHERE session_id<>? ORDER BY id", result.targetSessionId())).isEqualTo(messages);
        assertThat(f.jdbc.queryForObject("SELECT count(*) FROM sessions", Integer.class)).isEqualTo(6);
        verify(summary).summarize(any(), eq(ids), any(), anyString(), any(), any(), any());
    }

    @Test void rejectsInvalidSourceSetsWithoutOccupyingSessions() {
        for (List<String> ids : List.of(List.<String>of(), List.of("A"), List.of("A", "A"),
                List.of("A", "B", "C", "D", "E", "F"), List.of("A", "../B"), List.of("B", "C"),
                Arrays.asList("A", null))) {
            assertThatThrownBy(() -> service.start(request(ids), UUID.randomUUID().toString())).hasMessageContaining("2～5");
        }
        assertThatThrownBy(() -> service.start(request(null), "null")).hasMessageContaining("2～5");
        assertThat(f.jdbc.queryForObject("SELECT count(*) FROM session_merges", Integer.class)).isZero();
        assertThat(f.gate.isBusy(f.ds, "A")).isFalse();
        verifyNoInteractions(summary);
    }

    @Test void fifthSourceAndDescendantBusyRejectWithoutLeakingEarlierOccupancy() throws Exception {
        fiveSources();
        try (var busy = f.gate.tryAcquire(f.ds, "E")) {
            assertThatThrownBy(() -> service.start(request(FIVE), "fifth")).hasMessageContaining("来源会话");
            for (String id : FIVE.subList(0, 4)) assertThat(f.gate.isBusy(f.ds, id)).isFalse();
        }
        try (var background = f.sessions.acquireBackgroundLease("E")) {
            assertThatThrownBy(() -> service.start(request(FIVE), "background"))
                    .hasMessageContaining("ID：E")
                    .hasMessageContaining("可能有尚未退出的后台任务或开发服务")
                    .hasMessageContaining("不会因等待合并而自动停止")
                    .hasMessageContaining("PID 和停止说明")
                    .hasMessageContaining("待进程退出后重试合并");
        }
        f.sessions.registerSubAgentSession("subagent-fifth", root.toString(), "E");
        try (var child = f.gate.tryAcquire(f.ds, "subagent-fifth")) {
            assertThatThrownBy(() -> service.start(request(FIVE), "child"))
                    .hasMessageContaining("关联子会话（ID：subagent-fifth）");
            for (String id : FIVE) assertThat(f.gate.isBusy(f.ds, id)).isFalse();
        }
        f.jdbc.update("INSERT INTO run_envelopes(id,session_id,status) VALUES('waiting-fifth','E','waiting_interaction')");
        assertThatThrownBy(() -> service.start(request(FIVE), "waiting"))
                .hasMessageContaining("ID：E").hasMessageContaining("审批");
        for (String id : FIVE) assertThat(f.gate.isBusy(f.ds, id)).isFalse();
        f.jdbc.update("DELETE FROM run_envelopes WHERE id='waiting-fifth'");
        assertThat(await(service.start(request(FIVE), "after-busy").operationId()).status()).isEqualTo("completed");
    }

    @Test void fiveSourcesStayOccupiedUntilSummaryExitsAndUnrelatedSessionCanContinue() throws Exception {
        fiveSources();
        f.sessions.createSessionRecord("unrelated", "test-model", root.toString(), "unrelated");
        var entered = new CountDownLatch(1); var finish = new CountDownLatch(1);
        when(summary.summarize(any(), anyList(), any(), anyString(), any(), any(), any())).thenAnswer(call -> {
            entered.countDown(); if (!finish.await(5, TimeUnit.SECONDS)) throw new IOException("TEST_TIMEOUT");
            return "summary";
        });
        var op = service.start(request(FIVE), "occupied-five");
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            for (String id : FIVE) {
                assertThat(f.gate.tryAcquire(f.ds, id)).isNull();
                assertThatThrownBy(() -> f.message(id, "blocked")).isInstanceOf(SessionExecutionBusyException.class);
            }
            try (var query = f.gate.tryAcquire(f.ds, "unrelated")) {
                assertThat(query).isNotNull(); f.message("unrelated", "normal query");
            }
        } finally { finish.countDown(); }
        assertThat(await(op.operationId()).status()).isEqualTo("completed");
        for (String id : FIVE) try (var query = f.gate.tryAcquire(f.ds, id)) { assertThat(query).isNotNull(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"SUMMARY_INCOMPLETE", "SUMMARY_CALL_BUDGET_EXCEEDED", "commit"})
    void fiveSourceFailuresLeaveNoTargetAndReleaseEverySource(String failure) throws Exception {
        fiveSources();
        if (failure.equals("commit")) f.jdbc.execute("CREATE TRIGGER fail_target BEFORE INSERT ON messages WHEN NEW.session_id NOT IN ('A','B','C','D','E') BEGIN SELECT RAISE(ABORT,'commit failure'); END");
        else when(summary.summarize(any(), anyList(), any(), anyString(), any(), any(), any())).thenThrow(new IOException(failure));
        var op = service.start(request(FIVE), "five-fail");
        assertThat(await(op.operationId()).status()).isEqualTo("failed");
        assertThat(f.sessions.loadSession(op.targetSessionId())).isEmpty();
        assertThat(Files.exists(Path.of(op.packagePath()))).isFalse();
        for (String id : FIVE) try (var query = f.gate.tryAcquire(f.ds, id)) { assertThat(query).isNotNull(); }
        assertThat(service.start(request(FIVE), "five-fail").operationId()).isEqualTo(op.operationId());
    }
    SessionMergeService.Operation await(String operation) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            var state = service.get(operation);
            if (!state.status().equals("preparing") && f.gate.mergeOperationId(f.ds, "A") == null) return state;
            Thread.sleep(10);
        }
        throw new AssertionError("Merge did not terminate");
    }
    @Test void missingOperationReturnsStableNotFoundCodeWithoutChangingSourcesOrGate() throws Exception {
        var sources = f.jdbc.queryForList("SELECT * FROM sessions ORDER BY id");
        var messages = f.jdbc.queryForList("SELECT * FROM messages ORDER BY id");
        var mvc = standaloneSetup(new SessionMergeController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/api/session-merges/missing").accept(APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("MERGE_OPERATION_NOT_FOUND"));
        assertThat(f.jdbc.queryForList("SELECT * FROM sessions ORDER BY id")).isEqualTo(sources);
        assertThat(f.jdbc.queryForList("SELECT * FROM messages ORDER BY id")).isEqualTo(messages);
        try (var query = f.gate.tryAcquire(f.ds, "A")) { assertThat(query).isNotNull(); }
        try (var merge = f.gate.tryAcquireMerge(f.ds, "A", "actual-operation")) {
            assertThat(merge).isNotNull();
            mvc.perform(get("/api/session-merges/missing").accept(APPLICATION_JSON))
                    .andExpect(status().isNotFound());
            assertThat(f.gate.tryAcquire(f.ds, "A")).isNull();
        }
    }

    @Test void atomicHandoffPreservesSourcesBlocksWritesAndRetryReturnsSameTarget() throws Exception {
        var entered = new CountDownLatch(1); var finish = new CountDownLatch(1);
        when(summary.summarize(any(), anyList(), any(), anyString(), any(), any(), any())).thenAnswer(call -> {
            entered.countDown(); if (!finish.await(5, TimeUnit.SECONDS)) throw new IOException("TEST_TIMEOUT");
            Consumer<MergeSummaryService.CallUsage> sink = call.getArgument(6);
            sink.accept(new MergeSummaryService.CallUsage("merge-only", "test-model", new Usage(100, 30, 0, 0), .01));
            return "summary";
        });
        var sources = f.jdbc.queryForList("SELECT * FROM sessions WHERE id IN ('A','B') ORDER BY id");
        var messages = f.jdbc.queryForList("SELECT * FROM messages ORDER BY id");
        var operation = service.start(request(), "key");
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            assertThat(f.sessions.loadSession(operation.targetSessionId())).isEmpty();
            assertThat(f.gate.tryAcquire(f.ds, "A")).isNull();
            assertThat(f.gate.tryAcquire(f.ds, "B")).isNull();
            assertThatThrownBy(() -> f.message("A", "forbidden")).isInstanceOf(SessionExecutionBusyException.class);
            assertThatThrownBy(() -> f.sessions.deleteSession("B")).isInstanceOf(SessionExecutionBusyException.class);
            assertThatThrownBy(() -> f.sessions.acquireBackgroundLease("A")).isInstanceOf(SessionExecutionBusyException.class);
            try (var other = f.gate.tryAcquire(f.ds, "C")) { assertThat(other).isNotNull(); f.message("C", "still works"); }
            assertThat(service.start(request(), "key").operationId()).isEqualTo(operation.operationId());
            assertThatThrownBy(() -> service.start(new SessionMergeService.Request(List.of("A", "B"), "B", "different", "test-model"), "key"))
                    .hasMessageContaining("幂等键");
        } finally { finish.countDown(); }
        var result = await(operation.operationId());
        assertThat(result.status()).isEqualTo("completed");
        assertThat(service.start(request(), "key").targetSessionId()).isEqualTo(result.targetSessionId());
        var target = f.sessions.loadSession(result.targetSessionId()).orElseThrow();
        assertThat(target.messages()).hasSize(1).first().isInstanceOf(Message.UserMessage.class);
        assertThat(target.totalUsage()).isEqualTo(Usage.zero());
        assertThat(result.usage().toString()).contains("merge-only");
        assertThat(f.jdbc.queryForList("SELECT * FROM sessions WHERE id IN ('A','B') ORDER BY id")).isEqualTo(sources);
        assertThat(f.jdbc.queryForList("SELECT * FROM messages WHERE session_id IN ('A','B') ORDER BY id")).isEqualTo(messages);
        assertThat(f.jdbc.queryForObject("SELECT count(*) FROM sessions", Integer.class)).isEqualTo(4);
        assertThat(f.jdbc.queryForObject("SELECT permission_mode FROM sessions WHERE id=?", String.class,
                result.targetSessionId())).isEqualTo("AUTO_APPROVE");
        verifyNoInteractions(f.app);
    }
    @Test void copyLimitWarningStillCommitsTargetAndPreservesSources() throws Exception {
        org.springframework.test.util.ReflectionTestUtils.setField(f.packages, "maxCopyBytes", 1L);
        Path source = Files.writeString(Files.createDirectories(root.resolve("scratch/A")).resolve("asset.txt"), "source artifact");
        var before = f.jdbc.queryForList("SELECT * FROM sessions WHERE id IN ('A','B') ORDER BY id");
        var result = await(service.start(request(), "copy-limit").operationId());
        assertThat(result.status()).isEqualTo("completed");
        assertThat(((Number) result.result().get("warningCount")).longValue()).isEqualTo(1);
        assertThat(result.result().get("warnings").toString()).contains("容量上限");
        assertThat(f.sessions.loadSession(result.targetSessionId()).orElseThrow().messages()).hasSize(1);
        assertThat(f.jdbc.queryForList("SELECT * FROM sessions WHERE id IN ('A','B') ORDER BY id")).isEqualTo(before);
        assertThat(Files.readString(source)).isEqualTo("source artifact");
    }
    @Test void summaryFailureCreatesNoTargetAndReleasesBothSources() throws Exception {
        when(summary.summarize(any(), anyList(), any(), anyString(), any(), any(), any())).thenThrow(new IOException("SUMMARY_INCOMPLETE"));
        var op = service.start(request(), "fail-summary");
        var result = await(op.operationId());
        assertThat(result.status()).isEqualTo("failed");
        assertThat(f.sessions.loadSession(op.targetSessionId())).isEmpty();
        assertThat(Files.exists(Path.of(op.packagePath()))).isFalse();
        assertThat(f.gate.tryAcquire(f.ds, "A")).isNotNull();
        assertThat(f.gate.tryAcquire(f.ds, "B")).isNotNull();
    }
    @Test void textFailureCleansPackageEvenWhenRecordingFailureIsRejected() throws Exception {
        var packages = spy(f.packages);
        doReturn(0L).when(packages).availableBytes(any());
        service.shutdown(); service = newService(new DataSourceTransactionManager(f.ds), packages);
        f.jdbc.execute("CREATE TRIGGER fail_failure_update BEFORE UPDATE ON session_merges WHEN NEW.status='failed' BEGIN SELECT RAISE(ABORT,'disk full'); END");
        var operation = service.start(request(), "text-full");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        com.aicodeassistant.session.SessionExecutionGate.Token released = null;
        while (released == null && System.nanoTime() < deadline) {
            released = f.gate.tryAcquireMerge(f.ds, "A", "after-failure");
            if (released == null) Thread.sleep(10);
        }
        assertThat(released).isNotNull(); released.close();
        assertThat(Files.exists(Path.of(operation.packagePath()))).isFalse();
        assertThat(f.sessions.loadSession(operation.targetSessionId())).isEmpty();
        assertThat(service.get(operation.operationId()).status()).isEqualTo("preparing");
        verify(summary, never()).summarize(any(), anyList(), any(), anyString(), any(), any(), any());
        try (var lease = f.gate.tryAcquireMerge(f.ds, "A", "after-failure")) { assertThat(lease).isNotNull(); }
    }
    @Test void diskFailureAndMessageCommitFailureDoNotLeaveEmptyE() throws Exception {
        var failingPackages = spy(f.packages);
        doAnswer(call -> { Files.createDirectories(call.getArgument(0)); throw new IOException("DISK_FULL"); })
                .when(failingPackages).build(any(), anyList(), any());
        service.shutdown(); service = newService(new DataSourceTransactionManager(f.ds), failingPackages);
        var disk = service.start(request(), "disk");
        assertThat(await(disk.operationId()).status()).isEqualTo("failed");
        assertThat(f.sessions.loadSession(disk.targetSessionId())).isEmpty();
        service.shutdown(); service = newService(new DataSourceTransactionManager(f.ds), f.packages);
        f.jdbc.execute("CREATE TRIGGER fail_merge_message BEFORE INSERT ON messages WHEN NEW.session_id NOT IN ('A','B','C') BEGIN SELECT RAISE(ABORT,'injected failure'); END");
        var commit = service.start(request(), "commit");
        assertThat(await(commit.operationId()).status()).isEqualTo("failed");
        assertThat(f.sessions.loadSession(commit.targetSessionId())).isEmpty();
        assertThat(Files.exists(Path.of(commit.packagePath()))).isFalse();
    }
    @Test void uncertainCommitAndHookFailureCannotEraseCompletedTarget() throws Exception {
        service.shutdown();
        var transactions = new DataSourceTransactionManager(f.ds) {
            @Override protected void doCommit(DefaultTransactionStatus status) {
                super.doCommit(status);
                throw new IllegalStateException("simulated lost commit acknowledgment");
            }
        };
        service = newService(transactions, f.packages);
        var op = service.start(request(), "uncertain");
        assertThat(await(op.operationId()).status()).isEqualTo("completed");
        assertThat(f.sessions.loadSession(op.targetSessionId())).isPresent();
        assertThat(Files.exists(Path.of(op.packagePath()).resolve("index.md"))).isTrue();
        assertThat(service.start(request(), "uncertain").targetSessionId()).isEqualTo(op.targetSessionId());
        service.shutdown(); service = newService(new DataSourceTransactionManager(f.ds), f.packages);
        doThrow(new IllegalStateException("hook failed")).when(f.hooks).executeSessionStart(anyString());
        var hook = service.start(request(), "hook");
        assertThat(await(hook.operationId()).status()).isEqualTo("completed");
    }
    @Test void rejectsWaitingApprovalAndPartialAdmissionReleasesFirstSource() {
        try (var busy = f.gate.tryAcquire(f.ds, "B")) {
            assertThatThrownBy(() -> service.start(request(), "busy")).hasMessageContaining("来源会话");
            assertThat(f.gate.isBusy(f.ds, "A")).isFalse();
        }
        f.jdbc.update("INSERT INTO run_envelopes(id,session_id,status) VALUES('r','B','waiting_interaction')");
        assertThatThrownBy(() -> service.start(request(), "approval")).hasMessageContaining("审批");
        assertThat(f.gate.isBusy(f.ds, "A")).isFalse();
        assertThat(f.gate.isBusy(f.ds, "B")).isFalse();
    }
    @Test void recoveryFailsInterruptedPreparationButKeepsCommittedPackages() throws Exception {
        var completed = service.start(request(), "completed");
        assertThat(await(completed.operationId()).status()).isEqualTo("completed");
        String id = UUID.randomUUID().toString(); Path path = f.packages.packagePath("orphan", id);
        Files.createDirectories(path); Files.writeString(path.resolve("partial"), "partial");
        f.jdbc.update("INSERT INTO session_merges(operation_id,idempotency_key,params_json,target_session_id,status,stage,package_path,created_at,updated_at) VALUES(?,?,?,'orphan','preparing','summarizing',?,'now','now')",
                id, "interrupted", f.json.writeValueAsString(request()), path.toString());
        service.recover();
        assertThat(service.get(id).status()).isEqualTo("failed");
        assertThat(Files.exists(path)).isFalse();
        assertThat(service.get(completed.operationId()).status()).isEqualTo("completed");
        assertThat(Files.exists(Path.of(completed.packagePath()))).isTrue();
    }
    @Test void sameSourceToolIdsNeverEnterENativeHistoryAndENextMessageNormalizes() throws Exception {
        for (String source : List.of("A", "B")) {
            f.sessions.addMessageWithId(UUID.randomUUID().toString(), source, "assistant", List.of(
                    new ContentBlock.ToolUseBlock("same-id", "Read", f.json.createObjectNode().put("file_path", "missing.txt"))), "tool_use", 0, 0, null);
            f.sessions.addMessageWithId(UUID.randomUUID().toString(), source, "user", List.of(
                    new ContentBlock.ToolResultBlock("same-id", "failed", true)), null, 0, 0, null);
        }
        var op = service.start(request(), "same-tools");
        assertThat(await(op.operationId()).status()).isEqualTo("completed");
        f.message(op.targetSessionId(), "现在继续任务");
        var normalized = new com.aicodeassistant.engine.MessageNormalizer().normalize(f.sessions.loadSession(op.targetSessionId()).orElseThrow().messages());
        assertThat(normalized.toString()).contains("现在继续任务", "合并交接资料").doesNotContain("same-id", "tool_use", "tool_result");
    }
    @Test void slowCreationHookDoesNotRetainSourcesOrPreventAnotherMerge() throws Exception {
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        doAnswer(call -> {
            entered.countDown();
            try { if (!finish.await(5, TimeUnit.SECONDS)) throw new AssertionError("Hook timed out"); }
            finally { exited.countDown(); }
            return null;
        }).when(f.hooks).executeSessionStart(anyString());
        var op = service.start(request(), "slow-hook");
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(service.get(op.operationId()).status()).isEqualTo("completed");
            try (var foreground = f.gate.tryAcquire(f.ds, "A")) { assertThat(foreground).isNotNull(); }
            f.message("A", "A continues while E hook is pending");
            f.message("B", "B continues while E hook is pending");
            // Only the first hook blocks; prove the single merge slot is released too.
            doNothing().when(f.hooks).executeSessionStart(anyString());
            var next = service.start(request(), "next-merge");
            assertThat(await(next.operationId()).status()).isEqualTo("completed");
        } finally { finish.countDown(); }
        assertThat(exited.await(5, TimeUnit.SECONDS)).isTrue();
    }

}
