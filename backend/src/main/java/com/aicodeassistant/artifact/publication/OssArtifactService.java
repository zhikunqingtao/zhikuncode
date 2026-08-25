package com.aicodeassistant.artifact.publication;

import com.aicodeassistant.config.oss.OssPublishProperties;
import com.aicodeassistant.tool.ToolResult;
import com.aliyun.credentials.Client;
import com.aliyun.credentials.models.Config;
import com.aliyun.credentials.provider.DefaultCredentialsProvider;
import com.aliyun.sdk.service.oss2.OSSClient;
import com.aliyun.sdk.service.oss2.credentials.Credentials;
import com.aliyun.sdk.service.oss2.credentials.CredentialsProvider;
import com.aliyun.sdk.service.oss2.credentials.CredentialsProviderSupplier;
import com.aliyun.sdk.service.oss2.exceptions.ServiceException;
import com.aliyun.sdk.service.oss2.models.DeleteObjectRequest;
import com.aliyun.sdk.service.oss2.models.HeadObjectRequest;
import com.aliyun.sdk.service.oss2.models.HeadObjectResult;
import com.aliyun.sdk.service.oss2.models.PutObjectAclRequest;
import com.aliyun.sdk.service.oss2.models.PutObjectRequest;
import com.aliyun.sdk.service.oss2.transport.BinaryData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/** The only component allowed to acquire Alibaba Cloud credentials or call the OSS SDK. */
@Service
public class OssArtifactService {
    private static final Logger log = LoggerFactory.getLogger(OssArtifactService.class);

    private final OssPublishProperties properties;
    private final Supplier<OSSClient> clientFactory;

    @Autowired
    public OssArtifactService(OssPublishProperties properties) {
        this.properties = properties;
        this.clientFactory = this::createClient;
    }

    OssArtifactService(OssPublishProperties properties, Supplier<OSSClient> clientFactory) {
        this.properties = properties;
        this.clientFactory = clientFactory;
    }

    public PublishedArtifact publish(ArtifactPublicationPolicy.Snapshot artifact) {
        properties.requireReady();
        boolean privateUploadAcknowledged = false;
        boolean published = false;
        Phase phase = Phase.LOOKUP;
        try (OSSClient client = clientFactory.get()) {
            HeadObjectResult existing = headIfPresent(client, artifact);
            if (existing == null) {
                phase = Phase.UPLOAD_PRIVATE;
                try (InputStream input = Files.newInputStream(artifact.path())) {
                    client.putObject(PutObjectRequest.newBuilder()
                            .bucket(artifact.bucket())
                            .key(artifact.objectKey())
                            .forbidOverwrite(true)
                            .objectAcl("private")
                            .contentType(artifact.mimeType())
                            .contentDisposition(contentDisposition(artifact.fileName()))
                            .cacheControl("public, max-age=31536000, immutable")
                            .metadata(Map.of("sha256", artifact.sha256(),
                                    "artifact-id", artifact.artifactId()))
                            .body(BinaryData.fromStream(input, artifact.size()))
                            .build());
                }
                privateUploadAcknowledged = true;
                existing = client.headObject(headRequest(artifact));
            }

            phase = Phase.VERIFY_PRIVATE;
            verifyRemote(existing, artifact);
            String afterUploadHash = sha256(artifact);
            if (!artifact.sha256().equals(afterUploadHash)) {
                throw new OssPublishException("ARTIFACT_CHANGED_DURING_UPLOAD",
                        ToolResult.ToolFailureType.VALIDATION, ToolResult.Retryability.NEVER,
                        ToolResult.EffectState.UNKNOWN, null);
            }

            phase = Phase.MAKE_PUBLIC;
            client.putObjectAcl(PutObjectAclRequest.newBuilder()
                    .bucket(artifact.bucket())
                    .key(artifact.objectKey())
                    .objectAcl("public-read")
                    .build());
            published = true;
            log.info("OSS artifact published: artifactId={}, objectKey={}, size={}",
                    artifact.artifactId(), artifact.objectKey(), artifact.size());
            return new PublishedArtifact(artifact.artifactId(), artifact.fileName(), artifact.size(),
                    artifact.sha256(), artifact.objectKey(), artifact.publicUrl(), artifact.mimeType());
        } catch (OssPublishException known) {
            if (privateUploadAcknowledged && !published) known = compensateDelete(artifact, known);
            throw known;
        } catch (Exception failure) {
            OssPublishException mapped = mapFailure(failure, phase);
            if (privateUploadAcknowledged && !published) mapped = compensateDelete(artifact, mapped);
            throw mapped;
        }
    }

    private OSSClient createClient() {
        try {
            OssPublishProperties.CredentialMode mode = properties.resolvedCredentialMode();
            String credentialFailureCode = mode == OssPublishProperties.CredentialMode.ECS_RAM_ROLE
                    ? "OSS_INSTANCE_ROLE_UNAVAILABLE" : "OSS_CREDENTIALS_UNAVAILABLE";
            Client credentialClient = switch (mode) {
                case ECS_RAM_ROLE -> new Client(new Config().setType("ecs_ram_role")
                        .setRoleName(properties.ecsRoleName())
                        .setDisableIMDSv1(true)
                        .setEnableIMDSv2(true));
                case DEFAULT_CHAIN -> new Client(DefaultCredentialsProvider.builder().build());
            };
            CredentialsProvider provider = new CredentialsProviderSupplier(() -> {
                try {
                    var credential = credentialClient.getCredential();
                    return new Credentials(credential.getAccessKeyId(), credential.getAccessKeySecret(),
                            credential.getSecurityToken());
                } catch (Exception failure) {
                    throw new IllegalStateException(credentialFailureCode, failure);
                }
            });
            return OSSClient.newBuilder()
                    .credentialsProvider(provider)
                    .region(properties.region())
                    .endpoint(properties.endpoint())
                    .connectTimeout(properties.connectTimeout())
                    .readWriteTimeout(properties.requestTimeout())
                    .retryMaxAttempts(3)
                    .build();
        } catch (Exception failure) {
            String code = properties.resolvedCredentialMode()
                    == OssPublishProperties.CredentialMode.ECS_RAM_ROLE
                    ? "OSS_INSTANCE_ROLE_UNAVAILABLE" : "OSS_CREDENTIALS_UNAVAILABLE";
            throw new OssPublishException(code,
                    ToolResult.ToolFailureType.PROVIDER, ToolResult.Retryability.IDEMPOTENCY_REQUIRED,
                    ToolResult.EffectState.NOT_STARTED, failure);
        }
    }

    private HeadObjectResult headIfPresent(OSSClient client,
                                           ArtifactPublicationPolicy.Snapshot artifact) {
        try {
            return client.headObject(headRequest(artifact));
        } catch (RuntimeException failure) {
            ServiceException service = ServiceException.asCause(failure);
            if (service != null && service.statusCode() == 404) return null;
            throw failure;
        }
    }

    private static HeadObjectRequest headRequest(ArtifactPublicationPolicy.Snapshot artifact) {
        return HeadObjectRequest.newBuilder().bucket(artifact.bucket())
                .key(artifact.objectKey()).build();
    }

    private static void verifyRemote(HeadObjectResult remote,
                                     ArtifactPublicationPolicy.Snapshot artifact) {
        if (remote == null || remote.contentLength() == null || remote.contentLength() != artifact.size()) {
            throw new OssPublishException("OSS_REMOTE_SIZE_MISMATCH",
                    ToolResult.ToolFailureType.PROVIDER, ToolResult.Retryability.NEVER,
                    ToolResult.EffectState.UNKNOWN, null);
        }
        Object remoteHash = remote.metadata() == null ? null : remote.metadata().get("sha256");
        if (remoteHash == null || !artifact.sha256().equalsIgnoreCase(String.valueOf(remoteHash))) {
            throw new OssPublishException("OSS_REMOTE_HASH_MISMATCH",
                    ToolResult.ToolFailureType.PROVIDER, ToolResult.Retryability.NEVER,
                    ToolResult.EffectState.UNKNOWN, null);
        }
    }

    private static String sha256(ArtifactPublicationPolicy.Snapshot artifact) {
        try (InputStream input = Files.newInputStream(artifact.path())) {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = input.read(buffer)) != -1; ) digest.update(buffer, 0, read);
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (Exception failure) {
            throw new OssPublishException("ARTIFACT_POST_UPLOAD_VERIFY_FAILED",
                    ToolResult.ToolFailureType.VALIDATION, ToolResult.Retryability.NEVER,
                    ToolResult.EffectState.UNKNOWN, failure);
        }
    }

    private OssPublishException compensateDelete(ArtifactPublicationPolicy.Snapshot artifact,
                                                  OssPublishException original) {
        try (OSSClient cleanup = clientFactory.get()) {
            cleanup.deleteObject(DeleteObjectRequest.newBuilder()
                    .bucket(artifact.bucket()).key(artifact.objectKey()).build());
            return original.withEffect(ToolResult.EffectState.NONE);
        } catch (Exception cleanupFailure) {
            log.warn("OSS artifact cleanup failed: artifactId={}, objectKey={}",
                    artifact.artifactId(), artifact.objectKey());
            return original.withEffect(ToolResult.EffectState.PARTIAL);
        }
    }

    private OssPublishException mapFailure(Exception failure, Phase phase) {
        if (failure instanceof OssPublishException known) return known;
        String credentialFailureCode = credentialFailureCode(failure);
        if (credentialFailureCode != null) {
            return new OssPublishException(credentialFailureCode,
                    ToolResult.ToolFailureType.PROVIDER,
                    ToolResult.Retryability.IDEMPOTENCY_REQUIRED,
                    ToolResult.EffectState.NOT_STARTED, failure);
        }
        ServiceException service = ServiceException.asCause(failure);
        if (service != null) {
            log.warn("OSS request failed: phase={}, status={}, code={}, requestId={}",
                    phase, service.statusCode(), service.errorCode(), service.requestId());
            if (phase == Phase.MAKE_PUBLIC && (service.statusCode() == 403
                    || "AccessDenied".equalsIgnoreCase(service.errorCode())
                    || String.valueOf(service.errorCode()).toLowerCase(Locale.ROOT).contains("public")
                    || String.valueOf(service.ec()).startsWith("0016-00000901")
                    || String.valueOf(service.errorMessage()).toLowerCase(Locale.ROOT)
                    .contains("public object acl"))) {
                return new OssPublishException("OSS_PUBLIC_ACCESS_BLOCKED",
                        ToolResult.ToolFailureType.PROVIDER, ToolResult.Retryability.NEVER,
                        ToolResult.EffectState.UNKNOWN, failure);
            }
            if (service.statusCode() == 429 || service.statusCode() >= 500) {
                return new OssPublishException("OSS_TEMPORARY_FAILURE",
                        ToolResult.ToolFailureType.NETWORK, ToolResult.Retryability.IDEMPOTENCY_REQUIRED,
                        ToolResult.EffectState.UNKNOWN, failure);
            }
        }
        return new OssPublishException("OSS_PUBLISH_FAILED", ToolResult.ToolFailureType.PROVIDER,
                ToolResult.Retryability.IDEMPOTENCY_REQUIRED, ToolResult.EffectState.UNKNOWN, failure);
    }

    private static String credentialFailureCode(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if ("OSS_INSTANCE_ROLE_UNAVAILABLE".equals(current.getMessage())) {
                return "OSS_INSTANCE_ROLE_UNAVAILABLE";
            }
            if ("OSS_CREDENTIALS_UNAVAILABLE".equals(current.getMessage())) {
                return "OSS_CREDENTIALS_UNAVAILABLE";
            }
        }
        return null;
    }

    private static String contentDisposition(String fileName) {
        String encoded = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename*=UTF-8''" + encoded;
    }

    private enum Phase { LOOKUP, UPLOAD_PRIVATE, VERIFY_PRIVATE, MAKE_PUBLIC }

    public record PublishedArtifact(String artifactId, String fileName, long size, String sha256,
                                    String objectKey, String publicUrl, String mimeType) { }

    public static final class OssPublishException extends RuntimeException {
        private final String code;
        private final ToolResult.ToolFailureType failureType;
        private final ToolResult.Retryability retryability;
        private final ToolResult.EffectState effectState;

        public OssPublishException(String code, ToolResult.ToolFailureType failureType,
                                   ToolResult.Retryability retryability,
                                   ToolResult.EffectState effectState, Throwable cause) {
            super(code, cause); this.code = code; this.failureType = failureType;
            this.retryability = retryability; this.effectState = effectState;
        }
        public String code() { return code; }
        public ToolResult.ToolFailureType failureType() { return failureType; }
        public ToolResult.Retryability retryability() { return retryability; }
        public ToolResult.EffectState effectState() { return effectState; }
        private OssPublishException withEffect(ToolResult.EffectState effect) {
            return new OssPublishException(code, failureType, retryability, effect, getCause());
        }
    }
}
