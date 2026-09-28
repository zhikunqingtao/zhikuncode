package com.aicodeassistant.verify;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
}
