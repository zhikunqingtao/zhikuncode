package com.aicodeassistant.llm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class FinalProviderPayloadGuardTest {
    private ModelRegistry models;
    private FinalProviderPayloadGuard guard;

    @BeforeEach
    void setUp() {
        models = mock(ModelRegistry.class);
        when(models.isKnownModel("known")).thenReturn(true);
        when(models.getCapabilities("known")).thenReturn(new ModelCapabilities(
                "known", "Known", 1000, 10_000, true, true, true, 5, true, 0, 0));
        when(models.getCapabilities("unknown")).thenReturn(ModelCapabilities.DEFAULT);
        guard = new FinalProviderPayloadGuard(models);
    }

    @Test
    void chargesBase64OneTokenPerCharacter() {
        String encoded = "A".repeat(7_500);
        assertThatThrownBy(() -> guard.validate("anthropic", "known",
                Map.of("source", Map.of("type", "base64", "data", encoded)), 1000))
                .isInstanceOf(LlmApiException.class)
                .hasMessageContaining("CONTEXT_BUDGET_EXCEEDED");
    }

    @Test
    void unknownModelUsesConservativeDefaultInsteadOfBypassingTheGuard() {
        var result = guard.validate("anthropic", "unknown", Map.of("messages", "small"), 100);
        assertThat(result.guarded()).isTrue();
        assertThat(result.precision()).isEqualTo("CONSERVATIVE_DEFAULT");
        assertThat(result.inputBudget()).isEqualTo(6044);
    }

    @Test
    void unknownModelStillRejectsPayloadBeyondTheConservativeWindow() {
        assertThatThrownBy(() -> guard.validate("anthropic", "unknown",
                Map.of("messages", "x".repeat(30_000)), 100))
                .isInstanceOf(LlmApiException.class)
                .hasMessageContaining("CONTEXT_BUDGET_EXCEEDED");
    }

    @Test
    void decodedRasterBudgetIsConsistentForMapAndJsonWhileOpaqueDataStaysConservative() throws Exception {
        var image = new java.awt.image.BufferedImage(512, 512, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var random = new java.util.Random(1);
        for (int y = 0; y < 512; y++) for (int x = 0; x < 512; x++) image.setRGB(x, y, random.nextInt(0x1000000));
        var out = new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(image, "png", out);
        var encoded = java.util.Base64.getEncoder().encodeToString(out.toByteArray());
        var body = Map.of("messages", java.util.List.of(Map.of("role", "user", "content", java.util.List.of(
                Map.of("type", "image_url", "image_url", Map.of("url", "data:image/png;base64," + encoded))))));
        var mapResult = guard.validate("openai", "known", body, 1000);
        var treeResult = guard.validate("openai", "known", new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(body), 1000);
        assertThat(mapResult.estimatedTokens()).isEqualTo(treeResult.estimatedTokens()).isLessThan(3000);
        assertThatThrownBy(() -> guard.validate("openai", "unknown", body, 100))
                .isInstanceOf(LlmApiException.class).hasMessageContaining("CONTEXT_BUDGET_EXCEEDED");
        assertThatThrownBy(() -> guard.validate("openai", "known", Map.of("signature", encoded), 1000))
                .isInstanceOf(LlmApiException.class).hasMessageContaining("CONTEXT_BUDGET_EXCEEDED");
        assertThatThrownBy(() -> guard.validate("openai", "known", Map.of("tools", body), 1000))
                .isInstanceOf(LlmApiException.class).hasMessageContaining("CONTEXT_BUDGET_EXCEEDED");
        assertThatThrownBy(() -> guard.validate("openai", "known", new com.fasterxml.jackson.databind.ObjectMapper()
                .valueToTree(Map.of("tools", body)), 1000))
                .isInstanceOf(LlmApiException.class).hasMessageContaining("CONTEXT_BUDGET_EXCEEDED");
        // Looking like an image inside a tool argument is ordinary text, not an image part.
        assertThatThrownBy(() -> guard.validate("openai", "known", Map.of("text", "data:image/png;base64," + encoded), 1000))
                .isInstanceOf(LlmApiException.class).hasMessageContaining("CONTEXT_BUDGET_EXCEEDED");
    }

    @Test
    void toolArgumentsAreOpaqueButRealNestedToolResultImagesAreVisual() throws Exception {
        var raster = new java.awt.image.BufferedImage(128, 128, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var random = new java.util.Random(12);
        for (int y = 0; y < 128; y++) for (int x = 0; x < 128; x++) raster.setRGB(x, y, random.nextInt());
        var out = new java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(raster, "png", out);
        var data = java.util.Base64.getEncoder().encodeToString(out.toByteArray());
        var image = Map.of("type", "image", "source", Map.of("type", "base64", "media_type", "image/png", "data", data));
        var toolUse = Map.of("type", "tool_use", "id", "call", "name", "save_json", "input", Map.of("document", image));
        var arguments = Map.of("messages", java.util.List.of(Map.of("role", "assistant", "content", java.util.List.of(toolUse))));
        var toolResult = Map.of("type", "tool_result", "tool_use_id", "call", "content", java.util.List.of(image));
        var media = Map.of("messages", java.util.List.of(Map.of("role", "user", "content", java.util.List.of(toolResult))));
        var responseMedia = Map.of("input", java.util.List.of(Map.of("role", "user", "content", java.util.List.of(
                Map.of("type", "input_image", "image_url", "data:image/png;base64," + data)))));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (Object body : java.util.List.of(arguments, mapper.<com.fasterxml.jackson.databind.JsonNode>valueToTree(arguments))) {
            assertThatThrownBy(() -> guard.validate("anthropic", "known", body, 1000))
                    .isInstanceOf(LlmApiException.class).hasMessageContaining("CONTEXT_BUDGET_EXCEEDED");
        }
        for (Object body : java.util.List.of(media, mapper.<com.fasterxml.jackson.databind.JsonNode>valueToTree(media), responseMedia, mapper.<com.fasterxml.jackson.databind.JsonNode>valueToTree(responseMedia))) {
            assertThat(guard.validate("provider", "known", body, 1000).estimatedTokens()).isLessThan(3000);
        }
    }

    @Test
    void encodedImageSafetyLimitStillAppliesBeforeDecoding() {
        var part = Map.of("type", "image_url", "image_url", Map.of("url", "data:image/png;base64," + "A".repeat(14_000_000)));
        assertThatThrownBy(() -> guard.validate("openai", "known", Map.of("messages", java.util.List.of(Map.of("role", "user", "content", java.util.List.of(part)))), 1000))
                .isInstanceOf(LlmApiException.class).hasMessageContaining("10 MiB");
    }

    @Test
    void wireLimitIsIndependentOfImageTokenEstimate() {
        // A repeated part is written repeatedly on the wire even when its data is shared in memory.
        var part = Map.of("type", "image_url", "image_url", Map.of("url", "data:image/png;base64," + "A".repeat(1_000_000)));
        assertThatThrownBy(() -> guard.validate("openai", "known", java.util.Collections.nCopies(68, part), 1000))
                .isInstanceOf(LlmApiException.class).hasMessageContaining("64 MiB");
    }
}
