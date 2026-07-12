package com.aicodeassistant.artifact;

import com.aicodeassistant.config.database.DatabaseResolver;
import com.aicodeassistant.config.database.SqliteConfig;
import com.aicodeassistant.run.RunEvent;
import com.aicodeassistant.run.RunEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

/**
 * 产物清单服务 — 核心业务逻辑。
 * <p>
 * 负责从运行事件中提取文件变更记录，生成清单并验证文件完整性。
 */
@Service
public class ArtifactManifestService {

    private static final Logger log = LoggerFactory.getLogger(ArtifactManifestService.class);

    private static final Set<String> FILE_MODIFYING_TOOLS = Set.of(
        "Write", "Edit", "NotebookEdit", "Bash"
    );

    private static final Set<String> FILE_PATH_KEYS = Set.of(
        "filePath", "path", "file_path"
    );

    private static final long MAX_VERIFY_FILE_SIZE = 200 * 1024 * 1024L; // 200MB

    private final JdbcTemplate jdbcTemplate;
    private final SqliteConfig sqliteConfig;
    private final Path dbPath;
    private final RunEventRepository eventRepository;
    private final ObjectMapper objectMapper;

    private static final RowMapper<ArtifactEntry> ENTRY_ROW_MAPPER = (rs, rowNum) -> new ArtifactEntry(
        rs.getString("id"),
        rs.getString("manifest_id"),
        rs.getString("file_path"),
        rs.getString("operation"),
        rs.getString("expected_hash"),
        rs.getString("actual_hash"),
        rs.getObject("file_size") != null ? rs.getLong("file_size") : null,
        rs.getInt("verified") == 1,
        rs.getString("mismatch_detail"),
        Instant.parse(rs.getString("created_at"))
    );

    private static final RowMapper<ArtifactManifest> MANIFEST_ROW_MAPPER = (rs, rowNum) -> {
        String verifiedAtStr = rs.getString("verified_at");
        return new ArtifactManifest(
            rs.getString("id"),
            rs.getString("run_id"),
            rs.getString("session_id"),
            rs.getString("status"),
            rs.getInt("total_files"),
            rs.getInt("verified_files"),
            rs.getInt("failed_files"),
            Instant.parse(rs.getString("created_at")),
            verifiedAtStr != null ? Instant.parse(verifiedAtStr) : null,
            List.of() // entries loaded separately
        );
    };

    public ArtifactManifestService(@Qualifier("projectJdbcTemplate") JdbcTemplate jdbcTemplate,
                                    SqliteConfig sqliteConfig,
                                    DatabaseResolver databaseResolver,
                                    RunEventRepository eventRepository,
                                    ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.sqliteConfig = sqliteConfig;
        this.dbPath = databaseResolver.getProjectDbPath(Path.of(System.getProperty("user.dir")));
        this.eventRepository = eventRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 生成产物清单 — 从运行事件中提取文件变更记录。
     */
    public ArtifactManifest generateManifest(String runId, String sessionId) {
        List<RunEvent> toolEvents = eventRepository.getEventsByType(runId, "tool_result");

        List<ArtifactEntry> entries = new ArrayList<>();
        String manifestId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        for (RunEvent event : toolEvents) {
            try {
                JsonNode eventData = objectMapper.readTree(event.eventData());

                // 跳过失败的工具结果
                if (eventData.has("isError") && eventData.get("isError").asBoolean()) {
                    continue;
                }

                String toolName = extractToolName(eventData);
                if (toolName == null || !FILE_MODIFYING_TOOLS.contains(toolName)) {
                    continue;
                }

                String filePath = extractFilePath(eventData);
                if (filePath == null || filePath.isBlank()) {
                    continue;
                }

                String operation = extractOperation(eventData, toolName);
                String expectedHash = extractExpectedHash(eventData);

                ArtifactEntry entry = new ArtifactEntry(
                    UUID.randomUUID().toString(),
                    manifestId,
                    filePath,
                    operation,
                    expectedHash,
                    null,  // actualHash — set during verification
                    null,  // fileSize — set during verification
                    false,
                    null,
                    now
                );
                entries.add(entry);
            } catch (Exception e) {
                log.debug("Failed to parse event data for run={}, seq={}: {}",
                    runId, event.seq(), e.getMessage());
            }
        }

        if (entries.isEmpty()) {
            return null;
        }

        ArtifactManifest manifest = new ArtifactManifest(
            manifestId, runId, sessionId, "pending",
            entries.size(), 0, 0, now, null, entries
        );

        // Persist manifest and entries
        sqliteConfig.executeWriteVoid(dbPath, () -> {
            jdbcTemplate.update("""
                INSERT INTO artifact_manifests
                (id, run_id, session_id, status, total_files, verified_files, failed_files, created_at, verified_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                manifest.id(), manifest.runId(), manifest.sessionId(), manifest.status(),
                manifest.totalFiles(), manifest.verifiedFiles(), manifest.failedFiles(),
                manifest.createdAt().toString(), null
            );

            for (ArtifactEntry entry : entries) {
                jdbcTemplate.update("""
                    INSERT INTO artifact_entries
                    (id, manifest_id, file_path, operation, expected_hash, actual_hash, file_size, verified, mismatch_detail, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    entry.id(), entry.manifestId(), entry.filePath(), entry.operation(),
                    entry.expectedHash(), entry.actualHash(), entry.fileSize(),
                    entry.verified() ? 1 : 0, entry.mismatchDetail(),
                    entry.createdAt().toString()
                );
            }
        });

        log.info("Generated manifest: id={}, run={}, entries={}", manifestId, runId, entries.size());
        return manifest;
    }

    /**
     * 验证清单 — 检查每个文件条目的完整性。
     */
    public VerificationResult verify(String manifestId) {
        List<ArtifactEntry> entries = loadEntries(manifestId);
        int verified = 0;
        int failed = 0;
        List<VerificationResult.FailureDetail> failures = new ArrayList<>();

        // Collect batch update data
        record EntryUpdate(String id, String actualHash, Long fileSize, boolean verified, String mismatchDetail) {}
        List<EntryUpdate> entryUpdates = new ArrayList<>();

        for (ArtifactEntry entry : entries) {
            Path filePath = Path.of(entry.filePath());
            boolean fileExists = Files.exists(filePath);
            String actualHash = null;
            Long fileSize = null;
            boolean entryVerified = false;
            String mismatchDetail = null;

            switch (entry.operation()) {
                case "deleted" -> {
                    if (!fileExists) {
                        entryVerified = true;
                    } else {
                        mismatchDetail = "File still exists after deletion";
                    }
                }
                case "created", "modified" -> {
                    if (!fileExists) {
                        mismatchDetail = "File does not exist";
                    } else {
                        try {
                            fileSize = Files.size(filePath);
                            if (fileSize > MAX_VERIFY_FILE_SIZE) {
                                // Skip hash for large files, only verify existence
                                entryVerified = true;
                                log.info("Skipping hash verification for large file ({} bytes): {}", fileSize, filePath);
                            } else {
                                actualHash = computeSha256(filePath);
                                if (entry.expectedHash() != null && !entry.expectedHash().equals(actualHash)) {
                                    mismatchDetail = "Hash mismatch: expected=" + entry.expectedHash()
                                        + ", actual=" + actualHash;
                                } else {
                                    entryVerified = true;
                                }
                            }
                        } catch (Exception e) {
                            mismatchDetail = "Failed to verify: " + e.getMessage();
                        }
                    }
                }
                default -> {
                    mismatchDetail = "Unknown operation: " + entry.operation();
                }
            }

            if (entryVerified) {
                verified++;
            } else {
                failed++;
                failures.add(new VerificationResult.FailureDetail(entry.filePath(), mismatchDetail));
            }

            entryUpdates.add(new EntryUpdate(entry.id(), actualHash, fileSize, entryVerified, mismatchDetail));
        }

        // Determine overall status
        String status;
        if (failed == 0 && verified > 0) {
            status = "verified";
        } else if (verified > 0 && failed > 0) {
            status = "partial";
        } else {
            status = "failed";
        }

        // Batch update all entries and manifest in one write transaction
        Instant now = Instant.now();
        final int fVerified = verified;
        final int fFailed = failed;
        final String fStatus = status;
        sqliteConfig.executeWriteVoid(dbPath, () -> {
            for (EntryUpdate eu : entryUpdates) {
                jdbcTemplate.update("""
                    UPDATE artifact_entries SET actual_hash = ?, file_size = ?, verified = ?, mismatch_detail = ?
                    WHERE id = ?
                    """,
                    eu.actualHash(), eu.fileSize(), eu.verified() ? 1 : 0, eu.mismatchDetail(), eu.id()
                );
            }
            jdbcTemplate.update("""
                UPDATE artifact_manifests SET status = ?, verified_files = ?, failed_files = ?, verified_at = ?
                WHERE id = ?
                """,
                fStatus, fVerified, fFailed, now.toString(), manifestId
            );
        });

        log.info("Verified manifest: id={}, status={}, verified={}, failed={}",
            manifestId, status, verified, failed);

        return new VerificationResult(status, verified, failed, entries.size(), failures);
    }

    /**
     * 获取清单（含条目） — 按 run_id 查询。
     */
    public Optional<ArtifactManifest> getManifest(String runId) {
        List<ArtifactManifest> manifests = jdbcTemplate.query(
            "SELECT * FROM artifact_manifests WHERE run_id = ? ORDER BY created_at DESC LIMIT 1",
            MANIFEST_ROW_MAPPER, runId
        );
        if (manifests.isEmpty()) {
            return Optional.empty();
        }
        ArtifactManifest manifest = manifests.getFirst();
        List<ArtifactEntry> entries = loadEntries(manifest.id());
        return Optional.of(new ArtifactManifest(
            manifest.id(), manifest.runId(), manifest.sessionId(), manifest.status(),
            manifest.totalFiles(), manifest.verifiedFiles(), manifest.failedFiles(),
            manifest.createdAt(), manifest.verifiedAt(), entries
        ));
    }

    /**
     * 按 manifest ID 获取清单（含条目）。
     */
    public Optional<ArtifactManifest> getManifestById(String manifestId) {
        List<ArtifactManifest> manifests = jdbcTemplate.query(
            "SELECT * FROM artifact_manifests WHERE id = ?",
            MANIFEST_ROW_MAPPER, manifestId
        );
        if (manifests.isEmpty()) {
            return Optional.empty();
        }
        ArtifactManifest manifest = manifests.getFirst();
        List<ArtifactEntry> entries = loadEntries(manifest.id());
        return Optional.of(new ArtifactManifest(
            manifest.id(), manifest.runId(), manifest.sessionId(), manifest.status(),
            manifest.totalFiles(), manifest.verifiedFiles(), manifest.failedFiles(),
            manifest.createdAt(), manifest.verifiedAt(), entries
        ));
    }

    /**
     * 计算文件 SHA-256 哈希值。
     */
    public String computeSha256(Path path) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream is = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = is.read(buffer)) != -1) {
                digest.update(buffer, 0, len);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    // ───── 内部方法 ─────

    private List<ArtifactEntry> loadEntries(String manifestId) {
        return jdbcTemplate.query(
            "SELECT * FROM artifact_entries WHERE manifest_id = ? ORDER BY created_at ASC",
            ENTRY_ROW_MAPPER, manifestId
        );
    }

    private String extractToolName(JsonNode eventData) {
        if (eventData.has("toolName")) {
            return eventData.get("toolName").asText();
        }
        if (eventData.has("tool_name")) {
            return eventData.get("tool_name").asText();
        }
        if (eventData.has("tool")) {
            return eventData.get("tool").asText();
        }
        return null;
    }

    private String extractFilePath(JsonNode eventData) {
        for (String key : FILE_PATH_KEYS) {
            if (eventData.has(key)) {
                String val = eventData.get(key).asText();
                if (val != null && !val.isBlank() && !"null".equals(val)) {
                    return val;
                }
            }
        }
        // Check nested "input" or "args" object
        JsonNode input = eventData.has("input") ? eventData.get("input") :
                         eventData.has("args") ? eventData.get("args") : null;
        if (input != null && input.isObject()) {
            for (String key : FILE_PATH_KEYS) {
                if (input.has(key)) {
                    String val = input.get(key).asText();
                    if (val != null && !val.isBlank() && !"null".equals(val)) {
                        return val;
                    }
                }
            }
        }
        return null;
    }

    private String extractOperation(JsonNode eventData, String toolName) {
        if (eventData.has("operation")) {
            return eventData.get("operation").asText();
        }
        // Infer from tool name
        return switch (toolName) {
            case "Write" -> "created";
            case "Edit", "NotebookEdit" -> "modified";
            case "Bash" -> "modified";
            default -> "modified";
        };
    }

    private String extractExpectedHash(JsonNode eventData) {
        if (eventData.has("expectedHash")) {
            return eventData.get("expectedHash").asText();
        }
        if (eventData.has("expected_hash")) {
            return eventData.get("expected_hash").asText();
        }
        return null;
    }
}
