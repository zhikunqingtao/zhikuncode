package com.aicodeassistant.controller;

import com.aicodeassistant.artifact.publication.BrowserFilePublicationService;
import com.aicodeassistant.artifact.publication.ClipboardImagePublicationService;
import com.aicodeassistant.config.RawFileUploadWebConfig;
import com.aicodeassistant.config.oss.OssPublishProperties;
import com.aicodeassistant.security.SessionAccessAuthorizer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OssClipboardImageController.class)
@Import(RawFileUploadWebConfig.class)
class OssLocalFileRawBodyWebTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OssPublishProperties properties;

    @MockitoBean
    private ClipboardImagePublicationService clipboardImages;

    @MockitoBean
    private BrowserFilePublicationService browserFiles;

    @MockitoBean
    private SessionAccessAuthorizer access;

    @Test
    @WithMockUser
    void preservesFormLikeMimeTypesAndBodiesAboveTheMultipartLimit()
            throws Exception {
        assertRawUpload("说明+.txt", "application/x-www-form-urlencoded",
                "name=value&still=file-data".getBytes(
                        java.nio.charset.StandardCharsets.UTF_8));
        assertRawUpload("multipart.bin",
                "multipart/form-data; boundary=selected-file",
                new byte[6 * 1024 * 1024 + 1]);
    }

    private void assertRawUpload(String fileName, String mediaType, byte[] contents)
            throws Exception {
        when(access.canAccessSession("session-a", "session-a"))
                .thenReturn(true);
        when(browserFiles.publish(eq("session-a"), eq(fileName),
                argThat(value -> value != null && value.startsWith(mediaType)),
                eq((long) contents.length), any(InputStream.class)))
                .thenAnswer(invocation -> {
                    InputStream input = invocation.getArgument(4);
                    String actualMediaType = invocation.getArgument(2);
                    byte[] received = input.readAllBytes();
                    assertThat(received).containsExactly(contents);
                    String sha256 = HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256").digest(received));
                    return new BrowserFilePublicationService.PublishedBrowserFile(
                            "local-id", fileName, received.length, sha256,
                            "https://bucket.example/local-files/" + fileName,
                            actualMediaType);
                });

        String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8);
        mockMvc.perform(post(URI.create(
                        "/api/oss/local-files?fileName=" + encodedFileName))
                        .header("X-Session-Id", "session-a")
                        .contentType(mediaType)
                        .content(contents)
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileName").value(fileName))
                .andExpect(jsonPath("$.size").value(contents.length));
    }
}
