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
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    private OssArtifactService service(OSSClient client) {
        return new OssArtifactService(properties(), () -> client);
    }

    private ArtifactPublicationPolicy.Snapshot snapshot(String fileName, String content) throws Exception {
        Path file = Files.writeString(workspace.resolve(fileName), content);
        String hash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        String objectKey = "zhikuncode-artifacts/manifest-1/artifact-1/" + hash + "-" + fileName;
        return new ArtifactPublicationPolicy.Snapshot("artifact-1", "manifest-1", "run-1",
                fileName, file, fileName, Files.size(file), hash, "application/octet-stream",
                objectKey, "https://test-artifacts.oss-cn-beijing.aliyuncs.com/" + objectKey,
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
