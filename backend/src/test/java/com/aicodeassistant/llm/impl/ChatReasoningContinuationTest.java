package com.aicodeassistant.llm.impl;

import com.aicodeassistant.llm.ApiKeyRotationManager;
import com.aicodeassistant.llm.LlmApiException;
import com.aicodeassistant.llm.LlmHttpProperties;
import com.aicodeassistant.llm.LlmStreamEvent;
import com.aicodeassistant.llm.StreamChatCallback;
import com.aicodeassistant.llm.impl.OpenAiCompatibleProvider.ChatReasoningContinuation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Regression coverage for changed request history inside a signed tool exchange. */
class ChatReasoningContinuationTest {
    private static final String MODEL = "anthropic/claude-opus-5.5";
    private static final String URL = "https://example.invalid/api/v1/chat/completions";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void removingTemporaryImageAfterTwoToolsResetsEverySignatureAndPreservesToolExchange() throws Exception {
        List<Map<String, Object>> history = new ArrayList<>(List.of(user("start")));
        JsonNode first = response(prepare(request(history), history), "", true, "first-call");
        history.add(assistant("", first, "first-call"));
        history.add(result("first-call", "first result"));
        history.add(Map.of("role", "user", "content", List.of(
                Map.of("type", "text", "text", "temporary reference"),
                Map.of("type", "image", "source", Map.of("type", "base64", "media_type", "image/png",
                        "data", "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Wl6IcsAAAAASUVORK5CYII=")))));
        ObjectNode secondRequest = request(history);
        ChatReasoningContinuation secondStream = prepare(secondRequest, history);
        assertThat(reasoningCount(secondRequest)).isEqualTo(1);
        JsonNode second = response(secondStream, "", true, "second-call");
        history.add(assistant("", second, "second-call"));
        history.add(result("second-call", "second result"));

        // The acknowledged image is no longer injected on the next tool iteration.
        history.set(3, user("temporary reference"));
        JsonNode originalHistory = mapper.valueToTree(history);
        ObjectNode resetRequest = request(history);
        JsonNode publicRequest = resetRequest.deepCopy();
        ChatReasoningContinuation reset = prepare(resetRequest, history);
        assertThat(reasoningCount(resetRequest)).isZero();
        assertThat(resetRequest).isEqualTo(publicRequest);
        assertThat(resetRequest.toString()).contains("first-call", "second-call", "first result", "second result");
        assertThat(mapper.<JsonNode>valueToTree(history)).isEqualTo(originalHistory);

        JsonNode barrier = response(reset, "done", false, null);
        history.add(assistant("done", barrier, null));
        history.add(user("next"));
        ObjectNode afterReset = request(history);
        prepare(afterReset, history);
        assertThat(reasoningCount(afterReset)).isZero();
    }

    @Test
    void repeatedResetsKeepNewContinuationWorkingWithoutRevivingOldSignatures() throws Exception {
        List<Map<String, Object>> history = new ArrayList<>(List.of(user("original")));
        JsonNode original = response(prepare(request(history), history), "original answer", true, null);
        history.add(assistant("original answer", original, null));
        history.add(user("next"));
        for (String prefix : List.of("edited", "original")) {
            // Restoring an older public prefix must not restore its abandoned signatures.
            history.set(0, user(prefix));
            ObjectNode resetRequest = request(history);
            ChatReasoningContinuation reset = prepare(resetRequest, history);
            assertThat(reasoningCount(resetRequest)).isZero();
            JsonNode barrier = response(reset, "reset answer", false, null);
            history.add(assistant("reset answer", barrier, null));
            history.add(user("continue"));

            ObjectNode newRootRequest = request(history);
            JsonNode newRoot = response(prepare(newRootRequest, history), "new answer", true, null);
            assertThat(reasoningCount(newRootRequest)).isZero();
            history.add(assistant("new answer", newRoot, null));
            history.add(user("continue again"));
            ObjectNode childRequest = request(history);
            JsonNode child = response(prepare(childRequest, history), "new child", true, null);
            assertThat(reasoningCount(childRequest)).isEqualTo(1);
            history.add(assistant("new child", child, null));
            history.add(user("one more"));
            ObjectNode replay = request(history);
            prepare(replay, history);
            assertThat(reasoningCount(replay)).isEqualTo(2);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "not-json", "[]", "42"})
    void invalidToolArgumentsKeepTheirOwnErrorInsteadOfAnInternalStateError(String arguments) throws Exception {
        List<Map<String, Object>> history = new ArrayList<>(List.of(user("start")));
        ChatReasoningContinuation stream = prepare(request(history), history);
        List<JsonNode> states = new ArrayList<>();
        StreamChatCallback capture = capturing(states);

        stream.accept(chunk(true, "call-1", arguments, "tool_calls"), capture);

        assertThatThrownBy(() -> stream.complete(capture))
                .isInstanceOfSatisfying(LlmApiException.class, error -> {
                    assertThat(error.getErrorType()).isEqualTo("INVALID_TOOL_INPUT_JSON");
                    assertThat(error.isRetryable()).isFalse();
                });
        assertThat(states).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"length,{}", "max_tokens,{}", "length,{\"a\":1", "max_tokens,[]"})
    void truncatedToolCallKeepsTheStopReasonEvenWithOtherwiseValidArguments(String finishReason, String arguments)
            throws Exception {
        List<Map<String, Object>> history = new ArrayList<>(List.of(user("start")));
        ChatReasoningContinuation stream = prepare(request(history), history);
        List<JsonNode> states = new ArrayList<>();
        StreamChatCallback capture = capturing(states);

        stream.accept(chunk(true, "call-1", arguments, finishReason), capture);

        assertThatThrownBy(() -> stream.complete(capture))
                .isInstanceOfSatisfying(LlmApiException.class, error -> {
                    assertThat(error.getErrorType()).isEqualTo("TRUNCATED_TOOL_CALLS");
                    assertThat(error.isRetryable()).isFalse();
                });
        assertThat(states).isEmpty();
    }

    @Test
    void validSignedToolArgumentsStillEmitOneState() throws Exception {
        List<Map<String, Object>> history = new ArrayList<>(List.of(user("start")));
        ChatReasoningContinuation stream = prepare(request(history), history);
        List<JsonNode> states = new ArrayList<>();
        StreamChatCallback capture = capturing(states);

        stream.accept(chunk(true, "call-1", "{}", "tool_calls"), capture);
        stream.complete(capture);

        assertThat(states).hasSize(1);
    }

    private StreamChatCallback capturing(List<JsonNode> states) {
        return new StreamChatCallback() {
            @Override public void onEvent(LlmStreamEvent event) {
                if (event instanceof LlmStreamEvent.ProviderResponseState state) states.addAll(state.outputItems());
            }
            @Override public void onComplete() { }
            @Override public void onError(Throwable error) { throw new AssertionError(error); }
        };
    }

    private JsonNode chunk(boolean signed, String toolId, String arguments, String finishReason) {
        ObjectNode chunk = mapper.createObjectNode();
        ObjectNode delta = chunk.putArray("choices").addObject()
                .put("finish_reason", finishReason).putObject("delta").put("content", "");
        if (signed) delta.putArray("reasoning_details").addObject().put("type", "reasoning.text")
                .put("text", "synthetic upstream payload").put("signature", "fixture-signature");
        if (toolId != null) delta.putArray("tool_calls").addObject().put("index", 0).put("id", toolId)
                .putObject("function").put("name", "Read").put("arguments", arguments);
        return chunk;
    }

    private ObjectNode request(List<Map<String, Object>> history) throws Exception {
        OpenAiCompatibleProvider provider = new OpenAiCompatibleProvider("zenmux", mapper,
                new LlmHttpProperties(new LlmHttpProperties.PoolProperties(2, 30), 10, 10, true),
                new ApiKeyRotationManager("fixture-key"), "fixture-key", "https://example.invalid/api/v1", MODEL, List.of(MODEL));
        Method method = OpenAiCompatibleProvider.class.getDeclaredMethod("buildBaseRequest", String.class, List.class,
                String.class, List.class, int.class);
        method.setAccessible(true);
        return (ObjectNode) method.invoke(provider, MODEL, history, "system", List.of(Map.of("type", "function",
                "function", Map.of("name", "Read", "parameters", Map.of("type", "object")))), 4096);
    }

    private ChatReasoningContinuation prepare(ObjectNode request, List<Map<String, Object>> history) {
        return ChatReasoningContinuation.prepare(mapper, request, history, MODEL, URL, "fixture-key");
    }

    private JsonNode response(ChatReasoningContinuation stream, String text, boolean signed, String toolId) {
        ObjectNode chunk = mapper.createObjectNode();
        ObjectNode delta = chunk.putArray("choices").addObject()
                .put("finish_reason", toolId == null ? "stop" : "tool_calls").putObject("delta").put("content", text);
        if (signed) delta.putArray("reasoning_details").addObject().put("type", "reasoning.text")
                .put("text", "synthetic upstream payload").put("signature", "fixture-signature");
        if (toolId != null) delta.putArray("tool_calls").addObject().put("index", 0).put("id", toolId)
                .putObject("function").put("name", "Read").put("arguments", "{}");
        List<JsonNode> states = new ArrayList<>();
        StreamChatCallback capture = new StreamChatCallback() {
            @Override public void onEvent(LlmStreamEvent event) {
                if (event instanceof LlmStreamEvent.ProviderResponseState state) states.addAll(state.outputItems());
            }
            @Override public void onComplete() { }
            @Override public void onError(Throwable error) { throw new AssertionError(error); }
        };
        stream.accept(chunk, capture);
        stream.complete(capture);
        assertThat(states).hasSize(1);
        return states.getFirst();
    }

    private long reasoningCount(JsonNode request) {
        long count = 0;
        for (JsonNode message : request.path("messages")) if (message.has("reasoning_details")) count++;
        return count;
    }

    private Map<String, Object> user(String text) { return Map.of("role", "user", "content", text); }

    private Map<String, Object> result(String id, String text) {
        return Map.of("role", "user", "content", List.of(Map.of(
                "type", "tool_result", "tool_use_id", id, "content", text)));
    }

    private Map<String, Object> assistant(String text, JsonNode state, String toolId) {
        List<Map<String, Object>> content = new ArrayList<>();
        if (!text.isEmpty()) content.add(Map.of("type", "text", "text", text));
        if (toolId != null) content.add(Map.of("type", "tool_use", "id", toolId, "name", "Read", "input", Map.of()));
        content.add(Map.of("type", "provider_response_state", "provider", "zenmux", "model", MODEL, "output", List.of(state)));
        return Map.of("role", "assistant", "content", content);
    }
}
