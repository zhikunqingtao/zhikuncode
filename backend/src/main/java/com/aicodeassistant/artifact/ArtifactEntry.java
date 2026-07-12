package com.aicodeassistant.artifact;

import java.time.Instant;

/**
 * 产物条目 — 清单中的单个文件记录。
 */
public record ArtifactEntry(
    String id,
    String manifestId,
    String filePath,
    String operation,    // "created" | "modified" | "deleted"
    String expectedHash,
    String actualHash,
    Long fileSize,
    boolean verified,
    String mismatchDetail,
    Instant createdAt
) {}
