package com.aicodeassistant.artifact.publication;

import com.aicodeassistant.config.oss.OssPublishProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClipboardImagePublicationServiceTest {

    @Test
    void validatesImageBytesAndPublishesBelowDedicatedClipboardPrefix() {
        OssPublishProperties properties = properties();
        OssArtifactService oss = mock(OssArtifactService.class);
        ArgumentCaptor<ArtifactPublicationPolicy.Snapshot> snapshot =
                ArgumentCaptor.forClass(ArtifactPublicationPolicy.Snapshot.class);
        when(oss.publish(snapshot.capture())).thenAnswer(invocation -> {
            ArtifactPublicationPolicy.Snapshot value = invocation.getArgument(0);
            return new OssArtifactService.PublishedArtifact(
                    value.artifactId(), value.fileName(), value.size(), value.sha256(),
                    value.objectKey(), value.publicUrl(), value.mimeType());
        });
        byte[] png = new byte[] {
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
                0, 0, 0, 0
        };

        ClipboardImagePublicationService.PublishedClipboardImage result =
                new ClipboardImagePublicationService(properties, oss).publish(
                        "session-123",
                        new MockMultipartFile("file", "screenshot.png", "image/png", png));

        verify(oss).publish(snapshot.getValue());
        assertThat(snapshot.getValue().objectKey())
                .startsWith("zhikuncode-artifacts/clipboard/");
        assertThat(snapshot.getValue().mimeType()).isEqualTo("image/png");
        assertThat(result.url()).startsWith(
                "https://test-artifacts.oss-cn-beijing.aliyuncs.com/zhikuncode-artifacts/clipboard/");
        assertThat(result.size()).isEqualTo(png.length);
    }

    @Test
    void rejectsDeclaredTypeThatDoesNotMatchMagicBytesWithoutCallingOss() {
        OssArtifactService oss = mock(OssArtifactService.class);
        byte[] png = new byte[] {
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
        };

        assertThatThrownBy(() -> new ClipboardImagePublicationService(properties(), oss).publish(
                "session-123", new MockMultipartFile(
                        "file", "spoofed.jpg", "image/jpeg", png)))
                .isInstanceOfSatisfying(
                        ClipboardImagePublicationService.ClipboardImageException.class,
                        failure -> assertThat(failure.code())
                                .isEqualTo("CLIPBOARD_IMAGE_TYPE_MISMATCH"));
    }

    private static OssPublishProperties properties() {
        OssPublishProperties properties = new OssPublishProperties();
        properties.setEnabled(true);
        properties.setEndpoint("https://oss-cn-beijing.aliyuncs.com");
        properties.setRegion("cn-beijing");
        properties.setBucket("test-artifacts");
        properties.setPrefix("zhikuncode-artifacts");
        properties.setCredentialMode("default-chain");
        return properties;
    }
}
