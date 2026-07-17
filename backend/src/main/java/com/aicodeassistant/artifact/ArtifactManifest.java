package com.aicodeassistant.artifact;

import java.time.Instant;
import java.util.List;

public record ArtifactManifest(String id, String runId, String sessionId, String workspaceRoot, String status,
                               Instant createdAt, Instant updatedAt, List<ArtifactEntry> entries) {
    public int totalFiles() { return entries == null ? 0 : entries.size(); }
    public int verifiedFiles() { return entries == null ? 0 : (int) entries.stream().filter(ArtifactEntry::verified).count(); }
    public int failedFiles() { return entries == null ? 0 : (int) entries.stream().filter(e -> "failed".equals(e.state())).count(); }
    public Instant verifiedAt() { return List.of("verified","partial","failed","unverified").contains(status) ? updatedAt : null; }
}
