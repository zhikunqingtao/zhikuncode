package com.aicodeassistant.session.merge;

import java.util.List;
import java.util.Set;

/** Data shared by the merge services; historical data never becomes executable session state. */
public final class MergeHandoffData {
    private MergeHandoffData() { }
    public static String sha256(String text) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public static final String PROCESSOR_VERSION = "handoff-v2-1";
    public static final Set<String> SECTIONS = Set.of("goals_constraints", "state_conclusions", "changes",
            "artifacts", "validation_failures", "conflicts_todos");
    public static final Set<String> ITEM_STATUSES = Set.of("recorded", "completed", "in_progress", "pending",
            "failed", "unverified", "conflict", "inferred", "unknown");
    public record Execution(String resolvedModel, String workingDirectory, String targetTitle,
                            String processorVersion, String promptVersion) { }
    public record Ledger(String operationId, String idempotencyKey, String paramsJson, String targetSessionId,
                         String status, String stage, String packagePath, int protocolVersion, long runEpoch,
                         int snapshotVersion, String snapshotHash, String handoffHash, Execution execution,
                         String errorCode, String error, String retryAt, String resultJson, String usageJson) { }
    public record Unit(String operationId, String unitId, String stage, long ordinal, String inputHash,
                       String inputJson, String model, String state, int attemptCount, long runEpoch,
                       String resultPath, String resultHash) { }
    public record Progress(long completedUnits, long knownUnits, boolean totalFinal) { }
    public record Origin(String sessionId, String recordId, String version, String kind) { }
    public record RecordEntry(String recordRef, Origin origin, String role, String createdAt,
                              String rawRef, String processingPolicy) { }
    public record FileEntry(String ref, String recordRef, String sourceId, int part, String path,
                            long bytes, String sha256, String kind) { }
    /** Offsets are program-generated UTF-16 boundaries in bounded text files, never model output. */
    public record InputRef(String ref, String sourceId, int start, int end) { }
    public record UnitInput(List<InputRef> inputs, List<String> childUnitIds) { }
    public record Item(String itemId, String section, String content, String status, List<String> evidence) { }
    public record Detail(int schemaVersion, List<Item> items) { }
}
