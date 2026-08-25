package com.aicodeassistant.controller;

import com.aicodeassistant.artifact.publication.ClipboardImagePublicationService;
import com.aicodeassistant.artifact.publication.OssArtifactService;
import com.aicodeassistant.config.oss.OssPublishProperties;
import com.aicodeassistant.security.SessionAccessAuthorizer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/** OSS capability discovery and the low-latency clipboard-image publish endpoint. */
@RestController
@RequestMapping("/api/oss")
public class OssClipboardImageController {
    private final OssPublishProperties properties;
    private final ClipboardImagePublicationService clipboardImages;
    private final SessionAccessAuthorizer access;

    public OssClipboardImageController(OssPublishProperties properties,
                                       ClipboardImagePublicationService clipboardImages,
                                       SessionAccessAuthorizer access) {
        this.properties = properties;
        this.clipboardImages = clipboardImages;
        this.access = access;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        String error = null;
        boolean configured = false;
        try {
            properties.requireReady();
            configured = true;
        } catch (OssPublishProperties.OssConfigurationException failure) {
            error = failure.getMessage();
        }
        return Map.of(
                "enabled", properties.isEnabled(),
                "configured", configured,
                "credentialMode", safeCredentialMode(),
                "maxClipboardImageBytes", ClipboardImagePublicationService.MAX_CLIPBOARD_IMAGE_BYTES,
                "error", error == null ? "" : error);
    }

    @PostMapping(value = "/clipboard-images", consumes = "multipart/form-data")
    public ResponseEntity<?> publishClipboardImage(
            @RequestHeader("X-Session-Id") String sessionId,
            @RequestParam("file") MultipartFile file) {
        if (!access.canAccessSession(sessionId, sessionId)) {
            return error(HttpStatus.FORBIDDEN, "SESSION_ACCESS_DENIED");
        }
        try {
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(clipboardImages.publish(sessionId, file));
        } catch (OssPublishProperties.OssConfigurationException configuration) {
            return error(HttpStatus.SERVICE_UNAVAILABLE, configuration.getMessage());
        } catch (ClipboardImagePublicationService.ClipboardImageException invalid) {
            return error(HttpStatus.BAD_REQUEST, invalid.code());
        } catch (OssArtifactService.OssPublishException provider) {
            return error(HttpStatus.BAD_GATEWAY, provider.code());
        }
    }

    private String safeCredentialMode() {
        try {
            return properties.resolvedCredentialMode().name().toLowerCase(java.util.Locale.ROOT);
        } catch (OssPublishProperties.OssConfigurationException invalid) {
            return "invalid";
        }
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String code) {
        return ResponseEntity.status(status).body(Map.of("error", code));
    }
}
