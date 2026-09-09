package com.aicodeassistant.artifact.publication;

import com.aicodeassistant.config.oss.OssPublishProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.HexFormat;

/** Publishes a file explicitly selected in a remote browser. */
@Service
public class BrowserFilePublicationService {
    private static final int COPY_BUFFER_BYTES = 64 * 1024;
    private static final int MAX_DISPLAY_NAME_CHARS = 255;

    private final OssPublishProperties properties;
    private final OssArtifactService oss;

    public BrowserFilePublicationService(OssPublishProperties properties,
                                         OssArtifactService oss) {
        this.properties = properties;
        this.oss = oss;
    }

    public PublishedBrowserFile publish(String sessionId,
                                        String requestedFileName,
                                        String declaredMediaType,
                                        long declaredSize,
                                        InputStream input) {
        properties.requireReady();
        if (sessionId == null || sessionId.isBlank()) {
            throw invalid(HttpStatus.BAD_REQUEST, "LOCAL_FILE_SESSION_REQUIRED");
        }
        String fileName = normalizeDisplayName(requestedFileName);
        if (input == null) {
            throw invalid(HttpStatus.BAD_REQUEST, "LOCAL_FILE_REQUIRED");
        }
        long maxBytes = properties.getMaxFileBytes();
        if (declaredSize == 0) {
            throw invalid(HttpStatus.BAD_REQUEST, "LOCAL_FILE_REQUIRED");
        }
        if (declaredSize > maxBytes) {
            throw invalid(HttpStatus.PAYLOAD_TOO_LARGE, "LOCAL_FILE_TOO_LARGE");
        }

        Path temporary = null;
        try {
            temporary = Files.createTempFile("zhikuncode-browser-file-", ".upload");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = copyWithLimit(input, temporary, digest, maxBytes);
            if (size == 0) {
                throw invalid(HttpStatus.BAD_REQUEST, "LOCAL_FILE_REQUIRED");
            }
            if (declaredSize > 0 && size != declaredSize) {
                throw invalid(HttpStatus.BAD_REQUEST, "LOCAL_FILE_LENGTH_MISMATCH");
            }

            String sha256 = HexFormat.of().formatHex(digest.digest());
            String safeName = properties.safeObjectFileName(fileName);
            String sessionScope = digest(sessionId).substring(0, 16);
            String artifactId = "local-" + digest(
                    sessionId + "\0" + sha256 + "\0" + safeName).substring(0, 32);
            String objectKey = properties.normalizedPrefix() + "/local-files/"
                    + sessionScope + "/" + sha256 + "-" + safeName;
            String publicUrl = properties.publicUrl(objectKey);
            if (!properties.isTrustedLocalFileUrl(publicUrl)) {
                throw invalid(HttpStatus.BAD_REQUEST,
                        "LOCAL_FILE_OSS_URL_UNTRUSTED");
            }
            String mediaType = normalizeMediaType(declaredMediaType);

            ArtifactPublicationPolicy.Snapshot snapshot =
                    new ArtifactPublicationPolicy.Snapshot(
                            artifactId, "local-files", sessionId, fileName,
                            temporary, fileName, size, sha256, mediaType,
                            objectKey, publicUrl, properties.bucket(),
                            properties.endpoint());
            oss.publish(snapshot);
            return new PublishedBrowserFile(
                    artifactId, fileName, size, sha256, publicUrl, mediaType);
        } catch (BrowserFileException known) {
            throw known;
        } catch (OssPublishProperties.OssConfigurationException
                 | OssArtifactService.OssPublishException known) {
            throw known;
        } catch (Exception failure) {
            throw new BrowserFileException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "LOCAL_FILE_PUBLISH_FAILED", failure);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (Exception ignored) {
                    // The upload result must not be changed by best-effort temp cleanup.
                }
            }
        }
    }

    private static long copyWithLimit(InputStream input, Path target,
                                      MessageDigest digest, long maxBytes)
            throws Exception {
        long total = 0;
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        try (InputStream source = input;
             OutputStream output = Files.newOutputStream(target)) {
            while (true) {
                final int read;
                try {
                    read = source.read(buffer);
                } catch (IOException unreadable) {
                    throw new BrowserFileException(HttpStatus.BAD_REQUEST,
                            "LOCAL_FILE_READ_FAILED", unreadable);
                }
                if (read == -1) break;
                if (read == 0) continue;
                if (total > maxBytes - read) {
                    throw invalid(HttpStatus.PAYLOAD_TOO_LARGE,
                            "LOCAL_FILE_TOO_LARGE");
                }
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                total += read;
            }
        }
        return total;
    }

    private static String normalizeDisplayName(String requested) {
        if (requested == null || requested.isBlank()) {
            throw invalid(HttpStatus.BAD_REQUEST, "LOCAL_FILE_NAME_REQUIRED");
        }
        String normalized = Normalizer.normalize(requested, Normalizer.Form.NFKC)
                .replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0) normalized = normalized.substring(slash + 1);
        normalized = normalized.replaceAll("[\\p{Cntrl}]", "").trim();
        if (normalized.isBlank() || ".".equals(normalized)
                || "..".equals(normalized)) {
            throw invalid(HttpStatus.BAD_REQUEST, "LOCAL_FILE_NAME_INVALID");
        }
        return normalized.length() <= MAX_DISPLAY_NAME_CHARS
                ? normalized
                : normalized.substring(normalized.length() - MAX_DISPLAY_NAME_CHARS);
    }

    private static String normalizeMediaType(String value) {
        if (value == null || value.isBlank()) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
        try {
            MediaType parsed = MediaType.parseMediaType(value);
            return parsed.isWildcardType() || parsed.isWildcardSubtype()
                    ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                    : parsed.toString();
        } catch (IllegalArgumentException invalid) {
            return MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
    }

    private static String digest(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static BrowserFileException invalid(HttpStatus status, String code) {
        return new BrowserFileException(status, code, null);
    }

    public record PublishedBrowserFile(String artifactId, String fileName,
                                       long size, String sha256, String url,
                                       String mediaType) { }

    public static final class BrowserFileException extends IllegalArgumentException {
        private final HttpStatus status;
        private final String code;

        public BrowserFileException(HttpStatus status, String code,
                                    Throwable cause) {
            super(code, cause);
            this.status = status;
            this.code = code;
        }

        public HttpStatus status() { return status; }
        public String code() { return code; }
    }
}
