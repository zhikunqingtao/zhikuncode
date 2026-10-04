package com.aicodeassistant.verify;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class EvidenceControllerBlobTest {

    private static final String SHA256 = "ab12".repeat(16);

    @Test
    void servesBlobAsAttachmentWithBothFilenameForms() {
        EvidenceStore store = mock(EvidenceStore.class);
        byte[] bytes = "evidence-blob".getBytes(StandardCharsets.UTF_8);
        when(store.readBlob(SHA256)).thenReturn(Optional.of(bytes));

        ResponseEntity<byte[]> response = new EvidenceController(store).getBlob(SHA256);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("attachment; filename=\"" + SHA256 + "\";"
                        + " filename*=UTF-8''" + SHA256);
        assertThat(response.getBody()).containsExactly(bytes);
    }

    @Test
    void missingBlobRemainsNotFound() {
        EvidenceStore store = mock(EvidenceStore.class);
        when(store.readBlob(SHA256)).thenReturn(Optional.empty());

        ResponseEntity<byte[]> response = new EvidenceController(store).getBlob(SHA256);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).isNull();
    }

    @Test
    void previewUsesActualImageTypeAndPreservesBytes() throws Exception {
        for (String format : new String[]{"jpeg", "png"}) {
            byte[] bytes = ScreenshotFormatTest.image(format);
            EvidenceStore store = mock(EvidenceStore.class);
            when(store.readBlob(SHA256)).thenReturn(Optional.of(bytes));

            ResponseEntity<byte[]> response = new EvidenceController(store).getBlob(SHA256, true);

            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType("image/" + format));
            assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("inline;");
            assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(response.getHeaders().getContentLength()).isEqualTo(bytes.length);
            assertThat(response.getBody()).containsExactly(bytes);
        }
    }

    @Test
    void previewDoesNotRenderArbitraryOrBrokenContent() throws Exception {
        byte[] bytes = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);
        EvidenceStore store = mock(EvidenceStore.class);
        when(store.readBlob(SHA256)).thenReturn(Optional.of(bytes));

        ResponseEntity<byte[]> response = new EvidenceController(store).getBlob(SHA256, true);

        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("attachment;");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    void endpointPreviewIsOptionalAndNormalImageDownloadRemainsAttachment() throws Exception {
        byte[] bytes = ScreenshotFormatTest.image("jpeg");
        EvidenceStore store = mock(EvidenceStore.class);
        when(store.readBlob(SHA256)).thenReturn(Optional.of(bytes));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new EvidenceController(store)).build();

        mvc.perform(get("/api/evidence/blob/" + SHA256))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, org.hamcrest.Matchers.startsWith("attachment;")))
                .andExpect(content().bytes(bytes));
        mvc.perform(get("/api/evidence/blob/" + SHA256).param("preview", "true"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, org.hamcrest.Matchers.startsWith("inline;")))
                .andExpect(content().bytes(bytes));
    }
}
