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
        try {
            artifactManifestService.getManifest(event.getRunId()).ifPresent(manifest -> {
                if (manifest.totalFiles() > 0) {
                    runs.setVerification(event.getRunId(), RunEnvelope.VerificationStatus.NOT_REQUESTED,
                            RunEnvelope.VerificationStatus.PENDING, "artifact_manifest");
                    VerificationResult result=artifactManifestService.verify(manifest.id());
                    RunEnvelope.VerificationStatus terminal=switch(result.status()){
                        case "verified" -> RunEnvelope.VerificationStatus.VERIFIED;
                        case "unverified" -> RunEnvelope.VerificationStatus.UNVERIFIED;
                        default -> RunEnvelope.VerificationStatus.FAILED;
                    };
                    runs.setVerification(event.getRunId(),RunEnvelope.VerificationStatus.PENDING,
                            terminal,result.status());
                }
            });
        } catch (Exception e) {
            log.warn("Artifact verification failed for run {}: {}", event.getRunId(), e.getMessage());
            runs.setVerification(event.getRunId(), RunEnvelope.VerificationStatus.PENDING,
                    RunEnvelope.VerificationStatus.FAILED, "verification_exception");
        }
    }
}
