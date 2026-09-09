package com.aicodeassistant.controller;

import com.aicodeassistant.artifact.publication.BrowserFilePublicationService;
import com.aicodeassistant.artifact.publication.ClipboardImagePublicationService;
import com.aicodeassistant.artifact.publication.OssArtifactService;
import com.aicodeassistant.config.oss.OssPublishProperties;
import com.aicodeassistant.security.SessionAccessAuthorizer;
import com.aicodeassistant.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.InputStream;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OssLocalFileControllerTest {
    private OssPublishProperties properties;
    private BrowserFilePublicationService browserFiles;
    private SessionAccessAuthorizer access;
    private OssClipboardImageController controller;

    @BeforeEach
    void setUp() {
        properties = new OssPublishProperties();
        browserFiles = mock(BrowserFilePublicationService.class);
        access = mock(SessionAccessAuthorizer.class);
        controller = new OssClipboardImageController(
                properties, mock(ClipboardImagePublicationService.class),
                browserFiles, access);
    }

    @Test
    void requiresSessionOwnershipBeforeReadingOrPublishing() {
        MockHttpServletRequest request = request();
        when(access.canAccessSession("session-a", "session-a")).thenReturn(false);

        var response = controller.publishLocalFile("session-a", request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(errorCode(response.getBody())).isEqualTo("SESSION_ACCESS_DENIED");
        verify(browserFiles, never()).publish(anyString(), anyString(), any(),
                anyLong(), any(InputStream.class));
    }

    @Test
    void mapsValidationConfigurationAndProviderFailures() {
        MockHttpServletRequest request = request();
        when(access.canAccessSession("session-a", "session-a")).thenReturn(true);
        when(browserFiles.publish(anyString(), anyString(), anyString(),
                anyLong(), any(InputStream.class)))
                .thenThrow(new BrowserFilePublicationService.BrowserFileException(
                        HttpStatus.BAD_REQUEST, "LOCAL_FILE_REQUIRED", null))
                .thenThrow(new BrowserFilePublicationService.BrowserFileException(
                        HttpStatus.PAYLOAD_TOO_LARGE, "LOCAL_FILE_TOO_LARGE", null))
                .thenThrow(new OssPublishProperties.OssConfigurationException(
                        "OSS_PUBLISHING_DISABLED"))
                .thenThrow(new OssArtifactService.OssPublishException(
                        "OSS_TEMPORARY_FAILURE", ToolResult.ToolFailureType.NETWORK,
                        ToolResult.Retryability.IDEMPOTENCY_REQUIRED,
                        ToolResult.EffectState.NONE, null));

        var invalid = controller.publishLocalFile("session-a", request);
        var tooLarge = controller.publishLocalFile("session-a", request);
        var unavailable = controller.publishLocalFile("session-a", request);
        var provider = controller.publishLocalFile("session-a", request);

        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(errorCode(invalid.getBody())).isEqualTo("LOCAL_FILE_REQUIRED");
        assertThat(tooLarge.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(errorCode(tooLarge.getBody())).isEqualTo("LOCAL_FILE_TOO_LARGE");
        assertThat(unavailable.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(errorCode(unavailable.getBody())).isEqualTo("OSS_PUBLISHING_DISABLED");
        assertThat(provider.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(errorCode(provider.getBody())).isEqualTo("OSS_TEMPORARY_FAILURE");
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest() {
            @Override
            public String getParameter(String name) {
                throw new AssertionError("raw upload must not parse request parameters");
            }
        };
        request.setMethod("POST");
        request.setRequestURI("/api/oss/local-files");
        request.setQueryString("fileName=note.txt");
        request.setContentType("text/plain");
        request.setContent(new byte[] { 1, 2, 3 });
        return request;
    }

    @SuppressWarnings("unchecked")
    private static String errorCode(Object body) {
        return (String) ((Map<String, ?>) body).get("error");
    }
}
