package com.aicodeassistant.artifact.publication;

import com.aicodeassistant.artifact.ArtifactEntry;
import com.aicodeassistant.artifact.ArtifactManifest;
import com.aicodeassistant.artifact.ArtifactManifestService;
import com.aicodeassistant.config.oss.OssPublishProperties;
import com.aicodeassistant.security.ManagedPathLockManager;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Selects exactly one verified artifact and freezes all facts shown in the permission prompt. */
@Service
public class ArtifactPublicationPolicy {
    private static final int SCAN_CHUNK_BYTES = 64 * 1024;
    private static final int SCAN_OVERLAP_BYTES = 512;
    private static final Set<String> DATABASE_SUFFIXES = Set.of(
            ".db", ".sqlite", ".sqlite3", ".mdb", ".accdb");
    private static final Set<String> PRIVATE_KEY_SUFFIXES = Set.of(
            ".pem", ".key", ".p12", ".pfx", ".jks", ".keystore");
    private static final List<Pattern> SECRET_PATTERNS = List.of(
            Pattern.compile("sk-[A-Za-z0-9_-]{20,}"),
            Pattern.compile("sk-ant-[A-Za-z0-9_-]{20,}"),
            Pattern.compile("gh[pousr]_[A-Za-z0-9]{20,}"),
            Pattern.compile("LTAI[A-Za-z0-9]{12,}"),
            Pattern.compile("AKIA[0-9A-Z]{16}"),
            Pattern.compile("eyJ[A-Za-z0-9_-]{10,}\\.eyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}"),
            Pattern.compile("-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----"),
            Pattern.compile("(?i)(?:api[_-]?key|access[_-]?key|secret|password|token|credential)"
                    + "\\s*[=:]\\s*['\"]?[A-Za-z0-9_./+\\-=]{16,}"),
            Pattern.compile("(?i)(?:mongodb|postgres(?:ql)?|mysql|redis)://[^\\s:@]+:[^\\s@]{4,}@"));

    private final ArtifactManifestService manifests;
    private final ManagedPathLockManager pathLocks;
    private final OssPublishProperties properties;

    public ArtifactPublicationPolicy(ArtifactManifestService manifests,
                                     ManagedPathLockManager pathLocks,
                                     OssPublishProperties properties) {
        this.manifests = manifests;
        this.pathLocks = pathLocks;
        this.properties = properties;
    }

    public Snapshot inspect(String requestedPath, String currentRunId, String currentWorkspaceRoot) {
        properties.requireReady();
        if (currentRunId == null || currentRunId.isBlank()) {
            throw denied("ARTIFACT_RUN_REQUIRED");
        }
        Path configuredWorkspace = absoluteNormalizedPath(
                currentWorkspaceRoot, "ARTIFACT_WORKSPACE_INVALID");
        Path workspace = realDirectory(currentWorkspaceRoot, "ARTIFACT_WORKSPACE_INVALID");
        Path target = resolveRequestedPath(requestedPath, configuredWorkspace, workspace);
        rejectSymlinkSegments(workspace, target);
        List<ArtifactManifest> candidates = manifests.getManifestsForRunSession(currentRunId);
        ManifestSelection selection = selectLatestExactArtifact(candidates, target);
        String artifactId;
        String manifestId;
        String sourceRunId;
        String verifiedHash = null;
        if (selection != null) {
            ArtifactManifest manifest = selection.manifest();
            ArtifactEntry entry = selection.entry();
            if (!("verified".equals(manifest.status()) || "partial".equals(manifest.status()))) {
                throw denied("ARTIFACT_MANIFEST_NOT_VERIFIED");
            }
            if (!entry.verified()) throw denied("ARTIFACT_NOT_VERIFIED");
            if (!("created".equals(entry.operation()) || "modified".equals(entry.operation()))) {
                throw denied("ARTIFACT_OPERATION_NOT_PUBLISHABLE");
            }
            artifactId = entry.id();
            manifestId = manifest.id();
            sourceRunId = manifest.runId();
            verifiedHash = entry.actualHash() == null ? entry.expectedHash() : entry.actualHash();
        } else {
            Path relative = workspace.relativize(target);
            artifactId = "workspace-" + digestHex(
                    workspace + "\0" + relative.toString().replace('\\', '/')).substring(0, 32);
            manifestId = "workspace";
            sourceRunId = currentRunId;
        }
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
            throw denied("ARTIFACT_NOT_REGULAR_FILE");
        }

        Path relative = workspace.relativize(target);
        rejectSensitiveName(relative);
        long size = fileSize(target);
        if (size > properties.getMaxFileBytes()) throw denied("ARTIFACT_TOO_LARGE");
        String sha256 = sha256(target);
        if (selection != null && (verifiedHash == null || !sha256.equalsIgnoreCase(verifiedHash))) {
            throw denied("ARTIFACT_HASH_CHANGED");
        }
        scanForSecrets(target);

        String filename = target.getFileName().toString();
        String objectKey = properties.normalizedPrefix() + "/" + manifestId + "/"
                + artifactId + "/" + sha256 + "-" + properties.safeObjectFileName(filename);
        String publicUrl = publicUrl(objectKey);
        return new Snapshot(artifactId, manifestId, sourceRunId, relative.toString().replace('\\', '/'),
                target, filename, size, sha256, detectMimeType(target), objectKey, publicUrl,
                properties.bucket(), properties.endpoint());
    }

    private ManifestSelection selectLatestExactArtifact(List<ArtifactManifest> candidates, Path target) {
        for (ArtifactManifest candidate : candidates) {
            ArtifactEntry entry = candidate.entries().stream()
                    .filter(item -> samePath(item.filePath(), target))
                    .findFirst().orElse(null);
            if (entry != null) return new ManifestSelection(candidate, entry);
        }
        return null;
    }

    public <T> T withLockedSnapshot(String requestedPath, String currentRunId,
                                    String currentWorkspaceRoot,
                                    CheckedFunction<Snapshot, T> action) throws Exception {
        Snapshot beforeLock = inspect(requestedPath, currentRunId, currentWorkspaceRoot);
        return pathLocks.withLock(beforeLock.path(), () -> {
            Snapshot locked = inspect(requestedPath, currentRunId, currentWorkspaceRoot);
            if (!beforeLock.sameSecurityFacts(locked)) throw denied("ARTIFACT_CHANGED_BEFORE_UPLOAD");
            return action.apply(locked);
        });
    }

    public String sha256(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = input.read(buffer)) != -1; ) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception failure) {
            throw denied("ARTIFACT_HASH_FAILED");
        }
    }

    private static String digestHex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw denied("ARTIFACT_ID_FAILED");
        }
    }

    private Path resolveRequestedPath(String requestedPath, Path configuredWorkspace, Path workspace) {
        if (requestedPath == null || requestedPath.isBlank()) throw denied("ARTIFACT_PATH_REQUIRED");
        Path raw;
        try {
            raw = Path.of(requestedPath);
        } catch (RuntimeException invalid) {
            throw denied("ARTIFACT_PATH_INVALID");
        }
        for (Path segment : raw) {
            if ("..".equals(segment.toString())) throw denied("ARTIFACT_PATH_ESCAPE");
        }
        Path target;
        if (raw.isAbsolute()) {
            Path normalized = raw.toAbsolutePath().normalize();
            target = normalized.startsWith(configuredWorkspace)
                    ? workspace.resolve(configuredWorkspace.relativize(normalized)).normalize()
                    : normalized;
        } else {
            target = workspace.resolve(raw).toAbsolutePath().normalize();
        }
        if (!target.startsWith(workspace)) throw denied("ARTIFACT_PATH_ESCAPE");
        return target;
    }

    private void rejectSymlinkSegments(Path workspace, Path target) {
        Path current = workspace;
        for (Path segment : workspace.relativize(target)) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) throw denied("ARTIFACT_SYMLINK_FORBIDDEN");
        }
    }

    private void rejectSensitiveName(Path relative) {
        for (Path segment : relative) {
            String name = segment.toString().toLowerCase(Locale.ROOT);
            if (name.equals(".git") || name.equals(".ssh") || name.equals(".aws")
                    || name.equals("credentials") || name.equals("secrets")
                    || name.startsWith(".env") || name.equals("id_rsa") || name.equals("id_ed25519")
                    || hasSuffix(name, DATABASE_SUFFIXES) || hasSuffix(name, PRIVATE_KEY_SUFFIXES)) {
                throw denied("ARTIFACT_SENSITIVE_FILE_FORBIDDEN");
            }
        }
    }

    private void scanForSecrets(Path path) {
        byte[] buffer = new byte[SCAN_CHUNK_BYTES];
        byte[] overlap = new byte[0];
        try (InputStream input = Files.newInputStream(path)) {
            for (int read; (read = input.read(buffer)) != -1; ) {
                byte[] combined = new byte[overlap.length + read];
                System.arraycopy(overlap, 0, combined, 0, overlap.length);
                System.arraycopy(buffer, 0, combined, overlap.length, read);
                String window = new String(combined, StandardCharsets.ISO_8859_1);
                if (SECRET_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(window).find())) {
                    throw denied("ARTIFACT_SENSITIVE_CONTENT_FORBIDDEN");
                }
                int keep = Math.min(SCAN_OVERLAP_BYTES, combined.length);
                overlap = new byte[keep];
                System.arraycopy(combined, combined.length - keep, overlap, 0, keep);
            }
        } catch (ArtifactPublicationException denied) {
            throw denied;
        } catch (Exception failure) {
            throw denied("ARTIFACT_CONTENT_SCAN_FAILED");
        }
    }

    private String publicUrl(String objectKey) {
        try {
            URI endpoint = properties.endpointUri();
            return new URI("https", properties.bucket() + "." + endpoint.getHost(),
                    "/" + objectKey, null).toASCIIString();
        } catch (Exception invalid) {
            throw denied("OSS_PUBLIC_URL_INVALID");
        }
    }

    private static Path realDirectory(String value, String code) {
        try {
            Path path = Path.of(value).toRealPath();
            if (!Files.isDirectory(path)) throw denied(code);
            return path;
        } catch (ArtifactPublicationException denied) {
            throw denied;
        } catch (Exception failure) {
            throw denied(code);
        }
    }

    private static Path absoluteNormalizedPath(String value, String code) {
        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (Exception failure) {
            throw denied(code);
        }
    }

    private static boolean samePath(String stored, Path target) {
        try {
            Path storedPath = Path.of(stored).toAbsolutePath().normalize();
            if (Files.exists(storedPath) && Files.exists(target)) {
                return Files.isSameFile(storedPath, target);
            }
            return storedPath.equals(target);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static long fileSize(Path path) {
        try { return Files.size(path); }
        catch (Exception failure) { throw denied("ARTIFACT_SIZE_FAILED"); }
    }

    private static String detectMimeType(Path path) {
        try {
            String detected = Files.probeContentType(path);
            if (detected != null && !detected.isBlank()) return detected;
        } catch (Exception ignored) { }
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".txt") || lower.endsWith(".md")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }

    private static boolean hasSuffix(String value, Set<String> suffixes) {
        return suffixes.stream().anyMatch(value::endsWith);
    }

    private static ArtifactPublicationException denied(String code) {
        return new ArtifactPublicationException(code);
    }

    public record Snapshot(String artifactId, String manifestId, String runId, String relativePath,
                           Path path, String fileName, long size, String sha256, String mimeType,
                           String objectKey, String publicUrl, String bucket, String endpoint) {
        public boolean sameSecurityFacts(Snapshot other) {
            return other != null && artifactId.equals(other.artifactId) && manifestId.equals(other.manifestId)
                    && runId.equals(other.runId) && path.equals(other.path) && size == other.size
                    && sha256.equals(other.sha256) && objectKey.equals(other.objectKey)
                    && bucket.equals(other.bucket) && endpoint.equals(other.endpoint);
        }

        public Map<String, Object> authorizationFacts() {
            return Map.of("artifactId", artifactId, "manifestId", manifestId, "runId", runId,
                    "relativePath", relativePath, "size", size, "sha256", sha256,
                    "objectKey", objectKey, "bucket", bucket, "endpoint", endpoint);
        }
    }

    private record ManifestSelection(ArtifactManifest manifest, ArtifactEntry entry) { }

    @FunctionalInterface
    public interface CheckedFunction<T, R> { R apply(T value) throws Exception; }

    public static final class ArtifactPublicationException extends IllegalArgumentException {
        private final String code;
        public ArtifactPublicationException(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
}
