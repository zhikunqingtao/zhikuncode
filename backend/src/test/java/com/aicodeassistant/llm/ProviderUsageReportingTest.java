package com.aicodeassistant.llm;

import com.aicodeassistant.llm.impl.AnthropicProvider;
import com.aicodeassistant.llm.impl.OpenAiCompatibleProvider;
import com.aicodeassistant.model.Usage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises the real stream parsers using in-memory payloads; no HTTP or model calls. */
class ProviderUsageReportingTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final LlmHttpProperties http = new LlmHttpProperties(
            new LlmHttpProperties.PoolProperties(2, 30), 10, 10, false);
    private final OpenAiCompatibleProvider openAi = new OpenAiCompatibleProvider(
            "test", mapper, http, new ApiKeyRotationManager("fixture"), "fixture",
            "http://127.0.0.1:1/v1", "fixture", List.of("fixture"));

    @ParameterizedTest
    @ValueSource(strings = {"", ",\"usage\":null", ",\"usage\":{}"})
    void chatCompletionWithoutUsageDoesNotReportZero(String usage) throws Exception {
        Capture capture = parseChat("{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]" + usage + "}");
        assertThat(capture.deltas()).singleElement().satisfies(delta -> {
            assertThat(delta.stopReason()).isEqualTo("end_turn");
            assertThat(delta.usage()).isNull();
        });
    }

    @Test
    void nullUsageOnlyTailDoesNotReportZero() throws Exception {
        Capture capture = parseChat("{\"choices\":[],\"usage\":null}");
        assertThat(capture.deltas()).allSatisfy(delta -> assertThat(delta.usage()).isNull());
    }

    @Test
    void chatCompletionPreservesExplicitZeroAndNonzeroUsage() throws Exception {
        for (int input : List.of(0, 10)) {
            Capture capture = parseChat("{\"choices\":[],\"usage\":{\"prompt_tokens\":"
                    + input + ",\"completion_tokens\":0}}");
            assertThat(capture.deltas()).singleElement().satisfies(delta ->
                    assertThat(delta.usage()).isEqualTo(new Usage(input, 0, 0, 0)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ",\"usage\":null", ",\"usage\":{}"})
    void responsesWithoutUsageDoesNotReportZero(String usage) throws Exception {
        Capture capture = parseResponses("{\"output\":[]" + usage + "}");
        assertThat(capture.deltas()).singleElement().satisfies(delta ->
                assertThat(delta.usage()).isNull());
    }

    @Test
    void responsesPreservesExplicitZeroAndNonzeroUsage() throws Exception {
        for (int input : List.of(0, 10)) {
            Capture capture = parseResponses("{\"output\":[],\"usage\":{\"input_tokens\":"
                    + input + ",\"output_tokens\":0}}");
            assertThat(capture.deltas()).singleElement().satisfies(delta ->
                    assertThat(delta.usage()).isEqualTo(new Usage(input, 0, 0, 0)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ",\"usage\":null", ",\"usage\":{}"})
    void anthropicWithoutUsageDoesNotReportZero(String usage) throws Exception {
        Capture capture = parseAnthropic(
                "{\"type\":\"message_start\",\"message\":{\"id\":\"m\"" + usage + "}}",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}" + usage + "}");
        assertThat(capture.deltas()).isNotEmpty()
                .allSatisfy(delta -> assertThat(delta.usage()).isNull());
    }

    @Test
    void anthropicKeepsCumulativeUsageAcrossDeltasWithoutUsage() throws Exception {
        Capture capture = parseAnthropic(
                "{\"type\":\"message_start\",\"message\":{\"id\":\"m\",\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}}",
                "{\"type\":\"message_delta\",\"delta\":{},\"usage\":{\"output_tokens\":2}}",
                "{\"type\":\"message_delta\",\"delta\":{},\"usage\":{\"output_tokens\":5}}",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":null}");
        assertThat(capture.deltas()).extracting(LlmStreamEvent.MessageDelta::usage)
                .containsExactly(new Usage(10, 0, 0, 0), new Usage(10, 2, 0, 0),
                        new Usage(10, 5, 0, 0), new Usage(10, 5, 0, 0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"",
            "event: error\ndata: {\"error\":{\"type\":\"api_error\",\"message\":\"stream failed\"}}\n\n"})
    void anthropicReportsInitialUsageBeforeErrorOrUnexpectedEof(String failureTail) {
        Capture capture = new Capture();
        String body = "data: {\"type\":\"message_start\",\"message\":{\"id\":\"m\","
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}}\n\n" + failureTail;

        assertThatThrownBy(() -> parseAnthropicBody(body, capture))
                .hasCauseInstanceOf(LlmApiException.class);
        assertThat(capture.deltas()).singleElement().satisfies(delta -> {
            assertThat(delta.usage()).isEqualTo(new Usage(10, 0, 0, 0));
            assertThat(delta.stopReason()).isNull();
        });
    }

    @Test
    void anthropicExplicitZeroIsAReportedSnapshot() throws Exception {
        Capture capture = parseAnthropic(
                "{\"type\":\"message_start\",\"message\":{\"id\":\"m\",\"usage\":{\"input_tokens\":0,\"output_tokens\":0}}}",
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":0}}");
        assertThat(capture.deltas().getLast().usage()).isEqualTo(Usage.zero());
    }

    private Capture parseChat(String chunk) throws Exception {
        Capture capture = new Capture();
        Method method = OpenAiCompatibleProvider.class.getDeclaredMethod("processChunk",
                String.class, Map.class, StreamChatCallback.class, AtomicBoolean.class);
        method.setAccessible(true);
        method.invoke(openAi, chunk, new LinkedHashMap<>(), capture, new AtomicBoolean());
        assertThat(capture.error).isNull();
        return capture;
    }

    private Capture parseResponses(String response) throws Exception {
        Capture capture = new Capture();
        Class<?> stateClass = Class.forName(OpenAiCompatibleProvider.class.getName() + "$ResponsesStreamState");
        var constructor = stateClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        Method method = OpenAiCompatibleProvider.class.getDeclaredMethod("completeResponses",
                JsonNode.class, boolean.class, stateClass, StreamChatCallback.class);
        method.setAccessible(true);
        method.invoke(openAi, mapper.readTree(response), false, constructor.newInstance(), capture);
        assertThat(capture.error).isNull();
        return capture;
    }

    private Capture parseAnthropic(String... events) throws Exception {
        StringBuilder body = new StringBuilder();
        for (String event : events) body.append("data: ").append(event).append("\n\n");
        body.append("data: {\"type\":\"message_stop\"}\n\n");
        Capture capture = new Capture();
        parseAnthropicBody(body.toString(), capture);
        assertThat(capture.error).isNull();
        return capture;
    }

    private void parseAnthropicBody(String body, Capture capture) throws Exception {
        AnthropicProvider provider = new AnthropicProvider(mapper, http, "fixture",
                "http://127.0.0.1:1", "fixture", List.of("fixture"), null);
        Method method = AnthropicProvider.class.getDeclaredMethod("parseAnthropicSSE",
                Response.class, StreamChatCallback.class);
        method.setAccessible(true);
        try (Response response = new Response.Builder()
                .request(new Request.Builder().url("http://127.0.0.1:1").build())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ResponseBody.create(body, MediaType.get("text/event-stream"))).build()) {
            method.invoke(provider, response, capture);
        }
    }

    private static final class Capture implements StreamChatCallback {
        private final List<LlmStreamEvent> events = new ArrayList<>();
        private Throwable error;
        @Override public void onEvent(LlmStreamEvent event) { events.add(event); }
        @Override public void onComplete() { }
        @Override public void onError(Throwable failure) { error = failure; }
        private List<LlmStreamEvent.MessageDelta> deltas() {
            return events.stream().filter(LlmStreamEvent.MessageDelta.class::isInstance)
                    .map(LlmStreamEvent.MessageDelta.class::cast).toList();
        }
    }
}
