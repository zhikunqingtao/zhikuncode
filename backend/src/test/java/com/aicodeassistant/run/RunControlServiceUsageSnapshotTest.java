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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 已观测用量快照的数据库语义 — 序号单调、同序号幂等/冲突、未知 Run 不落库，
 * 以及进入终态后补齐快照不得改写终态事实或重复终态事件。
 */
class RunControlServiceUsageSnapshotTest {
    @TempDir Path temp;
    private SqliteConfig sqlite;
    private JdbcTemplate jdbc;
    private RunControlService service;
    private Path dbPath;

    @BeforeEach
    void setUp() {
        DatabaseResolver resolver = new DatabaseResolver("", temp.toString());
        dbPath = resolver.getProjectDbPath(Path.of("ignored"));
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
    void appliesMonotonicSnapshotsAndClassifiesReplaysAndConflicts() {
        RunEnvelope run = service.start("s1", null, "main", "known");

        // 初始 seq=0 / tokens=0 / turns=0 / status=unknown：0 保留表示"尚无快照"
        // （引擎首次快照从 1 开始），null 状态按 unknown 幂等。
        assertThat(service.updateUsageSnapshot(run.id(), 0, 0, 0, null))
                .isEqualTo(RunControlService.UsageSnapshotOutcome.IDEMPOTENT);

        assertThat(service.updateUsageSnapshot(run.id(), 1, 120, 2, RunEnvelope.UsageStatus.KNOWN))
                .isEqualTo(RunControlService.UsageSnapshotOutcome.APPLIED);
        assertThat(service.updateUsageSnapshot(run.id(), 2, 200, 3, RunEnvelope.UsageStatus.PARTIAL))
                .isEqualTo(RunControlService.UsageSnapshotOutcome.APPLIED);

        // 旧序号忽略；同序号同值幂等；同序号异值冲突（数值、轮次、状态任一不同）。
        assertThat(service.updateUsageSnapshot(run.id(), 1, 120, 2, RunEnvelope.UsageStatus.KNOWN))
                .isEqualTo(RunControlService.UsageSnapshotOutcome.STALE_IGNORED);
        assertThat(service.updateUsageSnapshot(run.id(), 2, 200, 3, RunEnvelope.UsageStatus.PARTIAL))
                .isEqualTo(RunControlService.UsageSnapshotOutcome.IDEMPOTENT);
        assertThat(service.updateUsageSnapshot(run.id(), 2, 999, 3, RunEnvelope.UsageStatus.PARTIAL))
                .isEqualTo(RunControlService.UsageSnapshotOutcome.CONFLICT);
        assertThat(service.updateUsageSnapshot(run.id(), 2, 200, 4, RunEnvelope.UsageStatus.PARTIAL))
                .isEqualTo(RunControlService.UsageSnapshotOutcome.CONFLICT);
        assertThat(service.updateUsageSnapshot(run.id(), 2, 200, 3, RunEnvelope.UsageStatus.KNOWN))
                .isEqualTo(RunControlService.UsageSnapshotOutcome.CONFLICT);

        // 不存在的 Run（含 null 参数）→ NOT_FOUND，绝不落库。
        assertThat(service.updateUsageSnapshot("missing-run", 1, 1, 1, RunEnvelope.UsageStatus.KNOWN))
                .isEqualTo(RunControlService.UsageSnapshotOutcome.NOT_FOUND);
        assertThat(service.updateUsageSnapshot(null, 1, 1, 1, RunEnvelope.UsageStatus.KNOWN))
                .isEqualTo(RunControlService.UsageSnapshotOutcome.NOT_FOUND);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM run_envelopes WHERE id=?", run.id());
        assertThat(row.get("total_tokens")).isEqualTo(200);
        assertThat(row.get("turn_count")).isEqualTo(3);
        assertThat(row.get("usage_status")).isEqualTo("partial");
        assertThat(((Number) row.get("usage_snapshot_seq")).longValue()).isEqualTo(2);
        assertThat(row.get("status")).isEqualTo("running");
        // 快照写入绝不追加事件：只有 run_started 一条。
        assertThat(eventCount(run.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM run_event_log WHERE run_id=? AND event_type='run_status_changed'",
                Integer.class, run.id())).isZero();
    }

    @Test
    void lateSnapshotAfterTerminalDoesNotRewriteTerminalFactsOrDuplicateEvents() {
        RunEnvelope run = service.start("s1", null, "main", "known");
        assertThat(service.updateUsageSnapshot(run.id(), 1, 50, 1, RunEnvelope.UsageStatus.KNOWN))
                .isEqualTo(RunControlService.UsageSnapshotOutcome.APPLIED);

        // 先进入终态（取消）。
        assertThat(service.cancel(run.id())).isEqualTo(RunControlService.TransitionResult.APPLIED);
        Map<String, Object> before = jdbc.queryForMap("SELECT * FROM run_envelopes WHERE id=?", run.id());
        assertThat(before.get("status")).isEqualTo("cancelled");
        assertThat(before.get("exit_reason")).isEqualTo("user_cancelled");
        assertThat(before.get("terminal_at")).isNotNull();
        assertThat(before.get("finished_at")).isNotNull();
        int eventsBefore = eventCount(run.id());
        assertThat(eventsBefore).isEqualTo(2); // run_started + run_status_changed

        // 终态后补齐已观测用量 → 仍然 APPLIED，但只写用量列。
        assertThat(service.updateUsageSnapshot(run.id(), 2, 180, 4, RunEnvelope.UsageStatus.PARTIAL))
                .isEqualTo(RunControlService.UsageSnapshotOutcome.APPLIED);

        Map<String, Object> after = jdbc.queryForMap("SELECT * FROM run_envelopes WHERE id=?", run.id());
        assertThat(after.get("status")).isEqualTo(before.get("status"));
        assertThat(after.get("exit_reason")).isEqualTo(before.get("exit_reason"));
        assertThat(after.get("terminal_at")).isEqualTo(before.get("terminal_at"));
        assertThat(after.get("finished_at")).isEqualTo(before.get("finished_at"));
        assertThat(after.get("error_summary")).isEqualTo(before.get("error_summary"));
        assertThat(after.get("total_tokens")).isEqualTo(180);
        assertThat(after.get("turn_count")).isEqualTo(4);
        assertThat(after.get("usage_status")).isEqualTo("partial");
        assertThat(((Number) after.get("usage_snapshot_seq")).longValue()).isEqualTo(2);

        // 不重复终态、不重复完成/状态事件。
        assertThat(eventCount(run.id())).isEqualTo(eventsBefore);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM run_event_log WHERE run_id=? AND event_type='run_status_changed'",
                Integer.class, run.id())).isEqualTo(1);
    }

    @Test
    void busySnapshotReturnsWithoutWaitingForTheLockHolderToFinish() throws Exception {
        RunEnvelope run = service.start("s1", null, "main", "known");
        RunTracker tracker = new RunTracker(service, null, null, null, null);
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = holdWriteLock(acquired, release);
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        Thread writer = null;
        try {
            assertThat(acquired.await(5, TimeUnit.SECONDS)).isTrue();
            writer = Thread.startVirtualThread(() -> result.complete(
                    tracker.recordUsageSnapshotBestEffort(run.id(), 1, 15, 1,
                            RunEnvelope.UsageStatus.KNOWN)));

            // The lock is still held: the supplemental write must return so that
            // QueryEngine can enter the existing termination/cleanup path.
            assertThat(result.get(15, TimeUnit.SECONDS)).isFalse();
            assertThat(holder.isAlive()).isTrue();
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT status,total_tokens,usage_snapshot_seq FROM run_envelopes WHERE id=?", run.id());
            assertThat(row.get("status")).isEqualTo("running");
            assertThat(((Number) row.get("total_tokens")).intValue()).isZero();
            assertThat(((Number) row.get("usage_snapshot_seq")).longValue()).isZero();
            assertThat(eventCount(run.id())).isEqualTo(1);
        } finally {
            release.countDown();
            holder.join(5000);
            if (writer != null) writer.join(5000);
        }

        assertThat(tracker.recordUsageSnapshotBestEffort(run.id(), 1, 15, 1,
                RunEnvelope.UsageStatus.KNOWN)).isTrue();
    }

    @Test
    void interruptedSnapshotReturnsWithoutClearingTheCallerInterrupt() throws Exception {
        RunEnvelope run = service.start("s1", null, "main", "known");
        RunTracker tracker = new RunTracker(service, null, null, null, null);
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = holdWriteLock(acquired, release);
        CompletableFuture<Boolean> interruptedAfterReturn = new CompletableFuture<>();
        Thread writer = null;
        try {
            assertThat(acquired.await(5, TimeUnit.SECONDS)).isTrue();
            writer = Thread.startVirtualThread(() -> {
                Thread.currentThread().interrupt();
                boolean recorded = tracker.recordUsageSnapshotBestEffort(run.id(), 1, 15, 1,
                        RunEnvelope.UsageStatus.KNOWN);
                interruptedAfterReturn.complete(!recorded && Thread.currentThread().isInterrupted());
            });
            assertThat(interruptedAfterReturn.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(holder.isAlive()).isTrue();
            assertThat(eventCount(run.id())).isEqualTo(1);
        } finally {
            release.countDown();
            holder.join(5000);
            if (writer != null) writer.join(5000);
        }
    }

    private Thread holdWriteLock(CountDownLatch acquired, CountDownLatch release) {
        return Thread.startVirtualThread(() -> sqlite.executeWrite(dbPath, () -> {
            acquired.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return null;
        }));
    }

    private int eventCount(String runId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM run_event_log WHERE run_id=?", Integer.class, runId);
        return count == null ? 0 : count;
    }

    private static void createSchema(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE sessions(id TEXT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE run_envelopes(id TEXT PRIMARY KEY,session_id TEXT NOT NULL,parent_run_id TEXT,status TEXT NOT NULL,version INTEGER NOT NULL DEFAULT 0,agent_type TEXT,model TEXT NOT NULL,prompt_hash TEXT,started_at TEXT NOT NULL,finished_at TEXT,terminal_at TEXT,exit_reason TEXT,requested_exit_reason TEXT,verification_status TEXT NOT NULL,waiting_reason TEXT,abort_reason TEXT,total_tokens INTEGER NOT NULL,total_cost_usd REAL NOT NULL,tool_call_count INTEGER NOT NULL,turn_count INTEGER NOT NULL,error_summary TEXT,created_at TEXT NOT NULL,updated_at TEXT NOT NULL,usage_status TEXT NOT NULL DEFAULT 'unknown',usage_snapshot_seq INTEGER NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE run_event_log(id INTEGER PRIMARY KEY AUTOINCREMENT,run_id TEXT NOT NULL,seq INTEGER NOT NULL,event_type TEXT NOT NULL,event_data TEXT NOT NULL,ts INTEGER NOT NULL,UNIQUE(run_id,seq))");
        jdbc.execute("CREATE TABLE permission_grants(grant_id TEXT PRIMARY KEY,grant_kind TEXT,scope TEXT,root_run_id TEXT,expires_at TEXT,revoked_at TEXT)");
    }
}
