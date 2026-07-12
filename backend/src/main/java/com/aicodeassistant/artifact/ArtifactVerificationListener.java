package com.aicodeassistant.artifact;

import com.aicodeassistant.run.RunCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
public class ArtifactVerificationListener {
    private static final Logger log = LoggerFactory.getLogger(ArtifactVerificationListener.class);
    private final ArtifactManifestService artifactManifestService;

    public ArtifactVerificationListener(ArtifactManifestService artifactManifestService) {
        this.artifactManifestService = artifactManifestService;
    }

    @Async
    @EventListener
    public void onRunCompleted(RunCompletedEvent event) {
        try {
            ArtifactManifest manifest = artifactManifestService.generateManifest(
                event.getRunId(), event.getSessionId());
            if (manifest != null && manifest.totalFiles() > 0) {
                artifactManifestService.verify(manifest.id());
            }
        } catch (Exception e) {
            log.warn("Artifact verification failed for run {}: {}", event.getRunId(), e.getMessage());
        }
    }
}
