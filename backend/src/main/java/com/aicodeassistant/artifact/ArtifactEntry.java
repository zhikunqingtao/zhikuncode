package com.aicodeassistant.artifact;

import java.time.Instant;

public record ArtifactEntry(String id, String manifestId, String toolUseId, String filePath,
                            String operation, String state, String expectedHash, String actualHash,
                            Long fileSize, String requiredValidatorId, String validatorResultJson,
                            String failureCode, Instant createdAt, Instant updatedAt) {
    public boolean verified() { return "integrity_verified".equals(state) || "content_verified".equals(state); }
    public String mismatchDetail() { return failureCode; }
}
