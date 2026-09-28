package com.aicodeassistant.artifact;

import com.aicodeassistant.run.RunCompletedEvent;
import com.aicodeassistant.run.RunControlService;
import com.aicodeassistant.run.RunEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class ArtifactVerificationListener {
    private static final Logger log = LoggerFactory.getLogger(ArtifactVerificationListener.class);
    private final ArtifactManifestService artifactManifestService;
    private final RunControlService runs;

    public ArtifactVerificationListener(ArtifactManifestService artifactManifestService,
                                        RunControlService runs) {
        this.artifactManifestService = artifactManifestService;
        this.runs = runs;
    }

    @Async("artifactVerificationExecutor")
    @EventListener
    public void onRunCompleted(RunCompletedEvent event) {
        ArtifactManifest manifest;
        try {
            manifest = artifactManifestService.getManifest(event.getRunId()).orElse(null);
        } catch (Exception e) {
            log.warn("Artifact verification failed for run {}: {}", event.getRunId(), e.getMessage());
            runs.setVerification(event.getRunId(), RunEnvelope.VerificationStatus.PENDING,
                    RunEnvelope.VerificationStatus.FAILED, verificationDetail(null, "verification_exception"));
            return;
        }
        if (manifest == null || manifest.totalFiles() == 0) return;
        String manifestId = manifest.id();
        try {
            runs.setVerification(event.getRunId(), RunEnvelope.VerificationStatus.NOT_REQUESTED,
                    RunEnvelope.VerificationStatus.PENDING, verificationDetail(manifestId, "pending"));
            VerificationResult result=artifactManifestService.verify(manifestId);
            RunEnvelope.VerificationStatus terminal=switch(result.status()){
                case "verified" -> RunEnvelope.VerificationStatus.VERIFIED;
                case "unverified" -> RunEnvelope.VerificationStatus.UNVERIFIED;
                default -> RunEnvelope.VerificationStatus.FAILED;
            };
            runs.setVerification(event.getRunId(),RunEnvelope.VerificationStatus.PENDING,
                    terminal, verificationDetail(manifestId, result.status()));
        } catch (Exception e) {
            log.warn("Artifact verification failed for run {}: {}", event.getRunId(), e.getMessage());
            runs.setVerification(event.getRunId(), RunEnvelope.VerificationStatus.PENDING,
                    RunEnvelope.VerificationStatus.FAILED, verificationDetail(manifestId, "verification_exception"));
        }
    }

    /** 自动产物验证事件统一附带范围与 manifestId，便于事件读取方判定覆盖范围。 */
    static String verificationDetail(String manifestId, String result) {
        return "scope=artifact_manifest;manifestId=" + (manifestId == null ? "unknown" : manifestId)
                + ";result=" + result;
    }
}
