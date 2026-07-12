package com.aicodeassistant.artifact;

import java.time.Instant;
import java.util.List;

/**
 * 产物清单 — 记录一次运行生成的所有文件变更。
 */
public record ArtifactManifest(
    String id,
    String runId,
    String sessionId,
    String status,
    int totalFiles,
    int verifiedFiles,
    int failedFiles,
    Instant createdAt,
    Instant verifiedAt,
    List<ArtifactEntry> entries
) {}
