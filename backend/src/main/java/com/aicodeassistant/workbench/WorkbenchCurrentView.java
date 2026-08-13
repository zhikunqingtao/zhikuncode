package com.aicodeassistant.workbench;

import com.aicodeassistant.artifact.ArtifactManifest;
import com.aicodeassistant.interaction.InteractionView;
import com.aicodeassistant.run.RunEnvelope;
import com.aicodeassistant.verify.EvidenceBundle;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 简洁工作台一次读取所需的 Root Run 级权威投影。 */
public record WorkbenchCurrentView(
        CorrelationMode correlationMode,
        String requestMessageId,
        String resultMessageId,
        RunEnvelope rootRun,
        MessageView request,
        MessageView result,
        StructuredSummaryExtractor.Summary structuredSummary,
        DeliveryView delivery,
        VerificationView verification,
        int pendingActionCount,
        List<InteractionView> pendingActions,
        List<ActivityView> activities,
        PreviousDeliveryView previousAvailableDelivery,
        FailureView currentFailure
) {
    public enum CorrelationMode { EXACT, LEGACY_FALLBACK }
    public enum CriterionStatus { PASSED, FAILED, PARTIAL, NOT_VERIFIED }

    public record MessageView(String messageId, String text, Instant timestamp) { }
    public record DeliveryView(List<ArtifactManifest> manifests,
                               List<DeliveryFileView> files,
                               int totalFiles,
                               String primaryArtifactPath) { }
    public record DeliveryFileView(String manifestId, String workspaceRoot,
                                   String id, String filePath, String relativePath,
                                   String operation, String state, Long fileSize,
                                   boolean verified, String mismatchDetail,
                                   boolean primary) { }
    public record ActivityView(String id, String sessionId, String runId,
                               String operationType, String summary, String toolName,
                               String status, long timestamp, Integer duration,
                               int fileCount, String decision, Object toolResult,
                               List<Map<String, Object>> changedFiles, Object insight) { }
    public record CriterionView(String id, String type, String text,
                                CriterionStatus status, String detail,
                                String evidenceBundleId) { }
    public record VerificationView(List<CriterionView> businessCriteria,
                                   List<CriterionView> technicalChecks,
                                   List<EvidenceBundle> evidence,
                                   CriterionStatus overallStatus) { }
    public record PreviousDeliveryView(String rootRunId, Instant finishedAt,
                                       MessageView result, DeliveryView delivery) { }
    public record FailureView(String status, String reason) { }
}
