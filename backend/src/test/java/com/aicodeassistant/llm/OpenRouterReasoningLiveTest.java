package com.aicodeassistant.llm;

import com.aicodeassistant.llm.impl.OpenAiCompatibleProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in paid smoke check: synthetic prompt and tool result only, never repository content. */
@EnabledIfEnvironmentVariable(named = "OPENROUTER_LIVE_TEST", matches = "true")
class OpenRouterReasoningLiveTest {
    @ParameterizedTest
    @ValueSource(strings = {OpenRouterModels.ASTRA, OpenRouterModels.FABLE})
    @Timeout(180)
    void maxReasoningAndToolContinuation(String model) {
        String key = System.getenv("LLM_PROVIDER_OPENROUTER_API_KEY");
        assertThat(key).isNotBlank();
        var provider = new OpenAiCompatibleProvider("openrouter", new ObjectMapper(),
                new LlmHttpProperties(new LlmHttpProperties.PoolProperties(2, 30), 10, 10, true),
                new ApiKeyRotationManager(key), key, "https://openrouter.ai/api/v1",
                OpenRouterModels.ASTRA, List.of(OpenRouterModels.ASTRA, OpenRouterModels.FABLE));
            long started = System.nanoTime();
            var tools = List.<Map<String, Object>>of(Map.of("type", "function", "function", Map.of(
                    "name", "lookup", "description", "Read a test value.", "parameters",
                    Map.of("type", "object", "properties", Map.of(), "additionalProperties", false))));
            var first = new OpenRouterReasoningTest.Capture();
            provider.streamChat(model, List.of(Map.of("role", "user", "content",
                            "Call lookup exactly once to get the test value, then report that value.")),
                    "This is a synthetic API integration test. Use the tool before answering.", tools,
                    4096, new ThinkingConfig.Disabled(), LlmCallContext.unscoped(), first);
            assertThat(first.error).as(model + " first response").isNull();
            assertThat(first.completed).isTrue();
            var call = first.events.stream().filter(LlmStreamEvent.ToolUseStart.class::isInstance)
                    .map(LlmStreamEvent.ToolUseStart.class::cast).findFirst().orElseThrow();
            var state = first.events.stream().filter(LlmStreamEvent.ProviderResponseState.class::isInstance)
                    .map(LlmStreamEvent.ProviderResponseState.class::cast).findFirst().orElseThrow();
            var next = new OpenRouterReasoningTest.Capture();
            provider.streamChat(model, List.of(
                            Map.of("role", "user", "content", "Call lookup and report the test value."),
                            Map.of("role", "assistant", "content", List.of(
                                    Map.of("type", "tool_use", "id", call.id(), "name", call.name(), "input", Map.of()),
                                    Map.of("type", "provider_response_state", "provider", "openrouter", "model", model,
                                            "output", state.outputItems()))),
                            Map.of("role", "user", "content", List.of(Map.of("type", "tool_result",
                                    "tool_use_id", call.id(), "content", "42")))),
                    "Report the test value briefly.", tools, 4096, new ThinkingConfig.Adaptive(),
                    LlmCallContext.unscoped(), next);
            assertThat(next.error).as(model + " tool continuation").isNull();
            assertThat(next.completed).isTrue();
            String text = next.events.stream().filter(LlmStreamEvent.TextDelta.class::isInstance)
                    .map(LlmStreamEvent.TextDelta.class::cast).map(LlmStreamEvent.TextDelta::text)
                    .collect(java.util.stream.Collectors.joining());
            assertThat(text).contains("42");
            System.out.printf("LIVE PASS model=%s thinkingChars=%d continuation=OK elapsedMs=%d%n",
                    model, first.thinking().length() + next.thinking().length(),
                    (System.nanoTime() - started) / 1_000_000);
    }
}
