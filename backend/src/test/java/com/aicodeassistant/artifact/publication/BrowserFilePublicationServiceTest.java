package com.aicodeassistant.artifact.publication;

import com.aicodeassistant.config.oss.OssPublishProperties;
import com.aicodeassistant.tool.ToolResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BrowserFilePublicationServiceTest {

    @Test
    void streamsFileToDeterministicSessionScopedObjectAndCleansTemporaryFile() {
        OssPublishProperties properties = properties(100);
        OssArtifactService oss = mock(OssArtifactService.class);
        ArgumentCaptor<ArtifactPublicationPolicy.Snapshot> snapshots =
                ArgumentCaptor.forClass(ArtifactPublicationPolicy.Snapshot.class);
        when(oss.publish(snapshots.capture())).thenAnswer(invocation -> {
            ArtifactPublicationPolicy.Snapshot value = invocation.getArgument(0);
            return published(value);
        });
        BrowserFilePublicationService service =
                new BrowserFilePublicationService(properties, oss);

        byte[] contents = "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var first = service.publish("session-a", "../note.txt", "text/plain",
                contents.length, new ByteArrayInputStream(contents));
        var second = service.publish("session-a", "note.txt", "text/plain",
                -1, new ByteArrayInputStream(contents));
        var otherSession = service.publish("session-b", "note.txt", "text/plain",
                contents.length, new ByteArrayInputStream(contents));

        verify(oss, times(3)).publish(any());
        assertThat(snapshots.getAllValues().get(0).objectKey())
                .isEqualTo(snapshots.getAllValues().get(1).objectKey())
                .startsWith("zhikuncode-artifacts/local-files/")
                .endsWith("-note.txt");
        assertThat(snapshots.getAllValues().get(2).objectKey())
                .isNotEqualTo(snapshots.getAllValues().get(0).objectKey());
        assertThat(first.url()).isEqualTo(second.url());
        assertThat(otherSession.url()).isNotEqualTo(first.url());
        assertThat(first.fileName()).isEqualTo("note.txt");
        assertThat(first.size()).isEqualTo(contents.length);
        assertThat(first.mediaType()).isEqualTo("text/plain");
        assertThat(snapshots.getAllValues())
                .allSatisfy(snapshot -> assertThat(Files.exists(snapshot.path())).isFalse());
    }

    @Test
    void enforcesDeclaredAndStreamedSizeWithoutCallingOss() {
        OssArtifactService oss = mock(OssArtifactService.class);
        BrowserFilePublicationService service =
                new BrowserFilePublicationService(properties(3), oss);

        assertThatThrownBy(() -> service.publish("session", "large.bin",
                null, 4, new ByteArrayInputStream(new byte[0])))
                .isInstanceOfSatisfying(
                        BrowserFilePublicationService.BrowserFileException.class,
                        failure -> {
                            assertThat(failure.status().value()).isEqualTo(413);
                            assertThat(failure.code()).isEqualTo("LOCAL_FILE_TOO_LARGE");
                        });
        assertThatThrownBy(() -> service.publish("session", "large.bin",
                null, -1, new ByteArrayInputStream(new byte[4])))
                .isInstanceOfSatisfying(
                        BrowserFilePublicationService.BrowserFileException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("LOCAL_FILE_TOO_LARGE"));
        verify(oss, never()).publish(any());
    }

    @Test
    void rejectsEmptyOrIncompleteBodiesAndFallsBackToBinaryMediaType() {
        OssArtifactService oss = mock(OssArtifactService.class);
        BrowserFilePublicationService service =
                new BrowserFilePublicationService(properties(10), oss);

        assertThatThrownBy(() -> service.publish("session", "empty.txt",
                "text/plain", -1, new ByteArrayInputStream(new byte[0])))
                .isInstanceOfSatisfying(
                        BrowserFilePublicationService.BrowserFileException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("LOCAL_FILE_REQUIRED"));
        assertThatThrownBy(() -> service.publish("session", "short.bin",
                null, 2, new ByteArrayInputStream(new byte[1])))
                .isInstanceOfSatisfying(
                        BrowserFilePublicationService.BrowserFileException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("LOCAL_FILE_LENGTH_MISMATCH"));
        verify(oss, never()).publish(any());
    }

    @Test
    void keepsSessionNamespacesSeparateAndCleansTempFileAfterOssFailure() {
        OssPublishProperties properties = properties(10);
        OssArtifactService oss = mock(OssArtifactService.class);
        ArgumentCaptor<ArtifactPublicationPolicy.Snapshot> snapshot =
                ArgumentCaptor.forClass(ArtifactPublicationPolicy.Snapshot.class);
        when(oss.publish(snapshot.capture())).thenThrow(new OssArtifactService.OssPublishException(
                "OSS_TEMPORARY_FAILURE", ToolResult.ToolFailureType.NETWORK,
                ToolResult.Retryability.IDEMPOTENCY_REQUIRED,
                ToolResult.EffectState.NONE, null));
        BrowserFilePublicationService service =
                new BrowserFilePublicationService(properties, oss);

        assertThatThrownBy(() -> service.publish("session-b", "file.bin",
                "not a media type", 1, new ByteArrayInputStream(new byte[] { 1 })))
                .isInstanceOf(OssArtifactService.OssPublishException.class);
        assertThat(snapshot.getValue().mimeType())
                .isEqualTo("application/octet-stream");
        assertThat(Files.exists(snapshot.getValue().path())).isFalse();
    }

    @Test
    void mapsInterruptedRequestBodiesToBadRequestWithoutPublishing() {
        OssArtifactService oss = mock(OssArtifactService.class);
        BrowserFilePublicationService service =
                new BrowserFilePublicationService(properties(10), oss);
        InputStream interrupted = new InputStream() {
            private boolean first = true;

            @Override
            public int read() throws IOException {
                if (first) {
                    first = false;
                    return 1;
                }
                throw new IOException("connection closed");
            }
        };

        assertThatThrownBy(() -> service.publish("session", "partial.bin",
                null, 2, interrupted))
                .isInstanceOfSatisfying(
                        BrowserFilePublicationService.BrowserFileException.class,
                        failure -> {
                            assertThat(failure.status()).isEqualTo(
                                    org.springframework.http.HttpStatus.BAD_REQUEST);
                            assertThat(failure.code()).isEqualTo(
                                    "LOCAL_FILE_READ_FAILED");
                        });
        verify(oss, never()).publish(any());
    }

    private static OssArtifactService.PublishedArtifact published(
            ArtifactPublicationPolicy.Snapshot snapshot) {
        return new OssArtifactService.PublishedArtifact(
                snapshot.artifactId(), snapshot.fileName(), snapshot.size(),
                snapshot.sha256(), snapshot.objectKey(), snapshot.publicUrl(),
                snapshot.mimeType());
    }

    private static OssPublishProperties properties(long maxBytes) {
        OssPublishProperties properties = new OssPublishProperties();
        properties.setEnabled(true);
        properties.setEndpoint("https://oss-cn-beijing.aliyuncs.com");
        properties.setRegion("cn-beijing");
        properties.setBucket("test-artifacts");
        properties.setPrefix("zhikuncode-artifacts");
        properties.setCredentialMode("default-chain");
        properties.setMaxFileBytes(maxBytes);
        return properties;
    }
}
