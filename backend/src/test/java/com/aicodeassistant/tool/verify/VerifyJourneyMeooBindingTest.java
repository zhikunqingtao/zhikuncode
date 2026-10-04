package com.aicodeassistant.tool.verify;

import com.aicodeassistant.artifact.meoo.MeooPublicationPolicy;
import com.aicodeassistant.artifact.meoo.MeooException;
import com.aicodeassistant.config.FeatureFlagService;
import com.aicodeassistant.notify.NotificationService;
import com.aicodeassistant.service.*;
import com.aicodeassistant.tool.*;
import com.aicodeassistant.verify.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class VerifyJourneyMeooBindingTest {
    @TempDir Path root;
    @Test void bindsEvidenceOnlyWhenPublicationBytesRemainTheSameDuringVerification() {
        var client=mock(PythonCapabilityAwareClient.class);when(client.isCapabilityAvailable("HTTP_API")).thenReturn(true);
        var factory=mock(VerifierFactory.class);var verifier=mock(Verifier.class);
        when(factory.selectVerifier(any(),anyString())).thenReturn(verifier);
        when(verifier.verify(any(),anyString())).thenReturn(new JourneyResult("verified",null,List.of(),Map.of()));
        var evidence=mock(EvidenceStore.class);when(evidence.save(any())).thenAnswer(c->c.getArgument(0));
        var tool=new VerifyJourneyTool(client,mock(DevServerLauncher.class),factory,mock(PreviewStackDetector.class),evidence,
            mock(SimpMessagingTemplate.class),mock(FeatureFlagService.class),mock(ActivityRepository.class),new ObjectMapper(),mock(NotificationService.class));
        var policy=mock(MeooPublicationPolicy.class);tool.setMeooPublicationPolicy(policy);
        var first=new MeooPublicationPolicy.Snapshot(root,".","static","Demo","account",List.of(),12,"a".repeat(64),"");
        var changed=new MeooPublicationPolicy.Snapshot(root,".","static","Demo","account",List.of(),13,"b".repeat(64),"");
        when(policy.inspect(any(),any(),eq(false))).thenReturn(first);
        var input=ToolInput.from(Map.of("journey",List.of(Map.of("action","http_get","url","/")),"verification_mode","http_api","publication_path",".","publication_runtime","static"));
        var ctx=ToolUseContext.of(root.toString(),"s").withCurrentRunId("r");
        assertThat(tool.call(input,ctx).isError()).isFalse();
        var captor=org.mockito.ArgumentCaptor.forClass(EvidenceBundle.class);verify(evidence).save(captor.capture());
        assertThat(captor.getValue().items()).anySatisfy(item->assertThat(item.meta()).containsEntry("meooSnapshotSha256",first.sha256()));
        clearInvocations(evidence);
        when(policy.inspect(any(),any(),eq(false))).thenReturn(first,changed);
        assertThat(tool.call(input,ctx).failureCode()).isEqualTo("MEOO_VERIFICATION_STALE");
        verify(evidence,never()).save(any());
    }

    @Test void stalePublicationRetainsExecutedVerdictAndFallbackFactsWithoutArchivingOrRerunning() {
        assertExecutedPublicationFailure("MEOO_VERIFICATION_STALE");
    }

    @Test void postVerificationInspectionFailureRetainsExecutedVerdictAndFallbackFactsWithoutArchivingOrRerunning() {
        assertExecutedPublicationFailure("MEOO_SNAPSHOT_CHANGED");
    }

    private void assertExecutedPublicationFailure(String errorCode) {
        var client = mock(PythonCapabilityAwareClient.class);
        when(client.isCapabilityAvailable("BROWSER_AUTOMATION")).thenReturn(true);
        var factory = mock(VerifierFactory.class);
        var verifier = mock(BrowserVerifier.class);
        when(factory.selectVerifier(any(), anyString())).thenReturn(verifier);
        var assistedStep = new StepResult(0, "type", true, 1, null, List.of(),
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl2nFsAAAAASUVORK5CYII=",
                "js_fallback", "Native input timed out", null);
        when(verifier.verify(any(), anyString()))
                .thenReturn(new JourneyResult("verified", null, List.of(assistedStep), Map.of()));
        var detector = mock(PreviewStackDetector.class);
        when(detector.detect(any())).thenReturn(new StackInfo("vite", 5173, "unused"));
        var launcher = mock(DevServerLauncher.class);
        var handle = new DevServerHandle(mock(Process.class), 99999L, 5173,
                root.resolve("unused.log"), root.resolve("unused.pid"));
        when(launcher.start(any(), anyString(), anyInt(), any())).thenReturn(handle);
        var evidence = mock(EvidenceStore.class);
        var tool = new VerifyJourneyTool(client, launcher, factory, detector, evidence,
                mock(SimpMessagingTemplate.class), mock(FeatureFlagService.class),
                mock(ActivityRepository.class), new ObjectMapper(), mock(NotificationService.class));
        var policy = mock(MeooPublicationPolicy.class);
        tool.setMeooPublicationPolicy(policy);
        var first = new MeooPublicationPolicy.Snapshot(root, ".", "image", "Demo", "account",
                List.of(), 12, "a".repeat(64), "");
        var after = when(policy.inspect(any(), any(), eq(false))).thenReturn(first);
        if ("MEOO_VERIFICATION_STALE".equals(errorCode)) {
            after.thenReturn(new MeooPublicationPolicy.Snapshot(root, ".", "image", "Demo", "account",
                    List.of(), 13, "b".repeat(64), ""));
        } else {
            after.thenThrow(new MeooException(errorCode));
        }

        var result = tool.call(ToolInput.from(Map.of(
                        "journey", List.of(Map.of("action", "type", "selector", "#name", "text", "Alice")),
                        "verification_mode", "browser", "publication_path", ".", "publication_runtime", "image")),
                ToolUseContext.of(root.toString(), "s").withCurrentRunId("r"));

        assertThat(result.failureCode()).isEqualTo(errorCode);
        assertThat(result.content()).contains("verdict 'verified'", "js_fallback", "Do not automatically rerun");
        assertThat(result.isRetryable()).isFalse();
        verify(evidence, never()).saveBlob(any());
        verify(evidence, never()).save(any());
        verify(verifier, times(1)).verify(any(), anyString());
        verify(launcher).stop(handle);
    }
}
