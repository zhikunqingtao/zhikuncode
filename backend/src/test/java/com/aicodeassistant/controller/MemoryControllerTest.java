package com.aicodeassistant.controller;

import com.aicodeassistant.memdir.MemdirService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real file storage and MVC serialization; no application databases or live services. */
class MemoryControllerTest {
    @TempDir Path temp;
    private final ObjectMapper json = new ObjectMapper();
    private MemdirService service;
    private MockMvc mvc;

    @BeforeEach void setUp() {
        service = spy(new MemdirService(temp));
        mvc = mvc(service);
    }

    private MockMvc mvc(MemdirService memory) {
        return MockMvcBuilders.standaloneSetup(new MemoryController(mock(JdbcTemplate.class), memory))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private ObjectNode body(boolean cards, String content) {
        ObjectNode body = json.createObjectNode();
        if (cards) {
            body.putArray("entries").addObject().put("source", "USER")
                    .put("category", "semantic").put("timestamp", "2026-01-01T00:00:00Z")
                    .put("content", content);
        } else body.put("content", content);
        return body;
    }

    private JsonNode save(boolean cards, ObjectNode body, int expected) throws Exception {
        return json.readTree(mvc.perform(put(cards ? "/api/memory/file/entries" : "/api/memory/file")
                        .contentType("application/json").accept("application/json").content(body.toString()))
                .andExpect(status().is(expected)).andReturn().getResponse().getContentAsString());
    }

    private JsonNode load() throws Exception {
        return json.readTree(mvc.perform(get("/api/memory/file").accept("application/json"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test void missingFileHasEmptyContentAndNullBaseline() throws Exception {
        JsonNode loaded = load();
        assertThat(loaded.path("content").asText()).isEmpty();
        assertThat(loaded.path("entries").size()).isZero();
        assertThat(loaded.path("updatedAt").isNull()).isTrue();
        assertThat(loaded.path("size").asInt()).isZero();
        assertThat(loaded.path("maxSize").asInt()).isEqualTo(50_000);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void saveReturnsNormalizedSnapshotMatchingSubsequentGet(boolean cards) throws Exception {
        JsonNode saved = save(cards, body(cards, "  中文 saved  "), 200);
        JsonNode loaded = load();
        for (String field : List.of("content", "entries", "updatedAt", "size", "maxSize")) {
            assertThat(saved.get(field)).as(field).isEqualTo(loaded.get(field));
        }
        assertThat(saved.path("success").asBoolean()).isTrue();
        assertThat(saved.path("entries").get(0).path("content").asText()).isEqualTo("中文 saved");
        assertThat(saved.path("size").asInt()).isEqualTo(saved.path("content").asText().length());
        assertThat(saved.path("updatedAt").asText()).isEqualTo(Files.getLastModifiedTime(service.getMemoryFile()).toInstant().toString());
    }

    static Stream<Arguments> invalidExistingBaselines() {
        return Stream.of(false, true).flatMap(cards -> Stream.of("missing", "null", "blank", "invalid")
                .map(baseline -> Arguments.of(cards, baseline)));
    }

    @ParameterizedTest @MethodSource("invalidExistingBaselines")
    void missingOrInvalidBaselineCannotOverwriteExistingFile(boolean cards, String baseline) throws Exception {
        service.overwriteFromUserEdit("KEEP");
        String before = service.readMemories();
        ObjectNode request = body(cards, "REPLACE");
        switch (baseline) {
            case "null" -> request.putNull("baseUpdatedAt");
            case "blank" -> request.put("baseUpdatedAt", " ");
            case "invalid" -> request.put("baseUpdatedAt", "not-a-date");
            default -> { }
        }
        assertThat(save(cards, request, 409).path("error").asText()).isEqualTo("conflict");
        assertThat(service.readMemories()).isEqualTo(before);
        assertThat(temp.resolve("MEMORY.md.bak")).doesNotExist();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void existingEmptyFileStillRequiresItsVersion(boolean cards) throws Exception {
        Files.writeString(service.getMemoryFile(), "");
        save(cards, body(cards, "REPLACE"), 409);
        ObjectNode request = body(cards, "REPLACE");
        request.put("baseUpdatedAt", load().path("updatedAt").asText());
        save(cards, request, 200);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void oversizeSavePreservesCurrentFile(boolean cards) throws Exception {
        service.overwriteFromUserEdit("KEEP");
        String before = service.readMemories();
        ObjectNode request = body(cards, "x".repeat(50_001));
        request.put("baseUpdatedAt", load().path("updatedAt").asText());
        assertThat(save(cards, request, 413).path("error").asText()).isEqualTo("too_large");
        assertThat(service.readMemories()).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void appendAfterSaveCannotAdvanceResponseVersionAndAuthorizeAnOverwrite(boolean cards) throws Exception {
        service.overwriteFromUserEdit("ORIGINAL");
        String base = load().path("updatedAt").asText();
        AtomicBoolean once = new AtomicBoolean(true);
        var appendAfterSave = (org.mockito.stubbing.Answer<MemdirService.MemoryFileSnapshot>) call -> {
            var saved = (MemdirService.MemoryFileSnapshot) call.callRealMethod();
            if (once.getAndSet(false)) {
                service.writeMemory("CONCURRENT_X", MemdirService.MemorySource.TOOL);
                Files.setLastModifiedTime(service.getMemoryFile(), FileTime.from(saved.updatedAt().plusSeconds(1)));
            }
            return saved;
        };
        if (cards) doAnswer(appendAfterSave).when(service).overwriteEntriesFromUserEditSnapshot(any(), any());
        else doAnswer(appendAfterSave).when(service).overwriteFromUserEditSnapshot(any(), any());

        ObjectNode request = body(cards, "MY_SAVE").put("baseUpdatedAt", base);
        JsonNode saved = save(cards, request, 200);
        assertThat(saved.path("content").asText()).contains("MY_SAVE").doesNotContain("CONCURRENT_X");
        assertThat(saved.path("updatedAt").asText()).isNotEqualTo(load().path("updatedAt").asText());
        save(cards, body(cards, "NEXT_DRAFT").put("baseUpdatedAt", saved.path("updatedAt").asText()), 409);
        assertThat(service.readMemories()).contains("CONCURRENT_X").doesNotContain("NEXT_DRAFT");
    }

    @Test void appendAfterReadCannotChangeTheReturnedSnapshotVersion() throws Exception {
        service.overwriteFromUserEdit("ORIGINAL");
        doAnswer(call -> {
            var snapshot = (MemdirService.MemoryFileSnapshot) call.callRealMethod();
            service.writeMemory("LATER_X", MemdirService.MemorySource.TOOL);
            Files.setLastModifiedTime(service.getMemoryFile(), FileTime.from(snapshot.updatedAt().plusSeconds(1)));
            return snapshot;
        }).when(service).readMemoryFileSnapshot();
        JsonNode loaded = load();
        assertThat(loaded.path("content").asText()).contains("ORIGINAL").doesNotContain("LATER_X");
        save(false, body(false, "STALE").put("baseUpdatedAt", loaded.path("updatedAt").asText()), 409);
        assertThat(service.readMemories()).contains("LATER_X");
    }

    @ParameterizedTest @ValueSource(strings = {"get", "raw", "cards"})
    void ioFailureUsesStructuredErrorAndDoesNotReplaceTheObstacle(String method) throws Exception {
        Path obstacle = Files.writeString(temp.resolve("not-a-directory"), "KEEP");
        mvc = mvc(new MemdirService(obstacle));
        JsonNode error;
        if (method.equals("get")) {
            error = json.readTree(mvc.perform(get("/api/memory/file").accept("application/json"))
                    .andExpect(status().isInternalServerError()).andReturn().getResponse().getContentAsString());
        } else error = save(method.equals("cards"), body(method.equals("cards"), "NEW"), 500);
        assertThat(error.at("/error/code").asText()).isEqualTo("INTERNAL_ERROR");
        assertThat(error.at("/error/message").asText()).isNotBlank();
        assertThat(Files.readString(obstacle)).isEqualTo("KEEP");
    }
}
