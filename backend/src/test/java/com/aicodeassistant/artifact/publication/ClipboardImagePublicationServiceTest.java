package com.aicodeassistant.artifact.publication;

import com.aicodeassistant.config.oss.OssPublishProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

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
                    value.objectKey(), value.publicUrl(), value.mimeType(), true);
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
        assertThat(result.mediaType()).isEqualTo("image/png");
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
        verifyNoInteractions(oss);
    }

    @Test
    void customDomainIsUsedForClipboardImagesWithoutChangingNamespace() {
        OssPublishProperties properties = properties();
        properties.setPublicBaseUrl("https://files.example.com");
        OssArtifactService oss = mock(OssArtifactService.class);
        when(oss.publish(any())).thenAnswer(invocation -> {
            ArtifactPublicationPolicy.Snapshot value = invocation.getArgument(0);
            return new OssArtifactService.PublishedArtifact(value.artifactId(), value.fileName(),
                    value.size(), value.sha256(), value.objectKey(), value.publicUrl(), value.mimeType(), false);
        });
        byte[] png = { (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a };
        var result = new ClipboardImagePublicationService(properties, oss).publish("session",
                new MockMultipartFile("file", "image.png", "image/png", png));

        assertThat(result.url()).startsWith("https://files.example.com/zhikuncode-artifacts/clipboard/");
        assertThat(properties.isTrustedClipboardImageUrl(result.url())).isTrue();
    }

    @ParameterizedTest
    @MethodSource("publicImageMediaTypes")
    void publicHeadersCannotMisclassifyClipboardImagesAndPreserveCdnImageFormats(
            String publicMimeType, String expectedMediaType) {
        OssPublishProperties properties = properties();
        properties.setPublicBaseUrl("https://files.example.com");
        OssArtifactService oss = mock(OssArtifactService.class);
        ArgumentCaptor<ArtifactPublicationPolicy.Snapshot> snapshot =
                ArgumentCaptor.forClass(ArtifactPublicationPolicy.Snapshot.class);
        when(oss.publish(snapshot.capture())).thenAnswer(invocation -> {
            ArtifactPublicationPolicy.Snapshot value = invocation.getArgument(0);
            return new OssArtifactService.PublishedArtifact(value.artifactId(), value.fileName(),
                    value.size(), value.sha256(), value.objectKey(), value.publicUrl(), publicMimeType, false);
        });
        byte[] png = { (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a };

        var result = new ClipboardImagePublicationService(properties, oss).publish("session",
                new MockMultipartFile("file", "image.png", "image/png", png));

        verify(oss).publish(snapshot.getValue());
        assertThat(snapshot.getValue().mimeType()).isEqualTo("image/png");
        assertThat(result.mediaType()).isEqualTo(expectedMediaType);
        assertThat(result.artifactId()).isEqualTo(snapshot.getValue().artifactId());
        assertThat(result.fileName()).isEqualTo(snapshot.getValue().fileName());
        assertThat(result.size()).isEqualTo(snapshot.getValue().size());
        assertThat(result.sha256()).isEqualTo(snapshot.getValue().sha256());
        assertThat(result.url()).isEqualTo(snapshot.getValue().publicUrl());
    }

    private static Stream<Arguments> publicImageMediaTypes() {
        return Stream.of(
                Arguments.of("image/png", "image/png"),
                Arguments.of("Image/PNG", "image/png"),
                Arguments.of(" IMAGE/PNG ; charset=UTF-8 ", "image/png"),
                Arguments.of("image/webp", "image/webp"),
                Arguments.of("IMAGE/JPEG; custom=value", "image/jpeg"),
                Arguments.of(null, "image/png"),
                Arguments.of("", "image/png"),
                Arguments.of("  ", "image/png"),
                Arguments.of("application/octet-stream", "image/png"),
                Arguments.of("text/html", "image/png"),
                Arguments.of("image/png/extra", "image/png"),
                Arguments.of("image/png; charset=not-a-real-charset", "image/png"),
                Arguments.of("*/*", "image/png"),
                Arguments.of("image/*", "image/png"),
                Arguments.of("image/*+xml", "image/png"));
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
