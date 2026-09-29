package com.aicodeassistant.verify;

import com.aicodeassistant.config.database.DatabaseResolver;
import com.aicodeassistant.config.database.SqliteConfig;
import com.aicodeassistant.security.SensitiveDataFilter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * 证据包存储服务 — 负责持久化和查询 RV-1 验证证据。
 * <p>
 * Blob 存储路径: {projectRoot}/.ai-code-assistant/blobs/{sha256前2位}/{sha256}
 */
@Service
public class EvidenceStore {

    private static final Logger log = LoggerFactory.getLogger(EvidenceStore.class);

    /** 运行时写锁等待上限；不是整个保存操作的硬截止，拿到锁后事务正常提交完成。 */
    private static final Duration WRITE_LOCK_TIMEOUT = Duration.ofSeconds(5);

    private final JdbcTemplate jdbcTemplate;
    private final SqliteConfig sqliteConfig;
    private final Path dbPath;
    private final TransactionTemplate transaction;
    private final ObjectMapper objectMapper;
    private final SensitiveDataFilter sensitiveDataFilter;
    private final Path blobRoot;

    @org.springframework.beans.factory.annotation.Autowired
    public EvidenceStore(@Qualifier("projectJdbcTemplate") JdbcTemplate jdbcTemplate,
                         SqliteConfig sqliteConfig,
                         DatabaseResolver databaseResolver,
                         @Qualifier("projectTransactionManager") PlatformTransactionManager transactionManager,
                         ObjectMapper objectMapper,
                         SensitiveDataFilter sensitiveDataFilter) {
        this(jdbcTemplate, sqliteConfig, databaseResolver, transactionManager, objectMapper,
                sensitiveDataFilter,
                Path.of(System.getProperty("user.dir"), ".ai-code-assistant", "blobs"));
    }

    /**
     * 测试专用构造函数 — 允许显式注入 blobRoot 路径，避免测试依赖 System.getProperty("user.dir")。
     */
    EvidenceStore(JdbcTemplate jdbcTemplate,
                  SqliteConfig sqliteConfig,
                  DatabaseResolver databaseResolver,
                  PlatformTransactionManager transactionManager,
                  ObjectMapper objectMapper,
                  SensitiveDataFilter sensitiveDataFilter,
                  Path blobRoot) {
        this.jdbcTemplate = jdbcTemplate;
        this.sqliteConfig = sqliteConfig;
        this.dbPath = databaseResolver.getProjectDbPath(Path.of(System.getProperty("user.dir")));
        this.transaction = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
        this.sensitiveDataFilter = sensitiveDataFilter;
        this.blobRoot = blobRoot;
    }

    /**
     * 保存证据包及其关联条目。
     * <p>
     * 完整保存或完整失败：元数据序列化、文本过滤与缺失 ID 生成都在写锁与事务之外完成，
     * 任一步失败都不产生任何写入；bundle 与全部 items 在同一个事务内使用普通 INSERT 写入，
     * 重复 ID 直接失败，不覆盖旧数据、不自动换 ID 重试、不把冲突当成功。
     */
    public EvidenceBundle save(EvidenceBundle bundle) {
        String bundleId = bundle.bundleId() != null
                ? bundle.bundleId()
                : "ev-" + UUID.randomUUID();
        Instant createdAt = bundle.createdAt() != null ? bundle.createdAt() : Instant.now();
        String filteredClaim = filterText(bundle.claim());

        List<PreparedItem> preparedItems = new ArrayList<>();
        if (bundle.items() != null) {
            int sortOrder = 0;
            for (EvidenceItem item : bundle.items()) {
                preparedItems.add(new PreparedItem(
                        item.id() != null ? item.id() : UUID.randomUUID().toString(),
                        item,
                        filterText(item.summary()),
                        serializeMeta(item.meta()),
                        sortOrder++
                ));
            }
        }

        // 先有界获取写锁（最多等待 5 秒），拿到锁后再开启事务写入 bundle 与全部 items；
        // 锁忙或等待被中断时不产生任何新记录，中断标志由 executeWriteBounded 保留。
        sqliteConfig.executeWriteBounded(dbPath, WRITE_LOCK_TIMEOUT, () -> transaction.execute(status -> {
            jdbcTemplate.update("""
                    INSERT INTO evidence_bundles
                        (bundle_id, session_id, run_id, agent_id, kind, claim, verdict, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    bundleId,
                    bundle.sessionId(),
                    bundle.runId(),
                    bundle.agentId(),
                    bundle.kind(),
                    filteredClaim,
                    bundle.verdict(),
                    createdAt.toString()
            );

            for (PreparedItem prepared : preparedItems) {
                jdbcTemplate.update("""
                        INSERT INTO evidence_items
                            (id, bundle_id, type, summary, blob_sha256, meta_json, sort_order)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                        prepared.itemId(),
                        bundleId,
                        prepared.item().type(),
                        prepared.summary(),
                        prepared.item().blobSha256(),
                        prepared.metaJson(),
                        prepared.sortOrder()
                );
            }
            return null;
        }));

        List<EvidenceItem> savedItems = new ArrayList<>(preparedItems.size());
        for (PreparedItem prepared : preparedItems) {
            EvidenceItem item = prepared.item();
            savedItems.add(new EvidenceItem(prepared.itemId(), item.type(), item.summary(),
                    item.blobSha256(), item.meta()));
        }
        return new EvidenceBundle(bundleId, bundle.sessionId(), bundle.runId(), bundle.agentId(),
                bundle.kind(), bundle.claim(), bundle.verdict(), savedItems, createdAt);
    }

    /** 事务外准备好的证据条目写入参数。 */
    private record PreparedItem(String itemId, EvidenceItem item, String summary,
                                String metaJson, int sortOrder) { }

    /**
     * 按 bundleId 查询单个证据包（含关联条目）。
     */
    public Optional<EvidenceBundle> findById(String bundleId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM evidence_bundles WHERE bundle_id = ?", bundleId);
        if (rows.isEmpty()) return Optional.empty();
        Map<String, Object> row = rows.get(0);
        List<EvidenceItem> items = queryItems(bundleId);
        return Optional.of(mapBundle(row, items));
    }

    /**
     * 按会话 ID 查询所有证据包（按 created_at DESC）。
     */
    public List<EvidenceBundle> findBySession(String sessionId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM evidence_bundles WHERE session_id = ? ORDER BY created_at DESC",
                sessionId);
        List<EvidenceBundle> bundles = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String bid = (String) row.get("bundle_id");
            List<EvidenceItem> items = queryItems(bid);
            bundles.add(mapBundle(row, items));
        }
        return bundles;
    }

    /** 只读取明确绑定到给定 Run 子树的证据包。 */
    public List<EvidenceBundle> findByRunIds(Collection<String> runIds) {
        if (runIds == null || runIds.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(runIds.size(), "?"));
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM evidence_bundles WHERE run_id IN (" + placeholders
                        + ") ORDER BY created_at DESC",
                runIds.toArray());
        return rows.stream().map(row -> mapBundle(row,
                queryItems(String.valueOf(row.get("bundle_id"))))).toList();
    }

    /**
     * 存储二进制 Blob，返回 SHA-256 十六进制字符串。去重：相同内容不重复写入。
     */
    public String saveBlob(byte[] content) {
        String sha256 = sha256Hex(content);
        Path blobPath = blobPath(sha256);
        if (!Files.exists(blobPath)) {
            try {
                Files.createDirectories(blobPath.getParent());
                Files.write(blobPath, content);
                log.debug("Blob saved: {}", sha256);
            } catch (IOException e) {
                throw new RuntimeException("Failed to save blob: " + sha256, e);
            }
        }
        return sha256;
    }

    /**
     * 读取 Blob 内容。
     * <p>
     * 入参防御（两层）：① 格式白名单——合法 SHA-256 必须是 64 个十六进制字符，其余输入
     * （包括 {@code ".." + 62 个字符} 这类长度恰为 64 的路径形态字符串）直接返回空，
     * 同时避免 {@link #blobPath(String)} 的 substring 越界；② 纵深防御——规范化后的
     * blob 路径必须仍位于 blobRoot 之内，否则返回空，防止目录逃逸读取 blob 根目录之外的文件。
     */
    public Optional<byte[]> readBlob(String sha256) {
        if (sha256 == null || !sha256.matches("[0-9a-fA-F]{64}")) {
            return Optional.empty();
        }
        Path root = blobRoot.toAbsolutePath().normalize();
        Path target = blobPath(sha256).toAbsolutePath().normalize();
        if (!target.startsWith(root)) {
            return Optional.empty();
        }
        if (!Files.exists(target)) return Optional.empty();
        try {
            return Optional.of(Files.readAllBytes(target));
        } catch (IOException e) {
            log.warn("Failed to read blob: {}", sha256, e);
            return Optional.empty();
        }
    }

    // ─── Private helpers ──────────────────────────────────────────────

    private List<EvidenceItem> queryItems(String bundleId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM evidence_items WHERE bundle_id = ? ORDER BY sort_order ASC",
                bundleId);
        List<EvidenceItem> items = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            items.add(new EvidenceItem(
                    (String) r.get("id"),
                    (String) r.get("type"),
                    (String) r.get("summary"),
                    (String) r.get("blob_sha256"),
                    deserializeMeta((String) r.get("meta_json"))
            ));
        }
        return items;
    }

    private EvidenceBundle mapBundle(Map<String, Object> row, List<EvidenceItem> items) {
        return new EvidenceBundle(
                (String) row.get("bundle_id"),
                (String) row.get("session_id"),
                (String) row.get("run_id"),
                (String) row.get("agent_id"),
                (String) row.get("kind"),
                (String) row.get("claim"),
                (String) row.get("verdict"),
                items,
                Instant.parse((String) row.get("created_at"))
        );
    }

    private String filterText(String text) {
        if (text == null) return null;
        return sensitiveDataFilter.filter(text);
    }

    private String serializeMeta(Map<String, Object> meta) {
        if (meta == null || meta.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(meta);
        } catch (Exception e) {
            // 证据必须完整保存：元数据无法序列化时明确失败，不能静默丢弃。
            throw new IllegalArgumentException("EVIDENCE_META_SERIALIZATION_FAILED", e);
        }
    }

    private Map<String, Object> deserializeMeta(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to deserialize meta_json", e);
            return Map.of();
        }
    }

    private Path blobPath(String sha256) {
        String prefix = sha256.substring(0, 2);
        return blobRoot.resolve(prefix).resolve(sha256);
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
