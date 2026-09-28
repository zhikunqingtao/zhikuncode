package com.aicodeassistant.artifact;

import com.aicodeassistant.run.RunCompletedEvent;
import com.aicodeassistant.run.RunControlService;
import com.aicodeassistant.run.RunEnvelope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtifactVerificationListenerTest {

    @Test
    void automaticVerificationDetailCarriesScopeAndManifestId() {
        ArtifactManifestService manifests = mock(ArtifactManifestService.class);
        RunControlService runs = mock(RunControlService.class);
        when(manifests.getManifest("run-1")).thenReturn(Optional.of(manifest()));
        when(manifests.verify("manifest-1")).thenReturn(new VerificationResult("verified", 1, 0, 1, List.of()));

        new ArtifactVerificationListener(manifests, runs).onRunCompleted(event());

        verify(runs).setVerification("run-1", RunEnvelope.VerificationStatus.NOT_REQUESTED,
                RunEnvelope.VerificationStatus.PENDING,
                "scope=artifact_manifest;manifestId=manifest-1;result=pending");
        verify(runs).setVerification("run-1", RunEnvelope.VerificationStatus.PENDING,
                RunEnvelope.VerificationStatus.VERIFIED,
                "scope=artifact_manifest;manifestId=manifest-1;result=verified");
    }

    @Test
    void exceptionDetailKeepsManifestIdWhenAvailable() {
        ArtifactManifestService manifests = mock(ArtifactManifestService.class);
        RunControlService runs = mock(RunControlService.class);
        when(manifests.getManifest("run-1")).thenReturn(Optional.of(manifest()));
        when(manifests.verify("manifest-1")).thenThrow(new IllegalStateException("boom"));

        new ArtifactVerificationListener(manifests, runs).onRunCompleted(event());

        verify(runs).setVerification("run-1", RunEnvelope.VerificationStatus.PENDING,
                RunEnvelope.VerificationStatus.FAILED,
                "scope=artifact_manifest;manifestId=manifest-1;result=verification_exception");
    }

    private static ArtifactManifest manifest() {
        Instant now = Instant.now();
        ArtifactEntry entry = new ArtifactEntry("entry-1", "manifest-1", "tool-1", "/tmp/report.html",
                "created", "declared", null, null, null, null, null, null, now, now);
        return new ArtifactManifest("manifest-1", "run-1", "session-1", "/tmp",
                "open", now, now, List.of(entry));
    }

    private static RunCompletedEvent event() {
        return new RunCompletedEvent(new Object(), "run-1", "session-1", 0, 0.0, 0, 0);
    }
}
