package com.aicodeassistant.verify;

import com.aicodeassistant.security.SensitiveDataFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * EvidenceStore 单元测试 — 覆盖 evidence_bundles / evidence_items 持久化与 Blob 文件存储。
 */
class EvidenceStoreTest {

    private JdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;
    private SensitiveDataFilter sensitiveDataFilter;
    private EvidenceStore store;

    private String origUserDir;
    private Path tempUserDir;

    @BeforeEach
    void setUp() throws IOException {
        // EvidenceStore 构造时使用 user.dir 拼接 blobRoot，临时切换到独立目录避免污染工作区。
        origUserDir = System.getProperty("user.dir");
        tempUserDir = Files.createTempDirectory("evidence-store-test-");
        System.setProperty("user.dir", tempUserDir.toString());

        jdbcTemplate = mock(JdbcTemplate.class);
        objectMapper = new ObjectMapper();
        sensitiveDataFilter = mock(SensitiveDataFilter.class);
        // 默认不改写文本，便于断言原始值传入 SQL。
        when(sensitiveDataFilter.filter(anyString())).thenAnswer(inv -> inv.getArgument(0));

        store = new EvidenceStore(jdbcTemplate, objectMapper, sensitiveDataFilter);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (origUserDir != null) {
            System.setProperty("user.dir", origUserDir);
        }
        if (tempUserDir != null && Files.exists(tempUserDir)) {
            try (var stream = Files.walk(tempUserDir)) {
                stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // 测试清理失败不影响断言
                    }
                });
            }
        }
    }

    @Test
    @DisplayName("TC-ES-01 save - 仅 bundle 写入 evidence_bundles 表，参数顺序与列对齐")
    void save_bundleWithoutItems_insertsBundleRow() {
        Instant createdAt = Instant.parse("2026-06-05T10:00:00Z");
        EvidenceBundle bundle = new EvidenceBundle(
                "ev-001", "session-1", "agent-1",
                "journey", "claim text", "verified",
                List.of(),
                createdAt
        );

        EvidenceBundle saved = store.save(bundle);

        assertEquals("ev-001", saved.bundleId());
        assertEquals(createdAt, saved.createdAt());
        assertTrue(saved.items().isEmpty());

        verify(jdbcTemplate, times(1)).update(
                argThat((String sql) -> sql.contains("INSERT OR REPLACE INTO evidence_bundles")
                        && sql.contains("bundle_id")
                        && sql.contains("created_at")),
                eq("ev-001"),
                eq("session-1"),
                eq("agent-1"),
                eq("journey"),
                eq("claim text"),
                eq("verified"),
                eq("2026-06-05T10:00:00Z")
        );
        // 没有 item 时不应触发 evidence_items 的额外写入
        verify(jdbcTemplate, times(1)).update(anyString(), any(Object[].class));
        // claim 必须经过敏感词过滤器
        verify(sensitiveDataFilter).filter("claim text");
    }

    @Test
    @DisplayName("TC-ES-02 save - 级联写入 evidence_items，逐条插入并保留 sort_order")
    void save_bundleWithItems_insertsEachItem() {
        Instant createdAt = Instant.parse("2026-06-05T10:00:00Z");
        EvidenceItem item1 = new EvidenceItem("item-1", "screenshot", "Step 1 ok", "sha-aaa", Map.of("k", "v"));
        EvidenceItem item2 = new EvidenceItem("item-2", "command", "Step 2 ok", null, Map.of());

        EvidenceBundle bundle = new EvidenceBundle(
                "ev-002", "session-2", "agent-1",
                "journey", "claim", "verified",
                List.of(item1, item2),
                createdAt
        );

        EvidenceBundle saved = store.save(bundle);
        assertEquals(2, saved.items().size());

        // 1 次 bundle 插入 + 2 次 item 插入
        verify(jdbcTemplate, times(3)).update(anyString(), any(Object[].class));

        verify(jdbcTemplate).update(
                argThat((String sql) -> sql.contains("INSERT OR REPLACE INTO evidence_items")),
                eq("item-1"), eq("ev-002"), eq("screenshot"), eq("Step 1 ok"),
                eq("sha-aaa"), argThat((String json) -> json != null && json.contains("\"k\"")), eq(0)
        );
        verify(jdbcTemplate).update(
                argThat((String sql) -> sql.contains("INSERT OR REPLACE INTO evidence_items")),
                eq("item-2"), eq("ev-002"), eq("command"), eq("Step 2 ok"),
                eq((String) null), eq((String) null), eq(1)
        );
    }

    @Test
    @DisplayName("TC-ES-03 findById - 查询命中返回 Optional.of(bundle)，列映射正确")
    void findById_existingBundle_returnsMappedBundle() {
        Map<String, Object> bundleRow = new LinkedHashMap<>();
        bundleRow.put("bundle_id", "ev-101");
        bundleRow.put("session_id", "session-X");
        bundleRow.put("agent_id", "agent-Y");
        bundleRow.put("kind", "journey");
        bundleRow.put("claim", "verify login");
        bundleRow.put("verdict", "verified");
        bundleRow.put("created_at", "2026-06-05T11:00:00Z");

        Map<String, Object> itemRow = new LinkedHashMap<>();
        itemRow.put("id", "i-1");
        itemRow.put("type", "screenshot");
        itemRow.put("summary", "Step ok");
        itemRow.put("blob_sha256", "sha-bbb");
        itemRow.put("meta_json", "{\"foo\":\"bar\"}");

        when(jdbcTemplate.queryForList(
                eq("SELECT * FROM evidence_bundles WHERE bundle_id = ?"), eq("ev-101")))
                .thenReturn(List.of(bundleRow));
        when(jdbcTemplate.queryForList(
                eq("SELECT * FROM evidence_items WHERE bundle_id = ? ORDER BY sort_order ASC"), eq("ev-101")))
                .thenReturn(List.of(itemRow));

        Optional<EvidenceBundle> result = store.findById("ev-101");

        assertTrue(result.isPresent());
        EvidenceBundle b = result.get();
        assertEquals("ev-101", b.bundleId());
        assertEquals("session-X", b.sessionId());
        assertEquals("agent-Y", b.agentId());
        assertEquals("journey", b.kind());
        assertEquals("verify login", b.claim());
        assertEquals("verified", b.verdict());
        assertEquals(Instant.parse("2026-06-05T11:00:00Z"), b.createdAt());
        assertEquals(1, b.items().size());

        EvidenceItem fetched = b.items().get(0);
        assertEquals("i-1", fetched.id());
        assertEquals("screenshot", fetched.type());
        assertEquals("Step ok", fetched.summary());
        assertEquals("sha-bbb", fetched.blobSha256());
        assertEquals("bar", fetched.meta().get("foo"));
    }

    @Test
    @DisplayName("TC-ES-04 findById - 不存在时返回 Optional.empty()，不再触发 items 查询")
    void findById_missing_returnsEmpty() {
        when(jdbcTemplate.queryForList(
                eq("SELECT * FROM evidence_bundles WHERE bundle_id = ?"), eq("ev-missing")))
                .thenReturn(List.of());

        Optional<EvidenceBundle> result = store.findById("ev-missing");

        assertTrue(result.isEmpty());
        verify(jdbcTemplate, times(1)).queryForList(anyString(), any(Object[].class));
    }

    @Test
    @DisplayName("TC-ES-05 saveBlob - 计算 SHA-256 并写入 blob 目录，返回 hex")
    void saveBlob_writesContentToShardedPath() throws Exception {
        byte[] payload = "hello evidence".getBytes();
        String expectedSha = sha256Hex(payload);

        String returned = store.saveBlob(payload);

        assertEquals(expectedSha, returned);

        Path blobPath = tempUserDir.resolve(".ai-code-assistant").resolve("blobs")
                .resolve(expectedSha.substring(0, 2)).resolve(expectedSha);
        assertTrue(Files.exists(blobPath), "blob 文件应存在于分片目录");
        assertArrayEquals(payload, Files.readAllBytes(blobPath));

        // 读回链路也应可用
        Optional<byte[]> readBack = store.readBlob(expectedSha);
        assertTrue(readBack.isPresent());
        assertArrayEquals(payload, readBack.get());
    }

    @Test
    @DisplayName("TC-ES-06 findBySession - 多 bundle 按 sessionId 检索并附带 items")
    void findBySession_multipleBundles_returnsAllWithItems() {
        Map<String, Object> row1 = new HashMap<>();
        row1.put("bundle_id", "ev-A");
        row1.put("session_id", "sess-9");
        row1.put("agent_id", null);
        row1.put("kind", "journey");
        row1.put("claim", null);
        row1.put("verdict", "verified");
        row1.put("created_at", "2026-06-05T12:00:00Z");

        Map<String, Object> row2 = new HashMap<>();
        row2.put("bundle_id", "ev-B");
        row2.put("session_id", "sess-9");
        row2.put("agent_id", null);
        row2.put("kind", "qa");
        row2.put("claim", null);
        row2.put("verdict", "failed");
        row2.put("created_at", "2026-06-05T11:00:00Z");

        when(jdbcTemplate.queryForList(
                eq("SELECT * FROM evidence_bundles WHERE session_id = ? ORDER BY created_at DESC"),
                eq("sess-9")))
                .thenReturn(List.of(row1, row2));
        when(jdbcTemplate.queryForList(
                eq("SELECT * FROM evidence_items WHERE bundle_id = ? ORDER BY sort_order ASC"),
                eq("ev-A")))
                .thenReturn(List.of());
        when(jdbcTemplate.queryForList(
                eq("SELECT * FROM evidence_items WHERE bundle_id = ? ORDER BY sort_order ASC"),
                eq("ev-B")))
                .thenReturn(List.of());

        List<EvidenceBundle> bundles = store.findBySession("sess-9");

        assertEquals(2, bundles.size());
        assertEquals("ev-A", bundles.get(0).bundleId());
        assertEquals("verified", bundles.get(0).verdict());
        assertEquals("ev-B", bundles.get(1).bundleId());
        assertEquals("failed", bundles.get(1).verdict());

        // 主查询参数必须为 sessionId
        verify(jdbcTemplate).queryForList(
                eq("SELECT * FROM evidence_bundles WHERE session_id = ? ORDER BY created_at DESC"),
                eq("sess-9"));
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
