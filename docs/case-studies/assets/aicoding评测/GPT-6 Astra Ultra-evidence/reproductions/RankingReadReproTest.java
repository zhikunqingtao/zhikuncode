package com.aicodeassistant.session.merge;

import com.aicodeassistant.authorization.AuthorizationException;
import com.aicodeassistant.model.ContentBlock;
import com.aicodeassistant.tool.ToolInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Observation tests for the frozen review snapshot, not corrected-behavior regressions.
 * A passing test means the named current behavior was observed.
 * All data, SQLite databases, and files are synthetic and scoped to @TempDir.
 * The existing service-test fixture supplies a synchronous, local stub provider.
 */
class RankingReadReproTest {
    @TempDir Path root;
    private final List<SessionMergeServiceTest> scenarios = new ArrayList<>();

    private SessionMergeServiceTest scenario(String name) throws Exception {
        var scenario = new SessionMergeServiceTest();
        scenario.root = Files.createDirectories(root.resolve(name));
        scenarios.add(scenario);
        scenario.setup();
        return scenario;
    }

    private SessionMergeService.Operation publish(SessionMergeServiceTest scenario, String key)
            throws Exception {
        var operation = scenario.await(scenario.service.start(scenario.request(), key).operationId());
        assertThat(operation.status()).isEqualTo("completed");
        return operation;
    }

    @AfterEach
    void closeScenariosAndClearInterrupt() {
        Thread.interrupted();
        for (var scenario : scenarios) {
            if (scenario.service != null) scenario.service.shutdown();
        }
    }

    @Test
    void authorizeTimeoutIsWrappedWithInvalidResourceCode() throws Exception {
        var scenario = scenario("authorization-timeout");
        var operation = publish(scenario, "authorization-timeout");
        var clockCalls = new AtomicInteger();
        var reads = new HandoffReadService(scenario.repo, scenario.f.json,
                () -> clockCalls.getAndIncrement() == 0 ? 0L : 16_000_000_000L);

        assertThatThrownBy(() -> reads.authorize(operation.targetSessionId(),
                ToolInput.from(Map.of("action", "list"))))
                .isInstanceOfSatisfying(AuthorizationException.class, error -> {
                    assertThat(error.code()).isEqualTo("HANDOFF_INVALID_RESOURCE");
                    assertThat(error.getMessage()).isEqualTo("HANDOFF_READ_BUDGET_EXCEEDED");
                });
    }

    @Test
    void readInitialInterruptionIsRethrownRatherThanWrapped() throws Exception {
        var scenario = scenario("read-interruption");
        var operation = publish(scenario, "read-interruption");
        var reads = new HandoffReadService(scenario.repo, scenario.f.json);

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> reads.read(operation.targetSessionId(),
                    ToolInput.from(Map.of("action", "list"))))
                    .isInstanceOf(InterruptedIOException.class)
                    .hasMessage("HANDOFF_READ_INTERRUPTED");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void actualHandoffSearchExcerptCanSplitSurrogatePair() throws Exception {
        var scenario = scenario("search-surrogate-boundary");
        String needle = "SYNTHETIC_UNICODE_BOUNDARY_NEEDLE";
        // Search snippets end at match + query.length() + 300 UTF-16 code units.
        // The emoji's high surrogate is at +299 and its low surrogate at +300.
        // This is a valid original string; only the returned excerpt is tested.
        String original = needle + "x".repeat(299) + "\uD83D\uDE00" + "VALID_SOURCE_TAIL";
        assertThat(hasUnpairedSurrogate(original)).isFalse();
        scenario.f.message("A", original);
        var operation = publish(scenario, "search-surrogate-boundary");
        var reads = new HandoffReadService(scenario.repo, scenario.f.json);

        String response = reads.read(operation.targetSessionId(),
                ToolInput.from(Map.of("action", "search", "query", needle, "limit", 20)));
        var page = scenario.f.json.readTree(response);
        List<String> excerpts = new ArrayList<>();
        for (var entry : page.path("entries")) {
            if (entry.path("text").asText().contains(needle)) excerpts.add(entry.path("text").asText());
        }
        assertThat(excerpts).isNotEmpty();
        assertThat(excerpts.stream().anyMatch(RankingReadReproTest::hasUnpairedSurrogate)).isTrue();
        assertThat(excerpts.stream().anyMatch(text ->
                !text.isEmpty() && Character.isHighSurrogate(text.charAt(text.length() - 1)))).isTrue();
    }

    @Test
    void embeddedImageCopyQuotaLosesClassificationWhileManagedFileRetainsIt() throws Exception {
        var imageScenario = scenario("embedded-image-quota");
        ReflectionTestUtils.setField(imageScenario.f.packages, "maxCopyBytes", 1L);
        var bytes = new ByteArrayOutputStream();
        assertThat(javax.imageio.ImageIO.write(
                new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes)).isTrue();
        imageScenario.f.sessions.addMessageWithId("synthetic-image", "A", "user",
                List.of(new ContentBlock.TextBlock("synthetic image only")), null, 0, 0, Map.of());
        imageScenario.f.jdbc.update("UPDATE messages SET content_json=? WHERE id=?",
                imageScenario.f.json.writeValueAsString(List.of(Map.of(
                        "type", "image", "base64Data", Base64.getEncoder().encodeToString(bytes.toByteArray()),
                        "mediaType", "image/png"))), "synthetic-image");

        // Capture the original exception separately to distinguish quota classification
        // from a generic failure in the asynchronous service fixture.
        Throwable raw = catchThrowable(() -> imageScenario.f.packages.seal(
                imageScenario.f.packages.snapshotPath(UUID.randomUUID().toString()),
                List.of("A", "B"), 1, () -> {}));
        assertThat(raw).isInstanceOf(IOException.class)
                .hasMessageContaining("复制容量上限");
        assertThat(raw.getClass().getSimpleName()).isEqualTo("CopyLimitException");

        var imageOperation = imageScenario.await(imageScenario.service.start(
                imageScenario.request(), "embedded-image-quota").operationId());
        assertThat(imageOperation.status()).isEqualTo("paused");
        assertThat(imageOperation.errorCode()).isEqualTo("MERGE_PREPARATION_FAILED");
        assertThat(imageOperation.targetAvailable()).isFalse();

        var fileScenario = scenario("managed-file-quota");
        ReflectionTestUtils.setField(fileScenario.f.packages, "maxCopyBytes", 1L);
        Path own = Files.createDirectories(fileScenario.root.resolve("scratch/A"));
        Files.writeString(own.resolve("synthetic-quota.txt"), "synthetic bytes exceed one byte");
        var fileOperation = fileScenario.await(fileScenario.service.start(
                fileScenario.request(), "managed-file-quota").operationId());
        assertThat(fileOperation.status()).isEqualTo("paused");
        assertThat(fileOperation.errorCode()).isEqualTo("MERGE_COPY_INCOMPLETE");
        assertThat(fileOperation.targetAvailable()).isFalse();
    }

    private static boolean hasUnpairedSurrogate(String text) {
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (Character.isHighSurrogate(value)) {
                if (index + 1 == text.length() || !Character.isLowSurrogate(text.charAt(index + 1))) return true;
                index++;
            } else if (Character.isLowSurrogate(value)) {
                return true;
            }
        }
        return false;
    }
}
