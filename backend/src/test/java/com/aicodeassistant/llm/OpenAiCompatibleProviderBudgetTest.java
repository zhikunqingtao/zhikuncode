package com.aicodeassistant.llm;

import com.aicodeassistant.llm.impl.OpenAiCompatibleProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real wire budgeting and synthetic SSE, confined to localhost and fixture credentials. */
class OpenAiCompatibleProviderBudgetTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MODEL = "anthropic/claude-haiku-5.5";
    private static final String KEY_A = "fixture-key-a";
    private static final String KEY_B = "fixture-key-b";
    private static final String LARGE_SIGNATURE = "s".repeat(20_000);
    private static final List<Map<String, Object>> TOOLS = List.of(Map.of(
            "name", "Read", "description", "Read a synthetic fixture", "input_schema",
            Map.of("type", "object", "properties", Map.of("path", Map.of("type", "string")))));

    @Test
    void validSignatureThatFitsIsReplayedWithoutResetOrExtraHttp() throws Exception {
        try (Fixture fixture = new Fixture(MODEL)) {
            var history = new ArrayList<>(List.of(user("start")));
            String signature = "valid-fixture-signature".repeat(30);
            var first = fixture.exchange(history, "first", signature, "call-1");
            appendToolTurn(history, MODEL, first.capture().state());
            JsonNode original = JSON.valueToTree(history);
            fixture.budgetSnapshots.clear();
            int sentBefore = fixture.server.getRequestCount();

            var next = fixture.exchange(history, "done", null, null);

            assertThat(fixture.budgetSnapshots).hasSize(1);
            assertThat(fixture.server.getRequestCount()).isEqualTo(sentBefore + 1);
            assertThat(next.wire().at("/messages/2/reasoning_details/0/signature").asText()).isEqualTo(signature);
            assertThat(next.wire().at("/messages/2/tool_calls/0/id").asText()).isEqualTo("call-1");
            assertThat(next.wire().at("/messages/3/tool_call_id").asText()).isEqualTo("call-1");
            assertThat(countMarkers(next.wire())).isGreaterThanOrEqualTo(2);
            assertThat(JSON.<JsonNode>valueToTree(history)).isEqualTo(original);
        }
    }

    @Test
    void signatureOnlyOverflowResetsBeforeOneHttpAndEmptyBarrierPreventsRevival() throws Exception {
        try (Fixture fixture = new Fixture(MODEL)) {
            var history = new ArrayList<>(List.of(user("start")));
            var first = fixture.exchange(history, "first", LARGE_SIGNATURE, "call-1");
            appendToolTurn(history, MODEL, first.capture().state());
            JsonNode original = JSON.valueToTree(history);
            fixture.budgetSnapshots.clear();
            int sentBefore = fixture.server.getRequestCount();

            var reset = fixture.exchange(history, "barrier", null, null);

            assertThat(fixture.budgetSnapshots).hasSize(2);
            assertThat(reasoningCount(fixture.budgetSnapshots.getFirst())).isEqualTo(1);
            assertThat(reasoningCount(fixture.budgetSnapshots.getLast())).isZero();
            assertThat(withoutReasoning(fixture.budgetSnapshots.getFirst())).isEqualTo(fixture.budgetSnapshots.getLast());
            assertThat(reset.wire()).isEqualTo(fixture.budgetSnapshots.getLast());
            assertThat(reset.wire().at("/messages/2/tool_calls/0/id").asText()).isEqualTo("call-1");
            assertThat(reset.wire().at("/messages/3/tool_call_id").asText()).isEqualTo("call-1");
            assertThat(reset.wire().at("/messages/3/content/0/text").asText()).isEqualTo("synthetic result");
            assertThat(countMarkers(reset.wire())).isGreaterThanOrEqualTo(2);
            assertThat(fixture.server.getRequestCount()).isEqualTo(sentBefore + 1);
            assertThat(JSON.<JsonNode>valueToTree(history)).isEqualTo(original);
            JsonNode barrier = reset.capture().state();
            assertThat(barrier.path("reasoning_details")).isEmpty();
            assertThat(barrier.at("/replay/parent").asText()).isEmpty();

            history.add(assistant(MODEL, "barrier", barrier, null));
            history.add(user("continue"));
            fixture.budgetSnapshots.clear();
            var afterBarrier = fixture.exchange(history, "done", null, null);
            assertThat(fixture.budgetSnapshots).hasSize(1);
            assertThat(reasoningCount(afterBarrier.wire())).isZero();
            assertThat(afterBarrier.wire().toString()).doesNotContain(LARGE_SIGNATURE);
        }
    }

    @Test
    void publicHistoryOverflowStillFailsAfterResetWithoutSendingHttp() throws Exception {
        try (Fixture fixture = new Fixture(MODEL)) {
            var history = new ArrayList<>(List.of(user("start")));
            var first = fixture.exchange(history, "first", LARGE_SIGNATURE, "call-1");
            appendToolTurn(history, MODEL, first.capture().state());
            history.add(user("public content ".repeat(5000)));
            fixture.budgetSnapshots.clear();
            int sentBefore = fixture.server.getRequestCount();

            LlmApiException failure = assertThrows(LlmApiException.class, () -> fixture.send(history, new Capture()));

            assertThat(failure.getErrorType()).isEqualTo("CONTEXT_BUDGET_EXCEEDED");
            assertThat(fixture.budgetSnapshots).hasSize(2);
            assertThat(reasoningCount(fixture.budgetSnapshots.getFirst())).isEqualTo(1);
            assertThat(reasoningCount(fixture.budgetSnapshots.getLast())).isZero();
            assertThat(withoutReasoning(fixture.budgetSnapshots.getFirst())).isEqualTo(fixture.budgetSnapshots.getLast());
            assertThat(fixture.server.getRequestCount()).isEqualTo(sentBefore);
        }
    }

    @Test
    void changedCredentialOrPrefixDropsOversizedIneligibleStateBeforeBudgetValidation() throws Exception {
        try (Fixture fixture = new Fixture(MODEL)) {
            var history = new ArrayList<>(List.of(user("start")));
            var first = fixture.exchange(history, "first", LARGE_SIGNATURE, "call-1");
            appendToolTurn(history, MODEL, first.capture().state());

            when(fixture.keys.getNextKey()).thenReturn(KEY_B);
            fixture.budgetSnapshots.clear();
            var changedKey = fixture.exchange(history, "done", null, null);
            assertThat(changedKey.authorization()).isEqualTo("Bearer " + KEY_B);
            assertThat(fixture.budgetSnapshots).hasSize(1);
            assertThat(reasoningCount(changedKey.wire())).isZero();

            when(fixture.keys.getNextKey()).thenReturn(KEY_A);
            history.set(0, user("edited starting prompt"));
            fixture.budgetSnapshots.clear();
            var changedPrefix = fixture.exchange(history, "done", null, null);
            assertThat(changedPrefix.authorization()).isEqualTo("Bearer " + KEY_A);
            assertThat(fixture.budgetSnapshots).hasSize(1);
            assertThat(reasoningCount(changedPrefix.wire())).isZero();
            assertThat(fixture.server.getRequestCount()).isEqualTo(3);
        }
    }

    @Test
    void publicOnlyOverflowDoesNotAttemptReasoningReset() throws Exception {
        try (Fixture fixture = new Fixture(MODEL)) {
            LlmApiException failure = assertThrows(LlmApiException.class, () -> fixture.send(
                    List.of(user("public content ".repeat(5000))), new Capture()));

            assertThat(failure.getErrorType()).isEqualTo("CONTEXT_BUDGET_EXCEEDED");
            assertThat(fixture.budgetSnapshots).hasSize(1);
            assertThat(fixture.server.getRequestCount()).isZero();
        }
    }

    @Test
    void modelOutsideResetAllowlistDoesNotSilentlyDiscardValidSignature() throws Exception {
        String unsupported = "anthropic/claude-unknown-fixture";
        try (Fixture fixture = new Fixture(unsupported)) {
            var history = new ArrayList<>(List.of(user("start")));
            var first = fixture.exchange(history, "first", LARGE_SIGNATURE, "call-1");
            appendToolTurn(history, unsupported, first.capture().state());
            fixture.budgetSnapshots.clear();
            int sentBefore = fixture.server.getRequestCount();

            LlmApiException failure = assertThrows(LlmApiException.class, () -> fixture.send(history, new Capture()));

            assertThat(failure.getErrorType()).isEqualTo("CONTEXT_BUDGET_EXCEEDED");
            assertThat(fixture.budgetSnapshots).hasSize(1);
            assertThat(reasoningCount(fixture.budgetSnapshots.getFirst())).isEqualTo(1);
            assertThat(fixture.server.getRequestCount()).isEqualTo(sentBefore);
        }
    }

    private static Map<String, Object> user(String text) {
        return Map.of("role", "user", "content", text);
    }

    private static void appendToolTurn(List<Map<String, Object>> history, String model, JsonNode state) {
        history.add(assistant(model, "first", state, "call-1"));
        history.add(Map.of("role", "user", "content", List.of(Map.of(
                "type", "tool_result", "tool_use_id", "call-1", "content", "synthetic result"))));
    }

    private static Map<String, Object> assistant(String model, String text, JsonNode state, String toolId) {
        var content = new ArrayList<Map<String, Object>>();
        content.add(Map.of("type", "text", "text", text));
        if (toolId != null) content.add(Map.of("type", "tool_use", "id", toolId,
                "name", "Read", "input", Map.of("path", "file")));
        content.add(Map.of("type", "provider_response_state", "provider", "zenmux", "model", model,
                "output", List.of(state)));
        return Map.of("role", "assistant", "content", content);
    }

    private static ObjectNode withoutReasoning(JsonNode request) {
        ObjectNode copy = request.deepCopy();
        for (JsonNode message : copy.path("messages")) {
            if (message instanceof ObjectNode object) object.remove(List.of("reasoning", "reasoning_content", "reasoning_details"));
        }
        return copy;
    }

    private static long reasoningCount(JsonNode request) {
        long count = 0;
        for (JsonNode message : request.path("messages")) if (message.has("reasoning_details")) count++;
        return count;
    }

    private static int countMarkers(JsonNode node) {
        int count = node.isObject() && node.has("cache_control") ? 1 : 0;
        for (JsonNode child : node) count += countMarkers(child);
        return count;
    }

    private record Exchange(Capture capture, JsonNode wire, String authorization) { }

    private static final class Fixture implements AutoCloseable {
        final MockWebServer server = new MockWebServer();
        final ApiKeyRotationManager keys = mock(ApiKeyRotationManager.class);
        final List<JsonNode> budgetSnapshots = new ArrayList<>();
        final OpenAiCompatibleProvider provider;
        final String model;

        Fixture(String model) throws Exception {
            this.model = model;
            server.start();
            when(keys.getKeyCount()).thenReturn(2);
            when(keys.getNextKey()).thenReturn(KEY_A);
            ModelRegistry models = mock(ModelRegistry.class);
            when(models.isKnownModel(model)).thenReturn(true);
            when(models.getCapabilities(model)).thenReturn(new ModelCapabilities(model, "Fixture", 1024, 12000,
                    true, false, true, 4, true, 0, 0, 3.5, true));
            FinalProviderPayloadGuard guard = spy(new FinalProviderPayloadGuard(models));
            doAnswer(invocation -> {
                budgetSnapshots.add(((JsonNode) invocation.getArgument(2)).deepCopy());
                return invocation.callRealMethod();
            }).when(guard).validate(anyString(), anyString(), any(), anyInt());
            provider = new OpenAiCompatibleProvider("zenmux", JSON,
                    new LlmHttpProperties(new LlmHttpProperties.PoolProperties(2, 30), 5, 5, false),
                    keys, "unused-fixture-fallback", server.url("/api/v1").toString(), model, List.of(model), guard);
        }

        void send(List<Map<String, Object>> history, Capture capture) {
            provider.streamChat(model, history, "stable system", TOOLS, 1024,
                    new ThinkingConfig.Disabled(), new LlmCallContext("fixture-" + server.getRequestCount(),
                            CancellationSignal.none()), capture);
        }

        Exchange exchange(List<Map<String, Object>> history, String answer, String signature, String toolId) throws Exception {
            ObjectNode chunk = JSON.createObjectNode();
            ObjectNode choice = chunk.putArray("choices").addObject().put("finish_reason", toolId == null ? "stop" : "tool_calls");
            ObjectNode delta = choice.putObject("delta").put("content", answer);
            if (signature != null) delta.putArray("reasoning_details").addObject()
                    .put("index", 0).put("type", "reasoning.text").put("text", "synthetic upstream payload")
                    .put("signature", signature);
            if (toolId != null) delta.putArray("tool_calls").addObject().put("index", 0).put("id", toolId)
                    .put("type", "function").putObject("function").put("name", "Read").put("arguments", "{\"path\":\"file\"}");
            server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody("data:" + chunk + "\n\ndata:[DONE]\n\n"));
            Capture capture = new Capture();
            send(history, capture);
            assertThat(capture.errors).isEmpty();
            assertThat(capture.completions).isEqualTo(1);
            var request = server.takeRequest(5, TimeUnit.SECONDS);
            assertThat(request).isNotNull();
            JsonNode wire = JSON.readTree(request.getBody().readUtf8());
            assertThat(wire.has("thinking")).isFalse();
            assertThat(wire.has("reasoning")).isFalse();
            assertThat(wire.has("enable_thinking")).isFalse();
            return new Exchange(capture, wire, request.getHeader("Authorization"));
        }

        @Override public void close() throws Exception { server.close(); }
    }

    private static final class Capture implements StreamChatCallback {
        final List<LlmStreamEvent> events = new ArrayList<>();
        final List<Throwable> errors = new ArrayList<>();
        int completions;
        @Override public void onEvent(LlmStreamEvent event) { events.add(event); }
        @Override public void onComplete() { completions++; }
        @Override public void onError(Throwable error) { errors.add(error); }
        JsonNode state() {
            var states = events.stream().filter(LlmStreamEvent.ProviderResponseState.class::isInstance)
                    .map(LlmStreamEvent.ProviderResponseState.class::cast).toList();
            assertThat(states).hasSize(1);
            assertThat(states.getFirst().outputItems()).hasSize(1);
            return states.getFirst().outputItems().getFirst();
        }
    }
}
