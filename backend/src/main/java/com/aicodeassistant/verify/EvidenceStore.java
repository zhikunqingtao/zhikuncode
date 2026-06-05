package com.aicodeassistant.verify;

import com.aicodeassistant.security.SensitiveDataFilter;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final SensitiveDataFilter sensitiveDataFilter;
    private final Path blobRoot;

    public EvidenceStore(@Qualifier("projectJdbcTemplate") JdbcTemplate jdbcTemplate,
                         ObjectMapper objectMapper,
                         SensitiveDataFilter sensitiveDataFilter) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.sensitiveDataFilter = sensitiveDataFilter;
        this.blobRoot = Path.of(System.getProperty("user.dir"), ".ai-code-assistant", "blobs");
    }

    /**
     * 保存证据包及其关联条目。
     */
    public EvidenceBundle save(EvidenceBundle bundle) {
        String bundleId = bundle.bundleId() != null
                ? bundle.bundleId()
                : "ev-" + UUID.randomUUID().toString().substring(0, 8);
        Instant createdAt = bundle.createdAt() != null ? bundle.createdAt() : Instant.now();

        jdbcTemplate.update("""
                INSERT OR REPLACE INTO evidence_bundles
                    (bundle_id, session_id, agent_id, kind, claim, verdict, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                bundleId,
                bundle.sessionId(),
                bundle.agentId(),
                bundle.kind(),
                filterText(bundle.claim()),
                bundle.verdict(),
                createdAt.toString()
        );

        List<EvidenceItem> savedItems = new ArrayList<>();
        if (bundle.items() != null) {
            int sortOrder = 0;
            for (EvidenceItem item : bundle.items()) {
                String itemId = item.id() != null ? item.id() : UUID.randomUUID().toString();
                String metaJson = serializeMeta(item.meta());

                jdbcTemplate.update("""
                        INSERT OR REPLACE INTO evidence_items
                            (id, bundle_id, type, summary, blob_sha256, meta_json, sort_order)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                        itemId,
                        bundleId,
                        item.type(),
                        filterText(item.summary()),
                        item.blobSha256(),
                        metaJson,
                        sortOrder++
                );
                savedItems.add(new EvidenceItem(itemId, item.type(), item.summary(), item.blobSha256(), item.meta()));
            }
        }

        return new EvidenceBundle(bundleId, bundle.sessionId(), bundle.agentId(),
                bundle.kind(), bundle.claim(), bundle.verdict(), savedItems, createdAt);
    }

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
     * 入参防御：合法的 SHA-256 哈希为 64 个十六进制字符；非法输入直接返回空，
     * 避免 {@link #blobPath(String)} 的 substring 越界，防止 API 层 HTTP 500。
     */
    public Optional<byte[]> readBlob(String sha256) {
        if (sha256 == null || sha256.length() != 64) {
            return Optional.empty();
        }
        Path blobPath = blobPath(sha256);
        if (!Files.exists(blobPath)) return Optional.empty();
        try {
            return Optional.of(Files.readAllBytes(blobPath));
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
            log.warn("Failed to serialize meta", e);
            return null;
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
