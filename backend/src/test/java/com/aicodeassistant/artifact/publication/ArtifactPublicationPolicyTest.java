package com.aicodeassistant.artifact.publication;

import com.aicodeassistant.artifact.ArtifactEntry;
import com.aicodeassistant.artifact.ArtifactManifest;
import com.aicodeassistant.artifact.ArtifactManifestService;
import com.aicodeassistant.config.oss.OssPublishProperties;
import com.aicodeassistant.security.ManagedPathLockManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArtifactPublicationPolicyTest {
    @TempDir Path workspace;

    @Test
    void acceptsOneVerifiedArtifactFromCurrentSession() throws Exception {
        Path file = Files.writeString(workspace.resolve("report.html"), "<h1>safe report</h1>");
        Fixture fixture = fixture(file, "integrity_verified");

        ArtifactPublicationPolicy.Snapshot snapshot = fixture.policy.inspect(
                "report.html", "run-1", workspace.toString());

        assertThat(snapshot.relativePath()).isEqualTo("report.html");
        assertThat(snapshot.size()).isEqualTo(Files.size(file));
        assertThat(snapshot.publicUrl()).startsWith("https://test-artifacts.oss-cn-beijing.aliyuncs.com/");
        assertThat(snapshot.objectKey()).contains(snapshot.sha256()).doesNotContain("..");
    }

    @Test
    void laterExplicitRunCanPublishThePreviousRunsVerifiedArtifact() throws Exception {
        Path file = Files.writeString(workspace.resolve("report.html"), "<h1>safe report</h1>");
        ArtifactManifestService manifests = mock(ArtifactManifestService.class);
        ArtifactManifest previous = manifest(file, "run-generate", "verified", "integrity_verified");
        when(manifests.getManifestsForRunSession("run-publish")).thenReturn(List.of(previous));
        ArtifactPublicationPolicy policy = new ArtifactPublicationPolicy(
                manifests, new ManagedPathLockManager(), validProperties());

        ArtifactPublicationPolicy.Snapshot snapshot = policy.inspect(
                "report.html", "run-publish", workspace.toString());

        assertThat(snapshot.runId()).isEqualTo("run-generate");
    }

    @Test
    void newestDeclarationForSamePathCannotFallBackToOlderVerifiedBytes() throws Exception {
        Path file = Files.writeString(workspace.resolve("report.html"), "<h1>safe report</h1>");
        ArtifactManifestService manifests = mock(ArtifactManifestService.class);
        ArtifactManifest newest = manifest(file, "run-new", "unverified", "sealed");
        ArtifactManifest older = manifest(file, "run-old", "verified", "integrity_verified");
        when(manifests.getManifestsForRunSession("run-publish"))
                .thenReturn(List.of(newest, older));
        ArtifactPublicationPolicy policy = new ArtifactPublicationPolicy(
                manifests, new ManagedPathLockManager(), validProperties());

        assertThatThrownBy(() -> policy.inspect(
                "report.html", "run-publish", workspace.toString()))
                .hasMessage("ARTIFACT_MANIFEST_NOT_VERIFIED");
    }

    @Test
    void rejectsFileChangedAfterManifestVerification() throws Exception {
        Path file = Files.writeString(workspace.resolve("report.txt"), "original content");
        Fixture fixture = fixture(file, "integrity_verified");
        Files.writeString(file, "changed content");

        assertThatThrownBy(() -> fixture.policy.inspect(
                "report.txt", "run-1", workspace.toString()))
                .isInstanceOf(ArtifactPublicationPolicy.ArtifactPublicationException.class)
                .hasMessage("ARTIFACT_HASH_CHANGED");
    }

    @Test
    void rejectsSensitiveNamesAndContents() throws Exception {
        Path env = Files.writeString(workspace.resolve(".env.production"), "SAFE=value");
        Fixture envFixture = fixture(env, "integrity_verified");
        assertThatThrownBy(() -> envFixture.policy.inspect(
                ".env.production", "run-1", workspace.toString()))
                .hasMessage("ARTIFACT_SENSITIVE_FILE_FORBIDDEN");

        Path secret = Files.writeString(workspace.resolve("report.txt"),
                "api_key=abcdefghijklmnopqrstuvwxyz123456");
        Fixture secretFixture = fixture(secret, "integrity_verified");
        assertThatThrownBy(() -> secretFixture.policy.inspect(
                "report.txt", "run-1", workspace.toString()))
                .hasMessage("ARTIFACT_SENSITIVE_CONTENT_FORBIDDEN");
    }

    @Test
    void rejectsUnverifiedOrMissingFiles() throws Exception {
        Path file = Files.writeString(workspace.resolve("draft.txt"), "draft");
        Fixture fixture = fixture(file, "declared");
        assertThatThrownBy(() -> fixture.policy.inspect(
                "draft.txt", "run-1", workspace.toString()))
                .hasMessage("ARTIFACT_NOT_VERIFIED");
        assertThatThrownBy(() -> fixture.policy.inspect(
                "missing.txt", "run-1", workspace.toString()))
                .hasMessage("ARTIFACT_NOT_REGULAR_FILE");
    }

    @Test
    void acceptsExactUnlistedRegularFileInsideCurrentWorkspace() throws Exception {
        Path file = Files.writeString(workspace.resolve("generated.xlsx"), "safe workbook bytes");
        ArtifactManifestService manifests = mock(ArtifactManifestService.class);
        when(manifests.getManifestsForRunSession("run-publish")).thenReturn(List.of());
        ArtifactPublicationPolicy policy = new ArtifactPublicationPolicy(
                manifests, new ManagedPathLockManager(), validProperties());

        ArtifactPublicationPolicy.Snapshot snapshot = policy.inspect(
                file.toString(), "run-publish", workspace.toString());

        assertThat(snapshot.relativePath()).isEqualTo("generated.xlsx");
        assertThat(snapshot.manifestId()).isEqualTo("workspace");
        assertThat(snapshot.artifactId()).startsWith("workspace-");
        assertThat(snapshot.runId()).isEqualTo("run-publish");
        assertThat(snapshot.objectKey()).contains("/workspace/").contains(snapshot.sha256());
    }

    @Test
    void unlistedFallbackCannotEscapeCurrentWorkspace() throws Exception {
        Path outside = Files.writeString(workspace.getParent().resolve("outside.txt"), "safe");
        ArtifactManifestService manifests = mock(ArtifactManifestService.class);
        ArtifactPublicationPolicy policy = new ArtifactPublicationPolicy(
                manifests, new ManagedPathLockManager(), validProperties());

        assertThatThrownBy(() -> policy.inspect(
                outside.toString(), "run-publish", workspace.toString()))
                .hasMessage("ARTIFACT_PATH_ESCAPE");
    }

    @Test
    void rejectsTraversalSymlinksAndFilesAboveConfiguredLimit() throws Exception {
        Path safe = Files.writeString(workspace.resolve("safe.txt"), "safe content");
        Fixture safeFixture = fixture(safe, "integrity_verified");
        assertThatThrownBy(() -> safeFixture.policy.inspect(
                "../safe.txt", "run-1", workspace.toString()))
                .hasMessage("ARTIFACT_PATH_ESCAPE");

        Path link = Files.createSymbolicLink(workspace.resolve("link.txt"), safe.getFileName());
        Fixture linkFixture = fixture(link, "integrity_verified");
        assertThatThrownBy(() -> linkFixture.policy.inspect(
                "link.txt", "run-1", workspace.toString()))
                .hasMessage("ARTIFACT_SYMLINK_FORBIDDEN");

        OssPublishProperties limited = validProperties();
        limited.setMaxFileBytes(4);
        Fixture limitedFixture = fixture(safe, "integrity_verified", limited);
        assertThatThrownBy(() -> limitedFixture.policy.inspect(
                "safe.txt", "run-1", workspace.toString()))
                .hasMessage("ARTIFACT_TOO_LARGE");
    }

    private Fixture fixture(Path file, String state) throws Exception {
        return fixture(file, state, validProperties());
    }

    private Fixture fixture(Path file, String state, OssPublishProperties properties) throws Exception {
        ArtifactManifestService manifests = mock(ArtifactManifestService.class);
        ArtifactManifest manifest = manifest(file, "run-1", "verified", state);
        when(manifests.getManifestsForRunSession("run-1")).thenReturn(List.of(manifest));
        ArtifactPublicationPolicy policy = new ArtifactPublicationPolicy(
                manifests, new ManagedPathLockManager(), properties);
        return new Fixture(policy);
    }

    private ArtifactManifest manifest(Path file, String runId, String manifestState,
                                      String entryState) throws Exception {
        String hash = sha256(file);
        String suffix = runId.replaceAll("[^A-Za-z0-9]", "");
        ArtifactEntry entry = new ArtifactEntry("artifact-" + suffix, "manifest-" + suffix, "tool-1",
                file.toAbsolutePath().normalize().toString(), "created", entryState, hash, hash,
                Files.size(file), "sha256", "{}", null, Instant.now(), Instant.now());
        return new ArtifactManifest("manifest-" + suffix, runId, "session-1",
                workspace.toRealPath().toString(), manifestState,
                Instant.now(), Instant.now(), List.of(entry));
    }

    private static String sha256(Path path) throws Exception {
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        return java.util.HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
    }

    private static OssPublishProperties validProperties() {
        OssPublishProperties properties = new OssPublishProperties();
        properties.setEnabled(true);
        properties.setEndpoint("https://oss-cn-beijing.aliyuncs.com");
        properties.setRegion("cn-beijing");
        properties.setBucket("test-artifacts");
        properties.setPrefix("zhikuncode-artifacts");
        properties.setEcsRoleName("TestEcsRole");
        return properties;
    }

    private record Fixture(ArtifactPublicationPolicy policy) { }
}
