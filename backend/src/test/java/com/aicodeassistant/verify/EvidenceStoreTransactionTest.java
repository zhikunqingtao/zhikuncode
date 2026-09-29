package com.aicodeassistant.verify;

import com.aicodeassistant.config.database.DatabaseResolver;
import com.aicodeassistant.config.database.SqliteConfig;
import com.aicodeassistant.config.database.V007_AddEvidenceTables;
import com.aicodeassistant.security.SensitiveDataFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EvidenceStore 事务语义测试 — 用真实临时 SQLite 验证「完整保存或完整失败」：
 * 重复 ID 明确失败、中途失败整批回滚、序列化失败零写入、锁忙/中断零写入、历史短 ID 仍可读。
 */
class EvidenceStoreTransactionTest {

    @TempDir
    Path tempDir;

    private SqliteConfig sqlite;
    private JdbcTemplate jdbc;
    private Path dbPath;
    private EvidenceStore store;

    @BeforeEach
    void setUp() {
        DatabaseResolver resolver = new DatabaseResolver("", tempDir.toString());
        sqlite = new SqliteConfig(resolver);
        var dataSource = sqlite.getProjectDataSource(tempDir);
        jdbc = new JdbcTemplate(dataSource);
        new V007_AddEvidenceTables(jdbc).execute();
        if (jdbc.queryForList("PRAGMA table_info(evidence_bundles)").stream()
                .noneMatch(row -> "run_id".equals(row.get("name")))) {
            jdbc.execute("ALTER TABLE evidence_bundles ADD COLUMN run_id TEXT");
        }
        dbPath = resolver.getProjectDbPath(tempDir);
        store = new EvidenceStore(jdbc, sqlite, resolver,
                new DataSourceTransactionManager(dataSource), new ObjectMapper(),
                new SensitiveDataFilter(), tempDir.resolve("blobs"));
    }

    @AfterEach
    void tearDown() {
        sqlite.destroy();
    }

    @Test
    @DisplayName("真实 SQLite：save 后 findById 完整读回 bundle 与 items（含 runId、元数据与顺序）")
    void saveThenFindById_roundTripsCompleteEvidence() {
        EvidenceBundle bundle = new EvidenceBundle(
                "ev-roundtrip", "session-rt", "run-rt", "agent-rt", "journey",
                "verify login", "verified",
                List.of(
                        new EvidenceItem("item-a", "screenshot", "Step 1 ok", "sha-aaa", Map.of("k", "v")),
                        new EvidenceItem("item-b", "command", "Step 2 ok", null, Map.of())),
                Instant.parse("2026-06-05T10:00:00Z"));

        EvidenceBundle saved = store.save(bundle);

        assertEquals("ev-roundtrip", saved.bundleId());
        assertEquals(2, saved.items().size());

        EvidenceBundle loaded = store.findById("ev-roundtrip").orElseThrow();
        assertEquals("session-rt", loaded.sessionId());
        assertEquals("run-rt", loaded.runId());
        assertEquals("agent-rt", loaded.agentId());
        assertEquals("journey", loaded.kind());
        assertEquals("verify login", loaded.claim());
        assertEquals("verified", loaded.verdict());
        assertEquals(Instant.parse("2026-06-05T10:00:00Z"), loaded.createdAt());
        assertEquals(List.of("item-a", "item-b"),
                loaded.items().stream().map(EvidenceItem::id).toList());
        assertEquals(Map.of("k", "v"), loaded.items().get(0).meta());
        assertEquals("sha-aaa", loaded.items().get(0).blobSha256());
        assertEquals(Map.of(), loaded.items().get(1).meta());
    }

    @Test
    @DisplayName("真实 SQLite：bundle ID 重复明确失败，旧数据不被覆盖（不换 ID 重试、不把冲突当成功）")
    void save_duplicateBundleId_failsAndKeepsOriginal() {
        store.save(simpleBundle("ev-dup", "first claim", "keep-item"));

        Throwable failure = assertThrows(RuntimeException.class,
                () -> store.save(simpleBundle("ev-dup", "second claim", "new-item")));
        assertSqliteConstraintFailure(failure);

        EvidenceBundle original = store.findById("ev-dup").orElseThrow();
        assertEquals("first claim", original.claim());
        assertEquals(List.of("keep-item"), original.items().stream().map(EvidenceItem::id).toList());
        assertEquals(0, count("SELECT COUNT(*) FROM evidence_items WHERE id='new-item'"));
    }

    @Test
    @DisplayName("真实 SQLite：同包第二个 item 冲突 → 整批回滚，不出现半个 bundle，已有证据不变")
    void save_secondItemDuplicates_rollsBackWholeBundle() {
        store.save(simpleBundle("ev-existing", "existing", "shared-item"));

        EvidenceBundle conflicting = new EvidenceBundle(
                "ev-halfway", "session-half", "agent-half", "journey", "half", "verified",
                List.of(
                        new EvidenceItem("fresh-item", "command", "first", null, Map.of()),
                        new EvidenceItem("shared-item", "command", "conflict", null, Map.of())),
                Instant.parse("2026-06-05T10:00:00Z"));

        Throwable failure = assertThrows(RuntimeException.class, () -> store.save(conflicting));
        assertSqliteConstraintFailure(failure);

        assertTrue(store.findById("ev-halfway").isEmpty(), "冲突后不得留下半个 bundle");
        assertEquals(0, count("SELECT COUNT(*) FROM evidence_items WHERE id='fresh-item'"));
        EvidenceBundle existing = store.findById("ev-existing").orElseThrow();
        assertEquals("existing", existing.claim());
        assertEquals(List.of("shared-item"), existing.items().stream().map(EvidenceItem::id).toList());
    }

    @Test
    @DisplayName("真实 SQLite：同一 bundle 内重复 item ID → 整批回滚，bundle 与 item 都不落库")
    void save_duplicateItemWithinSameBundle_rollsBackWholeBundle() {
        EvidenceBundle conflicting = new EvidenceBundle(
                "ev-dupe-items", "session-dupe", "agent-dupe", "journey", "dupe", "verified",
                List.of(
                        new EvidenceItem("dupe-item", "command", "first", null, Map.of()),
                        new EvidenceItem("dupe-item", "command", "second", null, Map.of())),
                Instant.parse("2026-06-05T10:00:00Z"));

        Throwable failure = assertThrows(RuntimeException.class, () -> store.save(conflicting));
        assertSqliteConstraintFailure(failure);

        assertTrue(store.findById("ev-dupe-items").isEmpty(), "同包 item 冲突后不得留下半个 bundle");
        assertEquals(0, count("SELECT COUNT(*) FROM evidence_items WHERE id='dupe-item'"));
    }

    @Test
    @DisplayName("真实 SQLite：元数据序列化失败 → 明确报错且无新记录")
    void save_metaSerializationFailure_throwsAndWritesNothing() {
        store.save(simpleBundle("ev-survivor", "survivor", "survivor-item"));

        Map<String, Object> cyclic = new HashMap<>();
        cyclic.put("self", cyclic);
        EvidenceBundle broken = new EvidenceBundle(
                "ev-serialization-failure", "session-meta", "agent-meta", "journey", "meta", "verified",
                List.of(new EvidenceItem("meta-item", "screenshot", "cyclic", null, cyclic)),
                Instant.parse("2026-06-05T10:00:00Z"));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> store.save(broken));
        assertTrue(failure.getMessage().contains("EVIDENCE_META_SERIALIZATION_FAILED"),
                "应携带明确错误码，实际：" + failure.getMessage());
        assertNotNull(failure.getCause());

        assertEquals(0, count("SELECT COUNT(*) FROM evidence_bundles WHERE bundle_id='ev-serialization-failure'"));
        assertEquals(0, count("SELECT COUNT(*) FROM evidence_items WHERE id='meta-item'"));
        assertEquals(1, count("SELECT COUNT(*) FROM evidence_bundles WHERE bundle_id='ev-survivor'"));
        // 序列化失败发生在写锁之外：写锁未被占用，后续写入仍可正常进行
        assertDoesNotThrow(() -> store.save(simpleBundle("ev-after-failure", "after", "after-item")));
    }

    @Test
    @DisplayName("真实 SQLite：两个不同 ID 并发 save 均成功")
    void save_concurrentDifferentIds_bothSucceed() throws Exception {
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            Future<EvidenceBundle> first = pool.submit(() -> {
                start.await();
                return store.save(simpleBundle("ev-concurrent-1", "one", "item-c1"));
            });
            Future<EvidenceBundle> second = pool.submit(() -> {
                start.await();
                return store.save(simpleBundle("ev-concurrent-2", "two", "item-c2"));
            });
            start.countDown();
            assertEquals("ev-concurrent-1", first.get(15, TimeUnit.SECONDS).bundleId());
            assertEquals("ev-concurrent-2", second.get(15, TimeUnit.SECONDS).bundleId());
        }
        assertEquals(1, count("SELECT COUNT(*) FROM evidence_bundles WHERE bundle_id='ev-concurrent-1'"));
        assertEquals(1, count("SELECT COUNT(*) FROM evidence_bundles WHERE bundle_id='ev-concurrent-2'"));
    }

    @Test
    @DisplayName("真实 SQLite：同 ID 并发 save 干净失败——恰好一个成功，另一个冲突，落库只有赢家")
    void save_concurrentSameId_exactlyOneWins() throws Exception {
        CopyOnWriteArrayList<Object> outcomes = new CopyOnWriteArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> first = pool.submit(race(start, outcomes, "claim-A"));
            Future<?> second = pool.submit(race(start, outcomes, "claim-B"));
            start.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        }

        assertEquals(2, outcomes.size());
        List<EvidenceBundle> winners = outcomes.stream()
                .filter(EvidenceBundle.class::isInstance).map(EvidenceBundle.class::cast).toList();
        List<Throwable> losers = outcomes.stream()
                .filter(Throwable.class::isInstance).map(Throwable.class::cast).toList();
        assertEquals(1, winners.size(), "同 ID 并发只能有一个成功");
        assertEquals(1, losers.size(), "另一个必须干净失败，不能静默成功");
        assertSqliteConstraintFailure(losers.get(0));

        assertEquals(1, count("SELECT COUNT(*) FROM evidence_bundles WHERE bundle_id='ev-race'"));
        String storedClaim = jdbc.queryForObject(
                "SELECT claim FROM evidence_bundles WHERE bundle_id='ev-race'", String.class);
        assertEquals(winners.get(0).claim(), storedClaim);
        String winnerItem = "item-" + storedClaim;
        String loserItem = winnerItem.equals("item-claim-A") ? "item-claim-B" : "item-claim-A";
        assertEquals(1, count("SELECT COUNT(*) FROM evidence_items WHERE id='" + winnerItem + "'"));
        assertEquals(0, count("SELECT COUNT(*) FROM evidence_items WHERE id='" + loserItem + "'"));
    }

    @Test
    @DisplayName("真实 SQLite：写锁忙超过 5 秒 → DatabaseWriteUnavailableException 且不产生新记录")
    void save_writeLockBusy_timesOutWithoutRecord() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> sqlite.executeWrite(dbPath, () -> {
            held.countDown();
            awaitQuietly(release);
            return null;
        }), "evidence-write-lock-holder");
        holder.start();
        assertTrue(held.await(5, TimeUnit.SECONDS), "占用线程应已持有写锁");

        try {
            long startNanos = System.nanoTime();
            SqliteConfig.DatabaseWriteUnavailableException failure = assertThrows(
                    SqliteConfig.DatabaseWriteUnavailableException.class,
                    () -> store.save(simpleBundle("ev-busy", "busy", "item-busy")));
            long waitedMs = (System.nanoTime() - startNanos) / 1_000_000;

            assertEquals("AUTHORIZATION_STORE_BUSY", failure.code());
            assertTrue(waitedMs >= 4_500, "应等待接近 5 秒才超时，实际 " + waitedMs + "ms");
        } finally {
            release.countDown();
            holder.join(10_000);
        }

        assertEquals(0, count("SELECT COUNT(*) FROM evidence_bundles WHERE bundle_id='ev-busy'"));
        assertEquals(0, count("SELECT COUNT(*) FROM evidence_items WHERE id='item-busy'"));
    }

    @Test
    @DisplayName("真实 SQLite：等待写锁的线程被中断 → 取消异常、保留中断标志且不产生新记录")
    void save_interruptedWhileWaitingLock_writesNothingAndKeepsFlag() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> sqlite.executeWrite(dbPath, () -> {
            held.countDown();
            awaitQuietly(release);
            return null;
        }), "evidence-write-lock-holder-2");
        holder.start();
        assertTrue(held.await(5, TimeUnit.SECONDS), "占用线程应已持有写锁");

        AtomicReference<String> code = new AtomicReference<>();
        AtomicBoolean flagPreserved = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            try {
                store.save(simpleBundle("ev-interrupted", "interrupted", "item-interrupted"));
            } catch (SqliteConfig.DatabaseWriteUnavailableException failure) {
                code.set(failure.code());
                flagPreserved.set(Thread.currentThread().isInterrupted());
            }
        }, "evidence-write-lock-waiter");
        try {
            worker.start();
            Thread.sleep(300);
            worker.interrupt();
            worker.join(10_000);
        } finally {
            release.countDown();
            holder.join(10_000);
        }

        assertEquals("AUTHORIZATION_CANCELLED", code.get(), "中断应映射为取消而不是成功或静默丢弃");
        assertTrue(flagPreserved.get(), "中断标志必须保留");
        assertEquals(0, count("SELECT COUNT(*) FROM evidence_bundles WHERE bundle_id='ev-interrupted'"));
        assertEquals(0, count("SELECT COUNT(*) FROM evidence_items WHERE id='item-interrupted'"));
    }

    @Test
    @DisplayName("真实 SQLite：历史短 ID 与新建全长 UUID 混存均可读，损坏 meta_json 读取容错")
    void findById_readsLegacyShortIdsAndToleratesCorruptMeta() {
        jdbc.update("""
                INSERT INTO evidence_bundles
                    (bundle_id, session_id, run_id, agent_id, kind, claim, verdict, created_at)
                VALUES ('ev-1a2b3c4d', 'legacy-session', NULL, 'legacy-agent', 'journey',
                        'legacy claim', 'verified', '2026-06-05T09:00:00Z')
                """);
        jdbc.update("""
                INSERT INTO evidence_items
                    (id, bundle_id, type, summary, blob_sha256, meta_json, sort_order)
                VALUES ('legacy-item', 'ev-1a2b3c4d', 'command', 'legacy summary', NULL, NULL, 0)
                """);
        jdbc.update("""
                INSERT INTO evidence_items
                    (id, bundle_id, type, summary, blob_sha256, meta_json, sort_order)
                VALUES ('legacy-corrupt-item', 'ev-1a2b3c4d', 'command', 'corrupt meta', NULL, 'not-json{', 1)
                """);

        EvidenceBundle legacy = store.findById("ev-1a2b3c4d").orElseThrow();
        assertEquals("legacy claim", legacy.claim());
        assertEquals("legacy-agent", legacy.agentId());
        assertEquals(List.of("legacy-item", "legacy-corrupt-item"),
                legacy.items().stream().map(EvidenceItem::id).toList());
        assertEquals(Map.of(), legacy.items().get(1).meta(), "损坏 meta_json 读取时降级为空 map");

        EvidenceBundle fresh = store.save(EvidenceBundle.builder()
                .sessionId("legacy-session").kind("journey").verdict("verified")
                .claim("new claim").build());
        assertTrue(fresh.bundleId().matches(
                        "ev-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
                "新建默认 ID 应为 ev- + 完整 UUID，实际：" + fresh.bundleId());

        assertEquals(2, store.findBySession("legacy-session").size(), "短 ID 与全长 UUID 应能混存混读");
        assertTrue(store.findById(fresh.bundleId()).isPresent());
        assertTrue(store.findById("ev-1a2b3c4d").isPresent());
    }

    // ─── helpers ──────────────────────────────────────────────────────

    private static EvidenceBundle simpleBundle(String bundleId, String claim, String itemId) {
        return new EvidenceBundle(bundleId, "session-dup", "agent-dup", "journey", claim, "verified",
                List.of(new EvidenceItem(itemId, "command", "summary", null, Map.of())),
                Instant.parse("2026-06-05T10:00:00Z"));
    }

    private Runnable race(CountDownLatch start, List<Object> outcomes, String claim) {
        return () -> {
            try {
                start.await();
                outcomes.add(store.save(simpleBundle("ev-race", claim, "item-" + claim)));
            } catch (Throwable failure) {
                outcomes.add(failure);
            }
        };
    }

    /**
     * Xerial 驱动不提供 SQLState，Spring 只能把主键冲突归为 UncategorizedSQLException；
     * 这里确认根因确实是 SQLite 约束失败，而不是被吞掉或静默成功。
     */
    private static void assertSqliteConstraintFailure(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) root = root.getCause();
        String message = root.getMessage() == null ? "" : root.getMessage();
        assertTrue(message.contains("SQLITE_CONSTRAINT"),
                "重复 ID 必须以 SQLite 约束失败明确报错，实际根因：" + root);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }
}
