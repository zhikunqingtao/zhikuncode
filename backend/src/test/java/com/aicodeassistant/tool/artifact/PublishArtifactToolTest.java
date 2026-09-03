package com.aicodeassistant.tool.artifact;

import com.aicodeassistant.artifact.ArtifactEntry;
import com.aicodeassistant.artifact.ArtifactManifest;
import com.aicodeassistant.artifact.ArtifactManifestService;
import com.aicodeassistant.artifact.publication.ArtifactPublicationPolicy;
import com.aicodeassistant.artifact.publication.OssArtifactService;
import com.aicodeassistant.config.oss.OssPublishProperties;
import com.aicodeassistant.security.ManagedPathLockManager;
import com.aicodeassistant.tool.PermissionRequirement;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublishArtifactToolTest {
    @TempDir Path workspace;

    @Test
    void publishesOnlyCurrentSessionVerifiedArtifactThroughAlwaysAskTool() throws Exception {
        Path file = Files.writeString(workspace.resolve("report.html"), "<h1>safe report</h1>");
        String hash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        ArtifactManifestService manifests = mock(ArtifactManifestService.class);
        ArtifactEntry entry = new ArtifactEntry("artifact-1", "manifest-1", "tool-1",
                file.toString(), "created", "integrity_verified", hash, hash, Files.size(file),
                "sha256", "{}", null, Instant.now(), Instant.now());
        ArtifactManifest manifest = new ArtifactManifest("manifest-1", "run-1", "session-1",
                workspace.toRealPath().toString(), "verified", Instant.now(), Instant.now(), List.of(entry));
        when(manifests.getManifestsForRunSession("run-1")).thenReturn(List.of(manifest));

        OssPublishProperties properties = properties();
        ArtifactPublicationPolicy policy = new ArtifactPublicationPolicy(
                manifests, new ManagedPathLockManager(), properties);
        OssArtifactService oss = mock(OssArtifactService.class);
        when(oss.publish(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            ArtifactPublicationPolicy.Snapshot artifact = invocation.getArgument(0);
            return new OssArtifactService.PublishedArtifact(
                    artifact.artifactId(), artifact.fileName(), artifact.size(), artifact.sha256(),
                    artifact.objectKey(), artifact.publicUrl(), artifact.mimeType());
        });
        PublishArtifactTool tool = new PublishArtifactTool(policy, oss, properties, new ObjectMapper());

        ToolResult result = tool.call(ToolInput.from(java.util.Map.of("file_path", "report.html")),
                ToolUseContext.of(workspace.toString(), "session-1").withCurrentRunId("run-1"));

        assertThat(tool.getPermissionRequirement()).isEqualTo(PermissionRequirement.ALWAYS_ASK);
        assertThat(tool.isHighRisk()).isTrue();
        assertThat(tool.isOpenWorld()).isTrue();
        assertThat(result.executionStatus()).isEqualTo(ToolResult.ExecutionStatus.SUCCEEDED);
        assertThat(result.effectState()).isEqualTo(ToolResult.EffectState.APPLIED);
        assertThat(result.content())
                .contains("\"status\":\"published\"")
                .contains("\"downloadCardAvailable\":true")
                .doesNotContain("https://")
                .doesNotContain("objectKey")
                .doesNotContain(hash);

        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) result.metadata().get("structuredResult");
        assertThat(structured)
                .containsEntry("schema", "external-resource/v1")
                .containsEntry("kind", "download")
                .containsEntry("provider", "oss")
                .containsEntry("label", "report.html")
                .containsEntry("size", Files.size(file))
                .containsEntry("sha256", hash)
                .containsEntry("permanentlyPublic", true)
                .containsEntry("downloadExpected", true);
        assertThat(structured.get("url")).asString()
                .startsWith("https://test-artifacts.oss-cn-beijing.aliyuncs.com/");
        assertThat(structured.get("objectKey")).asString()
                .startsWith("zhikuncode-artifacts/manifest-1/artifact-1/");
    }

    @Test
    void publishesExactUnlistedFileInsideCurrentWorkspace() throws Exception {
        Path file = Files.writeString(workspace.resolve("generated.xlsx"), "safe workbook bytes");
        ArtifactManifestService manifests = mock(ArtifactManifestService.class);
        when(manifests.getManifestsForRunSession("run-1")).thenReturn(List.of());
        OssPublishProperties properties = properties();
        ArtifactPublicationPolicy policy = new ArtifactPublicationPolicy(
                manifests, new ManagedPathLockManager(), properties);
        OssArtifactService oss = mock(OssArtifactService.class);
        when(oss.publish(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            ArtifactPublicationPolicy.Snapshot artifact = invocation.getArgument(0);
            return new OssArtifactService.PublishedArtifact(
                    artifact.artifactId(), artifact.fileName(), artifact.size(), artifact.sha256(),
                    artifact.objectKey(), artifact.publicUrl(), artifact.mimeType());
        });
        PublishArtifactTool tool = new PublishArtifactTool(policy, oss, properties, new ObjectMapper());

        ToolResult result = tool.call(ToolInput.from(Map.of("file_path", file.toString())),
                ToolUseContext.of(workspace.toString(), "session-1").withCurrentRunId("run-1"));

        assertThat(result.executionStatus()).isEqualTo(ToolResult.ExecutionStatus.SUCCEEDED);
        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) result.metadata().get("structuredResult");
        assertThat(structured).containsEntry("label", "generated.xlsx");
        assertThat(structured.get("objectKey")).asString().contains("/workspace/workspace-");
    }

    @Test
    void missingCurrentRunFailsBeforeAnyNetworkCall() {
        OssPublishProperties properties = properties();
        ArtifactManifestService manifests = mock(ArtifactManifestService.class);
        OssArtifactService oss = mock(OssArtifactService.class);
        PublishArtifactTool tool = new PublishArtifactTool(
                new ArtifactPublicationPolicy(manifests, new ManagedPathLockManager(), properties),
                oss, properties, new ObjectMapper());

        ToolResult result = tool.call(ToolInput.from(java.util.Map.of("file_path", "report.html")),
                ToolUseContext.of(workspace.toString(), "session-1"));

        assertThat(result.executionStatus()).isEqualTo(ToolResult.ExecutionStatus.FAILED);
        assertThat(result.failureCode()).isEqualTo("ARTIFACT_RUN_REQUIRED");
        org.mockito.Mockito.verifyNoInteractions(oss);
    }

    private static OssPublishProperties properties() {
        OssPublishProperties properties = new OssPublishProperties();
        properties.setEnabled(true);
        properties.setEndpoint("https://oss-cn-beijing.aliyuncs.com");
        properties.setRegion("cn-beijing");
        properties.setBucket("test-artifacts");
        properties.setPrefix("zhikuncode-artifacts");
        properties.setEcsRoleName("TestEcsRole");
        return properties;
    }
}
