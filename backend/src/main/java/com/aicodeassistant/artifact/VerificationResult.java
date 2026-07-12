package com.aicodeassistant.artifact;

import java.util.List;

/**
 * 验证结果 — 清单验证完成后的汇总。
 */
public record VerificationResult(
    String status,       // "verified" | "partial" | "failed"
    int verifiedFiles,
    int failedFiles,
    int totalFiles,
    List<FailureDetail> failures
) {
    public record FailureDetail(String path, String reason) {}
}
