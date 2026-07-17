package com.aicodeassistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import com.aicodeassistant.security.SensitiveDataFilter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ActivityRepositoryTest {
    @Test
    void oversizedJsonIsPreservedAsPreviewHashAndSize() throws Exception {
        ActivityRepository repository = new ActivityRepository(
                mock(JdbcTemplate.class), new ObjectMapper(), new SensitiveDataFilter());
        String bounded = repository.boundJson("{\"value\":\"" + "x".repeat(20_000) + "\"}");
        var json = new ObjectMapper().readTree(bounded);
        assertThat(json.path("truncated").asBoolean()).isTrue();
        assertThat(json.path("originalBytes").asInt()).isGreaterThan(10_240);
        assertThat(json.path("payloadSha256").asText()).hasSize(64);
        assertThat(json.path("payloadPreview").asText()).isNotEmpty();
        assertThat(bounded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).isLessThanOrEqualTo(10_240);
    }


    @Test
    void repositorySanitizesJsonBeforePersisting() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ActivityRepository repository = new ActivityRepository(
                jdbc, new ObjectMapper(), new SensitiveDataFilter());
        repository.upsert("a", "s", "tool", "summary", "done", 1L,
                null, 0, null, "{\"token\":\"sk-abcdefghijklmnopqrstuvwxyz123456\"}",
                null, null);
        var captor = org.mockito.ArgumentCaptor.forClass(Object[].class);
        org.mockito.Mockito.verify(jdbc).update(org.mockito.ArgumentMatchers.anyString(), captor.capture());
        Object[] args = captor.getValue();
        assertThat(String.valueOf(args[9])).contains("REDACTED").doesNotContain("abcdefghijklmnopqrstuvwxyz123456");
    }
}
