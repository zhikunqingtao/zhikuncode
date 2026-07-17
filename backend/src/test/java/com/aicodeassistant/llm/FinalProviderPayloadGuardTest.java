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
}
