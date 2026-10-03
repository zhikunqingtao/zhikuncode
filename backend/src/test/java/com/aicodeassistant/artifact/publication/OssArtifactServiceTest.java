package com.aicodeassistant.artifact.publication;

import com.aicodeassistant.config.oss.OssPublishProperties;
import com.aicodeassistant.tool.ToolResult;
import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.exceptions.ServiceException;
import com.aliyun.sdk.service.oss2.models.DeleteObjectRequest;
import com.aliyun.sdk.service.oss2.models.HeadObjectRequest;
import com.aliyun.sdk.service.oss2.models.HeadObjectResult;
import com.aliyun.sdk.service.oss2.models.PutObjectAclRequest;
import com.aliyun.sdk.service.oss2.models.PutObjectRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OssArtifactServiceTest {
    @TempDir Path workspace;

    @Test
    void uploadsPrivatelyVerifiesAndOnlyThenMakesObjectPublic() throws Exception {
        ArtifactPublicationPolicy.Snapshot artifact = snapshot("report.html", "<h1>safe</h1>");
        OSSClient client = mock(OSSClient.class);
        ServiceException notFound = serviceException(404, "NoSuchKey", null);
        HeadObjectResult verified = verifiedRemote(artifact);
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(notFound)
                .thenReturn(verified);

        OssArtifactService.PublishedArtifact published = service(client).publish(artifact);

        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(put.capture());
        assertThat(put.getValue().objectAcl()).isEqualTo("private");
        assertThat(put.getValue().forbidOverwrite()).isTrue();
        assertThat(put.getValue().metadata()).containsEntry("sha256", artifact.sha256());

        ArgumentCaptor<PutObjectAclRequest> acl = ArgumentCaptor.forClass(PutObjectAclRequest.class);
        verify(client).putObjectAcl(acl.capture());
        assertThat(acl.getValue().objectAcl()).isEqualTo("public-read");
        verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
        assertThat(published.publicUrl()).isEqualTo(artifact.publicUrl());
        assertThat(published.downloadExpected()).isTrue();
    }

    @Test
    void publicAclFailureRemovesPrivateUploadAndReportsNoRemainingEffect() throws Exception {
        ArtifactPublicationPolicy.Snapshot artifact = snapshot("report.pdf", "safe-pdf-content");
        OSSClient client = mock(OSSClient.class);
        ServiceException notFound = serviceException(404, "NoSuchKey", null);
        HeadObjectResult verified = verifiedRemote(artifact);
        ServiceException publicAclBlocked = serviceException(
                400, "InvalidArgument", "0016-00000901");
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(notFound)
                .thenReturn(verified);
        when(client.putObjectAcl(any(PutObjectAclRequest.class)))
                .thenThrow(publicAclBlocked);

        assertThatThrownBy(() -> service(client).publish(artifact))
                .isInstanceOfSatisfying(OssArtifactService.OssPublishException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("OSS_PUBLIC_ACCESS_BLOCKED");
                    assertThat(failure.effectState()).isEqualTo(ToolResult.EffectState.NONE);
                    assertThat(failure.retryability()).isEqualTo(ToolResult.Retryability.NEVER);
                });
        verify(client).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void contentDispositionCarriesUtf8NameAndAsciiFallback() throws Exception {
        ArtifactPublicationPolicy.Snapshot artifact = snapshot("报告 100%.html", "<h1>x</h1>");
        OSSClient client = mock(OSSClient.class);
        ServiceException notFound = serviceException(404, "NoSuchKey", null);
        HeadObjectResult verified = verifiedRemote(artifact);
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(notFound)
                .thenReturn(verified);

        service(client).publish(artifact);

        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(put.capture());
        assertThat(put.getValue().contentDisposition()).isEqualTo(
                "attachment; filename=\"?? 100%.html\";"
                        + " filename*=UTF-8''%E6%8A%A5%E5%91%8A%20100%25.html");
    }

    @Test
    void matchingDeterministicObjectIsReusedWithoutUploadingAgain() throws Exception {
        ArtifactPublicationPolicy.Snapshot artifact = snapshot("report.txt", "safe report");
        OSSClient client = mock(OSSClient.class);
        HeadObjectResult verified = verifiedRemote(artifact);
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(verified);

        service(client).publish(artifact);

        verify(client, never()).putObject(any(PutObjectRequest.class));
        verify(client).putObjectAcl(any(PutObjectAclRequest.class));
    }

    @Test
    void failedPutNeverDeletesAnObjectThatWasNotAcknowledgedAsCreated() throws Exception {
        ArtifactPublicationPolicy.Snapshot artifact = snapshot("report.txt", "safe report");
        OSSClient client = mock(OSSClient.class);
        ServiceException notFound = serviceException(404, "NoSuchKey", null);
        ServiceException conflict = serviceException(409, "FileAlreadyExists", null);
        when(client.headObject(any(HeadObjectRequest.class))).thenThrow(notFound);
        when(client.putObject(any(PutObjectRequest.class))).thenThrow(conflict);

        assertThatThrownBy(() -> service(client).publish(artifact))
                .isInstanceOfSatisfying(OssArtifactService.OssPublishException.class,
                        failure -> assertThat(failure.effectState())
                                .isEqualTo(ToolResult.EffectState.UNKNOWN));
        verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/html", " TEXT/HTML ; charset=UTF-8", "text/plain", "application/pdf",
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/svg+xml", "application/zip"})
    void onlyPreviewAllowlistUsesInlineForNewObjects(String mime) throws Exception {
        OssPublishProperties properties = previewProperties();
        var artifact = snapshot("报告 100%.html", "safe file", mime, properties);
        OSSClient client = mock(OSSClient.class);
        var notFound = serviceException(404, "NoSuchKey", null);
        var verified = verifiedRemote(artifact);
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(notFound).thenReturn(verified);
        HttpClient http = headClient(200, Map.of("Content-Type", List.of(mime)));

        var published = new OssArtifactService(properties, () -> client, http).publish(artifact);

        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(put.capture());
        boolean preview = !List.of("image/svg+xml", "application/zip").contains(mime);
        assertThat(put.getValue().contentDisposition()).startsWith(preview ? "inline;" : "attachment;")
                .contains("filename*=UTF-8''%E6%8A%A5%E5%91%8A%20100%25.html");
        assertThat(put.getValue().contentType()).isEqualTo(mime);
        assertThat(published.downloadExpected()).isEqualTo(!preview);
        assertThat(published.publicUrl()).startsWith("https://files.example.com/");
        verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any());
        assertThat(request.getValue().method()).isEqualTo("HEAD");
        assertThat(request.getValue().uri().toASCIIString()).isEqualTo(published.publicUrl());
        assertThat(request.getValue().timeout()).contains(Duration.ofSeconds(3));
        assertThat(request.getValue().headers().map()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"attachment; filename=old.html", "inline; filename=new.html", "INLINE", "missing"})
    void reusedObjectsFollowPublicHeadersNotCurrentPreviewFlagOrSdkHeaders(String disposition) throws Exception {
        OssPublishProperties properties = previewProperties();
        properties.setPreviewEnabled(false);
        var artifact = snapshot("old.html", "safe file", "text/html", properties);
        OSSClient client = mock(OSSClient.class);
        HeadObjectResult remote = verifiedRemote(artifact);
        when(remote.contentDisposition()).thenReturn("attachment");
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(remote);
        Map<String, List<String>> headers = new java.util.HashMap<>();
        headers.put("Content-Type", List.of("Text/HTML; charset=UTF-8"));
        if (!"missing".equals(disposition)) headers.put("Content-Disposition", List.of(disposition));
        HttpClient http = headClient(200, headers);

        var published = new OssArtifactService(properties, () -> client, http).publish(artifact);

        assertThat(published.downloadExpected()).isEqualTo(disposition.startsWith("attachment"));
        assertThat(published.mimeType()).isEqualTo("Text/HTML; charset=UTF-8");
        assertThat(published.publicUrl()).isEqualTo(properties.publicUrl(artifact.objectKey()));
        verify(client, never()).putObject(any(PutObjectRequest.class));
        verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void disabledPreviewWithCustomDomainStillUploadsAttachment() throws Exception {
        OssPublishProperties properties = previewProperties();
        properties.setPreviewEnabled(false);
        var artifact = snapshot("new.html", "safe file", "text/html", properties);
        OSSClient client = mock(OSSClient.class);
        var notFound = serviceException(404, "NoSuchKey", null);
        var verified = verifiedRemote(artifact);
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(notFound).thenReturn(verified);
        new OssArtifactService(properties, () -> client, headClient(200, Map.of(
                "Content-Type", List.of("text/html"), "Content-Disposition", List.of("attachment")))).publish(artifact);
        ArgumentCaptor<PutObjectRequest> put = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(put.capture());
        assertThat(put.getValue().contentDisposition()).startsWith("attachment;");
    }

    @ParameterizedTest
    @ValueSource(strings = {"redirect", "missing-mime", "unsupported-mime", "force-download",
            "unknown-disposition", "malformed-disposition", "duplicate-disposition", "duplicate-mime", "timeout", "error", "interrupted"})
    void publicProbeProblemsNeverUndoSuccessfulPublication(String scenario) throws Exception {
        OssPublishProperties properties = previewProperties();
        var artifact = snapshot("report.html", "safe file", "text/html", properties);
        OSSClient client = mock(OSSClient.class);
        var notFound = serviceException(404, "NoSuchKey", null);
        var verified = verifiedRemote(artifact);
        when(client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(notFound).thenReturn(verified);
        Map<String, List<String>> headers = new java.util.HashMap<>();
        headers.put("Content-Type", List.of("text/html"));
        switch (scenario) {
            case "missing-mime" -> headers.clear();
            case "unsupported-mime" -> headers.put("Content-Type", List.of("application/octet-stream"));
            case "force-download" -> headers.put("x-oss-force-download", List.of("true"));
            case "unknown-disposition" -> headers.put("Content-Disposition", List.of("something-else"));
            case "malformed-disposition" -> headers.put("Content-Disposition", List.of("inline; invalid"));
            case "duplicate-disposition" -> headers.put("Content-Disposition", List.of("inline", "attachment"));
            case "duplicate-mime" -> headers.put("Content-Type", List.of("text/html", "application/octet-stream"));
            default -> { }
        }
        HttpClient http = headClient("redirect".equals(scenario) ? 302 : 200, headers);
        if (List.of("timeout", "error", "interrupted").contains(scenario)) {
            Exception error = switch (scenario) {
                case "timeout" -> new HttpTimeoutException("timed out");
                case "interrupted" -> new InterruptedException();
                default -> new java.io.IOException("unavailable");
            };
            when(http.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any()))
                    .thenThrow(error);
        }
        try {
            var published = new OssArtifactService(properties, () -> client, http).publish(artifact);
            assertThat(published.downloadExpected()).isTrue();
            if ("unsupported-mime".equals(scenario)) assertThat(published.mimeType()).isEqualTo("application/octet-stream");
            verify(client).putObjectAcl(any(PutObjectAclRequest.class));
            verify(client, never()).deleteObject(any(DeleteObjectRequest.class));
            verify(http).send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any());
            assertThat(Thread.currentThread().isInterrupted()).isEqualTo("interrupted".equals(scenario));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void defaultDomainDoesNotPerformAnyPublicProbe() throws Exception {
        var artifact = snapshot("report.html", "safe file");
        OSSClient client = mock(OSSClient.class);
        var verified = verifiedRemote(artifact);
        when(client.headObject(any(HeadObjectRequest.class))).thenReturn(verified);
        HttpClient http = mock(HttpClient.class);
        assertThat(new OssArtifactService(properties(), () -> client, http).publish(artifact).downloadExpected()).isTrue();
        verifyNoInteractions(http);
    }

    @SuppressWarnings("unchecked")
    private static HttpClient headClient(int status, Map<String, List<String>> headers) throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<Void> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(HttpHeaders.of(headers, (key, value) -> true));
        when(client.send(any(HttpRequest.class), org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any()))
                .thenReturn(response);
        return client;
    }

    private static OssPublishProperties previewProperties() {
        OssPublishProperties properties = properties();
        properties.setPublicBaseUrl("https://files.example.com");
        properties.setPreviewEnabled(true);
        return properties;
    }

    private OssArtifactService service(OSSClient client) {
        return new OssArtifactService(properties(), () -> client);
    }

    private ArtifactPublicationPolicy.Snapshot snapshot(String fileName, String content) throws Exception {
        return snapshot(fileName, content, "application/octet-stream", properties());
    }

    private ArtifactPublicationPolicy.Snapshot snapshot(String fileName, String content, String mime,
                                                        OssPublishProperties properties) throws Exception {
        Path file = Files.writeString(workspace.resolve(fileName), content);
        String hash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        String objectKey = "zhikuncode-artifacts/manifest-1/artifact-1/" + hash + "-" + fileName;
        return new ArtifactPublicationPolicy.Snapshot("artifact-1", "manifest-1", "run-1",
                fileName, file, fileName, Files.size(file), hash, mime,
                objectKey, properties.publicUrl(objectKey),
                "test-artifacts", "https://oss-cn-beijing.aliyuncs.com");
    }

    private static HeadObjectResult verifiedRemote(ArtifactPublicationPolicy.Snapshot artifact) {
        HeadObjectResult remote = mock(HeadObjectResult.class);
        when(remote.contentLength()).thenReturn(artifact.size());
        when(remote.metadata()).thenReturn(Map.of("sha256", artifact.sha256()));
        return remote;
    }

    private static ServiceException serviceException(int status, String code, String ec) {
        ServiceException service = mock(ServiceException.class);
        when(service.statusCode()).thenReturn(status);
        when(service.errorCode()).thenReturn(code);
        when(service.ec()).thenReturn(ec);
        when(service.requestId()).thenReturn("request-redacted");
        return service;
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
