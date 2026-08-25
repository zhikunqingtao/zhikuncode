package com.aicodeassistant.artifact.publication;

import com.aicodeassistant.config.oss.OssPublishProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/** Fast path for an explicit browser paste: validate, publish, and return a trusted image URL. */
@Service
public class ClipboardImagePublicationService {
    public static final long MAX_CLIPBOARD_IMAGE_BYTES = 5L * 1024 * 1024;

    private final OssPublishProperties properties;
    private final OssArtifactService oss;

    public ClipboardImagePublicationService(OssPublishProperties properties, OssArtifactService oss) {
        this.properties = properties;
        this.oss = oss;
    }

    public PublishedClipboardImage publish(String sessionId, MultipartFile file) {
        properties.requireReady();
        if (sessionId == null || sessionId.isBlank()) throw invalid("CLIPBOARD_SESSION_REQUIRED");
        if (file == null || file.isEmpty()) throw invalid("CLIPBOARD_IMAGE_REQUIRED");
        if (file.getSize() > MAX_CLIPBOARD_IMAGE_BYTES) throw invalid("CLIPBOARD_IMAGE_TOO_LARGE");

        Path temporary = null;
        try {
            ImageFormat format;
            try (InputStream input = file.getInputStream()) {
                format = detectFormat(input.readNBytes(16));
            }
            if (format == null) throw invalid("CLIPBOARD_IMAGE_TYPE_UNSUPPORTED");
            String declared = file.getContentType();
            if (declared != null && !declared.isBlank()
                    && !format.mediaType().equals(declared.toLowerCase(Locale.ROOT))) {
                throw invalid("CLIPBOARD_IMAGE_TYPE_MISMATCH");
            }

            temporary = Files.createTempFile("zhikuncode-clipboard-", "." + format.extension());
            try (InputStream input = file.getInputStream()) {
                Files.copy(input, temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            long size = Files.size(temporary);
            if (size < 1 || size > MAX_CLIPBOARD_IMAGE_BYTES) {
                throw invalid("CLIPBOARD_IMAGE_TOO_LARGE");
            }
            String sha256 = sha256(temporary);
            String artifactId = UUID.randomUUID().toString();
            String fileName = "clipboard-" + Instant.now().toEpochMilli() + "." + format.extension();
            String sessionScope = sha256(sessionId).substring(0, 16);
            String objectKey = properties.normalizedPrefix() + "/clipboard/" + sessionScope + "/"
                    + artifactId + "/" + sha256 + "-" + fileName;
            String publicUrl = properties.publicUrl(objectKey);

            ArtifactPublicationPolicy.Snapshot snapshot = new ArtifactPublicationPolicy.Snapshot(
                    artifactId, "clipboard", sessionId, fileName, temporary, fileName, size,
                    sha256, format.mediaType(), objectKey, publicUrl,
                    properties.bucket(), properties.endpoint());
            OssArtifactService.PublishedArtifact published = oss.publish(snapshot);
            if (!properties.isTrustedClipboardImageUrl(published.publicUrl())) {
                throw invalid("CLIPBOARD_OSS_URL_UNTRUSTED");
            }
            return new PublishedClipboardImage(published.artifactId(), published.fileName(),
                    published.size(), published.sha256(), published.publicUrl(), published.mimeType());
        } catch (ClipboardImageException known) {
            throw known;
        } catch (OssPublishProperties.OssConfigurationException
                 | OssArtifactService.OssPublishException known) {
            throw known;
        } catch (Exception failure) {
            throw new ClipboardImageException("CLIPBOARD_IMAGE_PUBLISH_FAILED", failure);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (Exception ignored) { }
            }
        }
    }

    private static ImageFormat detectFormat(byte[] header) {
        if (header.length >= 8
                && (header[0] & 0xff) == 0x89 && header[1] == 0x50
                && header[2] == 0x4e && header[3] == 0x47
                && header[4] == 0x0d && header[5] == 0x0a
                && header[6] == 0x1a && header[7] == 0x0a) {
            return new ImageFormat("image/png", "png");
        }
        if (header.length >= 3 && (header[0] & 0xff) == 0xff
                && (header[1] & 0xff) == 0xd8 && (header[2] & 0xff) == 0xff) {
            return new ImageFormat("image/jpeg", "jpg");
        }
        if (header.length >= 12 && header[0] == 'R' && header[1] == 'I'
                && header[2] == 'F' && header[3] == 'F'
                && header[8] == 'W' && header[9] == 'E'
                && header[10] == 'B' && header[11] == 'P') {
            return new ImageFormat("image/webp", "webp");
        }
        return null;
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = input.read(buffer)) != -1; ) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static ClipboardImageException invalid(String code) {
        return new ClipboardImageException(code, null);
    }

    private record ImageFormat(String mediaType, String extension) { }

    public record PublishedClipboardImage(String artifactId, String fileName, long size,
                                          String sha256, String url, String mediaType) { }

    public static final class ClipboardImageException extends IllegalArgumentException {
        private final String code;
        public ClipboardImageException(String code, Throwable cause) { super(code, cause); this.code = code; }
        public String code() { return code; }
    }
}
