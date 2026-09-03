package com.aicodeassistant.tool.artifact;

import com.aicodeassistant.artifact.publication.ArtifactPublicationPolicy;
import com.aicodeassistant.artifact.publication.OssArtifactService;
import com.aicodeassistant.config.oss.OssPublishProperties;
import com.aicodeassistant.tool.InterruptBehavior;
import com.aicodeassistant.tool.PermissionRequirement;
import com.aicodeassistant.tool.Tool;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.ToolResult;
import com.aicodeassistant.tool.ToolUseContext;
import com.aicodeassistant.tool.ValidationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Explicitly publishes one exact, policy-checked workspace artifact after a non-reusable HIGH-risk decision. */
@Component
public class PublishArtifactTool implements Tool {
    private final ArtifactPublicationPolicy policy;
    private final OssArtifactService oss;
    private final OssPublishProperties properties;
    private final ObjectMapper json;

    public PublishArtifactTool(ArtifactPublicationPolicy policy, OssArtifactService oss,
                               OssPublishProperties properties, ObjectMapper json) {
        this.policy = policy;
        this.oss = oss;
        this.properties = properties;
        this.json = json;
    }

    @Override public String getName() { return "PublishArtifact"; }

    @Override public String getDescription() {
        return "Publish exactly one artifact at a user-provided exact path inside the current workspace to OSS "
                + "as a permanently public download. Never scan or guess a path; call only after an explicit request.";
    }

    @Override public Map<String, Object> getInputSchema() { return schema(); }
    @Override public Map<String, Object> getSchema() { return schema(); }

    private static Map<String, Object> schema() {
        return Map.of(
                "type", "object",
                "properties", Map.of("file_path", Map.of(
                        "type", "string",
                        "minLength", 1,
                        "maxLength", 4096,
                        "description", "Exact relative or absolute path of one artifact inside the current workspace")),
                "required", java.util.List.of("file_path"),
                "additionalProperties", false);
    }

    @Override public String getGroup() { return "artifact"; }
    @Override public PermissionRequirement getPermissionRequirement() { return PermissionRequirement.ALWAYS_ASK; }
    @Override public boolean isEnabled() { return properties.isEnabled(); }
    @Override public boolean isHighRisk() { return true; }
    @Override public boolean isOpenWorld() { return true; }
    @Override public boolean isConcurrencySafe(ToolInput input) { return false; }
    @Override public InterruptBehavior interruptBehavior() { return InterruptBehavior.BLOCK; }
    @Override public long getMaxExecutionTimeMs() { return properties.getRequestTimeoutMs() + 30_000L; }
    @Override public String userFacingName(ToolInput input) { return "Upload artifact to OSS"; }
    @Override public String getPath(ToolInput input) { return input.getString("file_path", null); }

    @Override
    public ValidationResult validateInput(ToolInput input, ToolUseContext context) {
        String path = input.getString("file_path", "");
        if (path.isBlank()) return ValidationResult.invalid("ARTIFACT_PATH_REQUIRED", "file_path is required");
        if (path.indexOf('\0') >= 0) return ValidationResult.invalid("ARTIFACT_PATH_INVALID", "file_path is invalid");
        return ValidationResult.ok();
    }

    @Override
    public ToolResult call(ToolInput input, ToolUseContext context) {
        String requestedPath = input.getString("file_path");
        try {
            OssArtifactService.PublishedArtifact published = policy.withLockedSnapshot(
                    requestedPath, context.currentRunId(), context.workingDirectory(), oss::publish);
            Map<String, Object> externalResource = new LinkedHashMap<>();
            externalResource.put("schema", "external-resource/v1");
            externalResource.put("kind", "download");
            externalResource.put("provider", "oss");
            externalResource.put("artifactId", published.artifactId());
            externalResource.put("url", published.publicUrl());
            externalResource.put("label", published.fileName());
            externalResource.put("size", published.size());
            externalResource.put("sha256", published.sha256());
            externalResource.put("objectKey", published.objectKey());
            externalResource.put("mimeType", published.mimeType());
            externalResource.put("permanentlyPublic", true);
            externalResource.put("downloadExpected", true);

            Map<String, Object> modelResult = new LinkedHashMap<>();
            modelResult.put("status", "published");
            modelResult.put("fileName", published.fileName());
            modelResult.put("size", published.size());
            modelResult.put("downloadCardAvailable", true);
            modelResult.put("htmlDownloadExpected", published.mimeType().startsWith("text/html"));

            // The LLM intentionally never receives the opaque URL/Object Key. The UI gets the
            // authoritative value through the allowlisted structuredResult metadata channel.
            String content = json.writeValueAsString(modelResult);
            return ToolResult.successWithEffect(content, ToolResult.EffectState.APPLIED,
                    Map.of("structuredResult", externalResource));
        } catch (OssPublishProperties.OssConfigurationException configuration) {
            return ToolResult.failed(ToolResult.ToolFailureType.PROVIDER, configuration.getMessage(),
                    publicMessage(configuration.getMessage()), ToolResult.Retryability.NEVER,
                    ToolResult.EffectState.NOT_STARTED, null, Map.of());
        } catch (ArtifactPublicationPolicy.ArtifactPublicationException denied) {
            return ToolResult.validationError(denied.code(), publicMessage(denied.code()));
        } catch (OssArtifactService.OssPublishException failure) {
            return ToolResult.failed(failure.failureType(), failure.code(), publicMessage(failure.code()),
                    failure.retryability(), failure.effectState(), null, Map.of());
        } catch (Exception failure) {
            return ToolResult.internalError("OSS_PUBLISH_INTERNAL_ERROR",
                    "OSS publication failed without exposing internal details",
                    ToolResult.EffectState.UNKNOWN);
        }
    }

    private static String publicMessage(String code) {
        return switch (code) {
            case "OSS_PUBLISHING_DISABLED" -> "OSS artifact publication is disabled on this deployment.";
            case "OSS_CREDENTIAL_SOURCE_FORBIDDEN" -> "Legacy OSS_* credential variables are forbidden; use an ECS RAM role or the Alibaba Cloud default credential chain.";
            case "OSS_ECS_ROLE_REQUIRED", "OSS_INSTANCE_ROLE_UNAVAILABLE" -> "The ECS instance RAM role is unavailable.";
            case "OSS_CREDENTIALS_UNAVAILABLE" -> "No local Alibaba Cloud credentials were found in the default credential chain.";
            case "OSS_PUBLIC_ACCESS_BLOCKED" -> "The bucket blocks public object access; the private upload was cleaned up.";
            case "ARTIFACT_NOT_IN_CURRENT_SESSION" ->
                    "The requested file is not a declared artifact of the current session.";
            case "ARTIFACT_NOT_VERIFIED", "ARTIFACT_HASH_CHANGED", "ARTIFACT_CHANGED_BEFORE_UPLOAD",
                    "ARTIFACT_CHANGED_DURING_UPLOAD" -> "The artifact is not currently verified or changed after verification.";
            case "ARTIFACT_SENSITIVE_FILE_FORBIDDEN", "ARTIFACT_SENSITIVE_CONTENT_FORBIDDEN" ->
                    "The artifact was rejected by the sensitive-data policy.";
            case "ARTIFACT_TOO_LARGE" -> "The artifact exceeds the configured single-file upload limit.";
            case "OSS_TEMPORARY_FAILURE" -> "OSS is temporarily unavailable. A new explicit retry is required.";
            default -> "OSS publication was rejected: " + code;
        };
    }
}
