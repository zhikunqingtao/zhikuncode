package com.aicodeassistant.verify;

import com.aicodeassistant.config.database.DatabaseResolver;
import com.aicodeassistant.config.database.SqliteConfig;
import com.aicodeassistant.security.SensitiveDataFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * EvidenceStore 边缘场景测试 — 覆盖大文件、并发、非法输入、文件系统异常、序列化失败等场景。
 *
 * <p>风格沿用 {@link EvidenceStoreTest}：通过 JUnit5 {@link TempDir} 提供临时目录并显式注入 blob 根目录，
 * JdbcTemplate 使用 Mockito mock 以避免拉起 SQLite。
 */
class EvidenceStoreEdgeCaseTest {

    private JdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;
    private SensitiveDataFilter sensitiveDataFilter;
    private EvidenceStore store;

    @TempDir
    Path tempDir;

    private Path blobRoot;

    @BeforeEach
    void setUp() {
        blobRoot = tempDir.resolve(".ai-code-assistant").resolve("blobs");

        jdbcTemplate = mock(JdbcTemplate.class);
        objectMapper = new ObjectMapper();
        sensitiveDataFilter = mock(SensitiveDataFilter.class);
        when(sensitiveDataFilter.filter(anyString())).thenAnswer(inv -> inv.getArgument(0));

        // 真实 SqliteConfig 提供有界写锁；事务管理器用直通 mock，SQL 断言仍在 mock JdbcTemplate 上。
        DatabaseResolver resolver = new DatabaseResolver("", tempDir.toString());
        SqliteConfig sqliteConfig = new SqliteConfig(resolver);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        store = new EvidenceStore(jdbcTemplate, sqliteConfig, resolver, transactionManager,
                objectMapper, sensitiveDataFilter, blobRoot);
    }

    // ─── Blob 写入类 ──────────────────────────────────────────────

    @Test
    @DisplayName("TC-EC-01 saveBlob - 10MB+ 大文件写入 SHA-256 正确且字节完整落盘")
    void saveBlob_largePayload_storesAndHashesCorrectly() throws Exception {
        // 10MB + 余数，覆盖非整页边界
        byte[] payload = new byte[10 * 1024 * 1024 + 7];
        new Random(42).nextBytes(payload);
        String expected = sha256Hex(payload);

        String returned = store.saveBlob(payload);

        assertEquals(expected, returned, "返回的 SHA-256 应等于内容哈希");

        Path blobPath = blobPathOf(expected);
        assertTrue(Files.exists(blobPath), "10MB blob 应落盘到分片目录");
        assertEquals(payload.length, Files.size(blobPath), "落盘字节数必须与原始一致");

        Optional<byte[]> readBack = store.readBlob(expected);
        assertTrue(readBack.isPresent());
        assertArrayEquals(payload, readBack.get(), "回读内容必须逐字节一致");
    }

    @Test
    @DisplayName("TC-EC-02 saveBlob - 多线程并发写入相同内容不抛异常且最终数据一致")
    void saveBlob_concurrentSameContent_isIdempotent() throws Exception {
        byte[] payload = "concurrent payload — same sha".getBytes();
        String expected = sha256Hex(payload);
        int threads = 10;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return store.saveBlob(payload);
                }));
            }
            start.countDown();
            for (Future<String> f : futures) {
                assertEquals(expected, f.get(10, TimeUnit.SECONDS),
                        "所有线程都应返回同一个 SHA-256");
            }
        } finally {
            pool.shutdownNow();
        }

        Path blobPath = blobPathOf(expected);
        assertTrue(Files.exists(blobPath));
        assertArrayEquals(payload, Files.readAllBytes(blobPath),
                "并发写入后文件内容必须与原始一致，不得损坏");
    }

    @Test
    @DisplayName("TC-EC-03 saveBlob - 文件系统创建目录失败时抛出 RuntimeException")
    void saveBlob_diskWriteFailure_throwsRuntime() throws Exception {
        // 把 blobs 占位为常规文件，使 Files.createDirectories(blobs/<prefix>) 失败
        Files.createDirectories(blobRoot.getParent());
        Files.writeString(blobRoot, "I am a regular file, not a directory.");

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> store.saveBlob("any payload".getBytes()));
        assertTrue(ex.getMessage().contains("Failed to save blob"),
                "异常消息应包含 saveBlob 失败信息：" + ex.getMessage());
        assertNotNull(ex.getCause(), "原始 IOException 必须作为 cause 保留");
        assertTrue(ex.getCause() instanceof IOException, "cause 应为 IOException 类型");
    }

    // ─── Blob 读取类 ──────────────────────────────────────────────

    @Test
    @DisplayName("TC-EC-04 readBlob - SHA-256 长度 62 (短2位) 返回 Optional.empty()")
    void readBlob_invalidLength62_returnsEmpty() {
        String invalid = "a".repeat(62);
        assertTrue(store.readBlob(invalid).isEmpty());
    }

    @Test
    @DisplayName("TC-EC-05 readBlob - SHA-256 长度 65 (长1位) 返回 Optional.empty()")
    void readBlob_invalidLength65_returnsEmpty() {
        String invalid = "a".repeat(65);
        assertTrue(store.readBlob(invalid).isEmpty());
    }

    @Test
    @DisplayName("TC-EC-06 readBlob - 64 字符路径形态输入（穿越/绝对路径）即使逃逸目标真实存在也被拒绝")
    void readBlob_pathTraversal_returnsEmpty() throws IOException {
        // 在 blobRoot 之外真实创建逃逸目标位置的诱饵文件：blobPath(".."+"a"*62) 会被拼成
        // blobRoot/../..aaa…，交给内核解析后正是 blobRoot.getParent() 下的这个文件。
        Path decoy = blobRoot.getParent().resolve(".." + "a".repeat(62));
        Files.createDirectories(decoy.getParent());
        try {
            Files.writeString(decoy, "TOP-SECRET-OUTSIDE-BLOBROOT");

            // [A] 单段一级逃逸形态：目标文件真实存在，修复前会读出 blobRoot 之外的内容，
            // 现在必须被格式白名单 + 路径包含校验直接拒绝。
            String paddedTraversal = ".." + "a".repeat(62);
            assertEquals(64, paddedTraversal.length());
            assertTrue(store.readBlob(paddedTraversal).isEmpty(),
                    "逃逸目标真实存在时也必须被拒绝，不得读出 blobRoot 之外的文件");

            // [D] 前导斜杠 + 冗余斜杠的绝对路径形态（64 字符）：修复前整串会被当作绝对路径
            // 交给内核解析（/etc/passwd 在类 Unix 系统真实存在），现在必须被格式白名单拒绝。
            String absoluteForm = "/".repeat(54) + "etc/passwd";
            assertEquals(64, absoluteForm.length());
            assertTrue(store.readBlob(absoluteForm).isEmpty(),
                    "绝对路径形态的 64 字符输入必须被拒绝，而非依赖目标不存在");

            // [F] "../"×18 + "etc/passwd"（64 字符）相对穿越形态，规范化后同样指向 /etc/passwd
            String traversalForm = "../".repeat(18) + "etc/passwd";
            assertEquals(64, traversalForm.length());
            assertTrue(store.readBlob(traversalForm).isEmpty(),
                    "含 ../ 段的穿越输入必须被拒绝，而非依赖目标不存在");

            // 短穿越与长度不足输入仍被拒绝（长度/格式校验）
            assertTrue(store.readBlob("../../etc/passwd").isEmpty());
            assertTrue(store.readBlob("..").isEmpty());
            // 前导斜杠 + 63 个字符（长度=64）：修复前会被当作绝对路径 /aaa… 交付内核解析，
            // 之前的断言"返回空"只是因为该路径不存在，属伪安全；现在由格式白名单直接拒绝。
            assertTrue(store.readBlob("/" + "a".repeat(63)).isEmpty(),
                    "前导斜杠 + 非 hex 的 64 字符必须被格式白名单拒绝，而非依赖目标不存在");
        } finally {
            Files.deleteIfExists(decoy);
        }
    }

    @Test
    @DisplayName("TC-EC-06B readBlob - 合法 64 位 hex 可正常读出，非 hex 的 64 字符被格式白名单拒绝")
    void readBlob_hexWhitelist_acceptsValidHexAndRejectsNonHex() throws IOException {
        String validHex = "ab12".repeat(16);
        Path blobPath = blobRoot.resolve(validHex.substring(0, 2)).resolve(validHex);
        Files.createDirectories(blobPath.getParent());
        byte[] payload = "legit blob content".getBytes();
        Files.write(blobPath, payload);
        try {
            assertArrayEquals(payload, store.readBlob(validHex).orElseThrow(),
                    "合法 64 位 hex 必须可正常读出");
            assertTrue(store.readBlob("g".repeat(64)).isEmpty(),
                    "非 hex 的 64 字符输入必须被格式白名单拒绝");
        } finally {
            Files.deleteIfExists(blobPath);
        }
    }

    @Test
    @DisplayName("TC-EC-07 readBlob - blob 存在后被外部删除应返回 Optional.empty()")
    void readBlob_fileDeletedExternally_returnsEmpty() throws Exception {
        byte[] payload = "to be deleted".getBytes();
        String sha = store.saveBlob(payload);

        Path blobPath = blobPathOf(sha);
        assertTrue(Files.exists(blobPath));
        Files.delete(blobPath);

        assertTrue(store.readBlob(sha).isEmpty(),
                "底层 blob 文件被删除后必须降级为 Optional.empty(),不得抛异常");
    }

    @Test
    @DisplayName("TC-EC-08 readBlob - 空字符串入参返回 Optional.empty()")
    void readBlob_emptyString_returnsEmpty() {
        assertTrue(store.readBlob("").isEmpty());
    }

    @Test
    @DisplayName("TC-EC-09 readBlob - null 入参返回 Optional.empty() 而非 NPE")
    void readBlob_null_returnsEmpty() {
        assertDoesNotThrow(() -> {
            assertTrue(store.readBlob(null).isEmpty());
        });
    }

    // ─── 去重机制类 ──────────────────────────────────────────────

    @Test
    @DisplayName("TC-EC-10 saveBlob - 相同内容两次调用文件只写入一次（mtime 不变）")
    void saveBlob_dedupSameContent_writesOnlyOnce() throws Exception {
        byte[] payload = "duplicate-write-check".getBytes();

        String sha1 = store.saveBlob(payload);
        Path blobPath = blobPathOf(sha1);
        assertTrue(Files.exists(blobPath));
        FileTime mtime1 = Files.getLastModifiedTime(blobPath);

        // 等待大于文件系统 mtime 分辨率（多数 macOS/APFS 为 1ns~1ms,Linux ext4 ~1ns,
        // 但 Java FileTime 实测可能为秒级；这里给足 1100ms 以避免假阳性。）
        Thread.sleep(1100);

        String sha2 = store.saveBlob(payload);
        FileTime mtime2 = Files.getLastModifiedTime(blobPath);

        assertEquals(sha1, sha2, "相同内容必须返回相同 SHA");
        assertEquals(mtime1, mtime2,
                "去重命中时不应再次写文件（mtime 必须保持不变）");
    }

    @Test
    @DisplayName("TC-EC-11 saveBlob - 不同内容前2字符相同时分别落盘到同一分片目录")
    void saveBlob_sameShardPrefixDifferentContent_storesBoth() throws Exception {
        // 暴力寻找前 2 字符相同的两个 SHA-256（256 桶，期望 ~20 次出现碰撞）
        byte[] a = null, b = null;
        String shaA = null, shaB = null;
        for (int i = 0; i < 100_000; i++) {
            byte[] candidate = ("payload-" + i).getBytes();
            String sha = sha256Hex(candidate);
            if (a == null) {
                a = candidate;
                shaA = sha;
            } else if (!sha.equals(shaA) && sha.startsWith(shaA.substring(0, 2))) {
                b = candidate;
                shaB = sha;
                break;
            }
        }
        assertNotNull(b, "应能在 10 万样本内找到前2字符相同的不同内容");

        String returnedA = store.saveBlob(a);
        String returnedB = store.saveBlob(b);
        assertEquals(shaA, returnedA);
        assertEquals(shaB, returnedB);

        Path shardDir = blobRoot.resolve(shaA.substring(0, 2));
        assertTrue(Files.exists(shardDir.resolve(shaA)));
        assertTrue(Files.exists(shardDir.resolve(shaB)));
        assertArrayEquals(a, Files.readAllBytes(shardDir.resolve(shaA)));
        assertArrayEquals(b, Files.readAllBytes(shardDir.resolve(shaB)));
    }

    // ─── 数据库操作类 ──────────────────────────────────────────────

    @Test
    @DisplayName("TC-EC-12 findById - 不存在的 bundleId 返回 Optional.empty() 且不查询 items 表")
    void findById_nonExisting_returnsEmptyAndSkipsItemsQuery() {
        when(jdbcTemplate.queryForList(
                eq("SELECT * FROM evidence_bundles WHERE bundle_id = ?"),
                eq("ev-ghost"))).thenReturn(List.of());

        Optional<EvidenceBundle> result = store.findById("ev-ghost");

        assertTrue(result.isEmpty());
        verify(jdbcTemplate, never()).queryForList(
                eq("SELECT * FROM evidence_items WHERE bundle_id = ? ORDER BY sort_order ASC"),
                anyString());
    }

    @Test
    @DisplayName("TC-EC-13 findBySession - 无匹配会话返回空列表（非 null）")
    void findBySession_noMatch_returnsEmptyList() {
        when(jdbcTemplate.queryForList(
                eq("SELECT * FROM evidence_bundles WHERE session_id = ? ORDER BY created_at DESC"),
                eq("ghost-session"))).thenReturn(List.of());

        List<EvidenceBundle> result = store.findBySession("ghost-session");

        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(jdbcTemplate, never()).queryForList(
                eq("SELECT * FROM evidence_items WHERE bundle_id = ? ORDER BY sort_order ASC"),
                anyString());
    }

    @Test
    @DisplayName("TC-EC-14 save - meta 序列化失败必须明确报错，不允许静默丢弃元数据或产生写入")
    void save_metaSerializationFailure_failsWithoutAnyWrite() {
        Map<String, Object> cyclicMeta = new HashMap<>();
        cyclicMeta.put("self", cyclicMeta);

        EvidenceItem item = new EvidenceItem(
                "it-cyc", "screenshot", "circular meta", null, cyclicMeta);
        EvidenceBundle bundle = new EvidenceBundle(
                "ev-cyc", "sess", "agent", "journey", "claim", "verified",
                List.of(item), Instant.parse("2026-06-05T10:00:00Z"));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> store.save(bundle), "序列化失败必须抛到调用方");
        assertTrue(failure.getMessage().contains("EVIDENCE_META_SERIALIZATION_FAILED"),
                "应携带明确错误码，实际：" + failure.getMessage());
        assertNotNull(failure.getCause(), "原始序列化异常必须作为 cause 保留");

        // 序列化在事务外失败：bundle 与 item 都不得落库
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    // ─── 并发安全类 ──────────────────────────────────────────────

    @Test
    @DisplayName("TC-EC-15 save - 10 线程并发写入不同 bundle 全部成功且无异常")
    void save_concurrentDifferentBundles_allSucceed() throws Exception {
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<EvidenceBundle>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    EvidenceBundle b = new EvidenceBundle(
                            "ev-c-" + idx, "sess-c", "agent-c",
                            "journey", "claim-" + idx, "verified",
                            List.of(),
                            Instant.parse("2026-06-05T10:00:00Z"));
                    return store.save(b);
                }));
            }
            start.countDown();
            for (int i = 0; i < threads; i++) {
                EvidenceBundle saved = futures.get(i).get(10, TimeUnit.SECONDS);
                assertNotNull(saved);
                assertEquals("ev-c-" + i, saved.bundleId());
            }
        } finally {
            pool.shutdownNow();
        }

        // 每个 bundle 一次写入，共 N 次（无 items）
        verify(jdbcTemplate, times(threads)).update(anyString(), any(Object[].class));
    }

    // ─── 工具方法 ──────────────────────────────────────────────

    private Path blobPathOf(String sha) {
        return blobRoot.resolve(sha.substring(0, 2)).resolve(sha);
    }

    private static String sha256Hex(byte[] data) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(data);
        StringBuilder sb = new StringBuilder(64);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
