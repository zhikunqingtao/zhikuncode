package com.aicodeassistant.config.oss;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.text.Normalizer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Non-secret configuration and fail-closed validation for OSS publication. */
@Component
@ConfigurationProperties(prefix = "zhikuncode.oss")
public class OssPublishProperties {
    private static final Pattern BUCKET = Pattern.compile("[a-z0-9][a-z0-9-]{1,61}[a-z0-9]");
    private static final Pattern REGION = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)+");
    private static final Pattern SAFE_PREFIX = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,255}");
    private static final List<String> FORBIDDEN_LEGACY_CREDENTIAL_ENV = List.of(
            "OSS_ACCESS_KEY_ID", "OSS_ACCESS_KEY_SECRET", "OSS_SESSION_TOKEN");

    private boolean enabled;
    private String endpoint = "";
    private String region = "";
    private String bucket = "";
    private String prefix = "zhikuncode-artifacts";
    private String ecsRoleName = "";
    private String credentialMode = "auto";
    private long maxFileBytes = 100L * 1024 * 1024;
    private int connectTimeoutMs = 10_000;
    private int requestTimeoutMs = 120_000;

    public void requireReady() {
        requireReady(System.getenv());
    }

    void requireReady(Map<String, String> environment) {
        if (!enabled) throw new OssConfigurationException("OSS_PUBLISHING_DISABLED");
        rejectLegacyCredentials(environment);
        String normalizedRegion = trim(region).toLowerCase(Locale.ROOT);
        String normalizedBucket = trim(bucket).toLowerCase(Locale.ROOT);
        String normalizedPrefix = normalizedPrefix();
        if (!REGION.matcher(normalizedRegion).matches()) {
            throw new OssConfigurationException("OSS_REGION_INVALID");
        }
        if (!BUCKET.matcher(normalizedBucket).matches()) {
            throw new OssConfigurationException("OSS_BUCKET_INVALID");
        }
        CredentialMode mode = resolvedCredentialMode(environment);
        if (mode == CredentialMode.ECS_RAM_ROLE && trim(ecsRoleName).isEmpty()) {
            throw new OssConfigurationException("OSS_ECS_ROLE_REQUIRED");
        }
        if (!SAFE_PREFIX.matcher(normalizedPrefix).matches()
                || normalizedPrefix.contains("..") || normalizedPrefix.startsWith("/")) {
            throw new OssConfigurationException("OSS_PREFIX_INVALID");
        }
        URI uri = endpointUri();
        String expectedHost = "oss-" + normalizedRegion + ".aliyuncs.com";
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !expectedHost.equalsIgnoreCase(uri.getHost())
                || uri.getPort() != -1 || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null || !(uri.getPath() == null || uri.getPath().isEmpty()
                || "/".equals(uri.getPath()))) {
            throw new OssConfigurationException("OSS_ENDPOINT_INVALID");
        }
        if (maxFileBytes < 1 || maxFileBytes > 100L * 1024 * 1024) {
            throw new OssConfigurationException("OSS_FILE_LIMIT_INVALID");
        }
        if (connectTimeoutMs < 1_000 || connectTimeoutMs > 60_000
                || requestTimeoutMs < connectTimeoutMs || requestTimeoutMs > 300_000) {
            throw new OssConfigurationException("OSS_TIMEOUT_INVALID");
        }
    }

    static void rejectLegacyCredentials(Map<String, String> environment) {
        if (FORBIDDEN_LEGACY_CREDENTIAL_ENV.stream()
                .anyMatch(name -> !trim(environment.get(name)).isEmpty())) {
            throw new OssConfigurationException("OSS_CREDENTIAL_SOURCE_FORBIDDEN");
        }
    }

    /** Backward-compatible test/helper name; standard ALIBABA_CLOUD_* variables are allowed. */
    static void rejectStaticCredentials(Map<String, String> environment) {
        rejectLegacyCredentials(environment);
    }

    public CredentialMode resolvedCredentialMode() {
        return resolvedCredentialMode(System.getenv());
    }

    CredentialMode resolvedCredentialMode(Map<String, String> environment) {
        String value = trim(credentialMode).toLowerCase(Locale.ROOT).replace('-', '_');
        if (value.isEmpty() || "auto".equals(value)) {
            if (hasLocalEnvironmentCredentials(environment)) {
                return CredentialMode.DEFAULT_CHAIN;
            }
            return trim(ecsRoleName).isEmpty()
                    ? CredentialMode.DEFAULT_CHAIN : CredentialMode.ECS_RAM_ROLE;
        }
        return switch (value) {
            case "ecs", "ecs_ram_role" -> CredentialMode.ECS_RAM_ROLE;
            case "default", "default_chain", "local" -> CredentialMode.DEFAULT_CHAIN;
            default -> throw new OssConfigurationException("OSS_CREDENTIAL_MODE_INVALID");
        };
    }

    private static boolean hasLocalEnvironmentCredentials(Map<String, String> environment) {
        return !trim(environment.get("ALIBABA_CLOUD_ACCESS_KEY_ID")).isEmpty()
                && !trim(environment.get("ALIBABA_CLOUD_ACCESS_KEY_SECRET")).isEmpty();
    }

    /** Only URLs issued below the configured clipboard prefix may be sent back to a model. */
    public boolean isTrustedClipboardImageUrl(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            URI uri = URI.create(value);
            String expectedHost = bucket() + "." + endpointUri().getHost();
            String expectedPath = "/" + normalizedPrefix() + "/clipboard/";
            String rawPath = uri.getRawPath();
            return "https".equalsIgnoreCase(uri.getScheme())
                    && expectedHost.equalsIgnoreCase(uri.getHost())
                    && uri.getPort() == -1 && uri.getUserInfo() == null
                    && uri.getQuery() == null && uri.getFragment() == null
                    && rawPath != null && rawPath.startsWith(expectedPath)
                    && !rawPath.contains("%")
                    && rawPath.equals(uri.normalize().getRawPath());
        } catch (RuntimeException invalid) {
            return false;
        }
    }

    public String publicUrl(String objectKey) {
        try {
            return new URI("https", bucket() + "." + endpointUri().getHost(),
                    "/" + objectKey, null).toASCIIString();
        } catch (Exception invalid) {
            throw new OssConfigurationException("OSS_PUBLIC_URL_INVALID");
        }
    }

    public enum CredentialMode { ECS_RAM_ROLE, DEFAULT_CHAIN }

    public URI endpointUri() {
        try {
            return URI.create(trim(endpoint));
        } catch (RuntimeException invalid) {
            throw new OssConfigurationException("OSS_ENDPOINT_INVALID");
        }
    }

    public String normalizedPrefix() {
        String value = trim(prefix).replace('\\', '/');
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    public String safeObjectFileName(String original) {
        String normalized = Normalizer.normalize(original == null ? "artifact" : original,
                Normalizer.Form.NFKC);
        String safe = normalized.replaceAll("[^A-Za-z0-9._-]", "_")
                .replaceAll("_+", "_");
        if (safe.isBlank() || ".".equals(safe) || "..".equals(safe)) safe = "artifact";
        return safe.length() <= 120 ? safe : safe.substring(safe.length() - 120);
    }

    public String endpoint() { return endpointUri().toString(); }
    public String region() { return trim(region).toLowerCase(Locale.ROOT); }
    public String bucket() { return trim(bucket).toLowerCase(Locale.ROOT); }
    public String ecsRoleName() { return trim(ecsRoleName); }
    public Duration connectTimeout() { return Duration.ofMillis(connectTimeoutMs); }
    public Duration requestTimeout() { return Duration.ofMillis(requestTimeoutMs); }

    private static String trim(String value) { return value == null ? "" : value.trim(); }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }
    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }
    public String getPrefix() { return prefix; }
    public void setPrefix(String prefix) { this.prefix = prefix; }
    public String getEcsRoleName() { return ecsRoleName; }
    public void setEcsRoleName(String ecsRoleName) { this.ecsRoleName = ecsRoleName; }
    public String getCredentialMode() { return credentialMode; }
    public void setCredentialMode(String credentialMode) { this.credentialMode = credentialMode; }
    public long getMaxFileBytes() { return maxFileBytes; }
    public void setMaxFileBytes(long maxFileBytes) { this.maxFileBytes = maxFileBytes; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public int getRequestTimeoutMs() { return requestTimeoutMs; }
    public void setRequestTimeoutMs(int requestTimeoutMs) { this.requestTimeoutMs = requestTimeoutMs; }

    public static final class OssConfigurationException extends IllegalStateException {
        public OssConfigurationException(String code) { super(code); }
    }
}
