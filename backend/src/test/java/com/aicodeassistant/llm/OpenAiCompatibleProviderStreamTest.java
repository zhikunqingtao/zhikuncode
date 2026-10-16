package com.aicodeassistant.llm;

import com.aicodeassistant.llm.impl.OpenAiCompatibleProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiCompatibleProviderStreamTest {
    private static final String MODEL = "anthropic/claude-opus-5.5";
    private static final String FINISH = "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}";
    private static final String TEXT = "{\"choices\":[{\"delta\":{\"content\":\"hello\"},\"finish_reason\":null}]}";

    @Test
    void acceptsLegalSseWithNoSpaceMultilineDataAndKeepaliveComments() throws Exception {
        Capture result = run(": keepalive\n\nevent: message\ndata:{\"choices\":[\ndata:{\"delta\":{\"content\":\"hello\"},\"finish_reason\":\"stop\"}]}\n\ndata:[DONE]\n\n");
        assertThat(result.errors).isEmpty();
        assertThat(result.completions).isEqualTo(1);
        assertThat(result.events.stream().filter(LlmStreamEvent.TextDelta.class::isInstance)
                .map(LlmStreamEvent.TextDelta.class::cast).map(LlmStreamEvent.TextDelta::text)).containsExactly("hello");
    }

    @Test
    void keepsHttp200ErrorFrameClassificationBeforeAnyProgress() throws Exception {
        for (int status : List.of(400, 401, 403, 429, 500, 529)) {
            Capture result = run("data:{\"error\":{\"code\":" + status + ",\"message\":\"fixture\"}}\n\ndata:[DONE]\n\n");
            assertThat(result.completions).isZero();
            assertThat(result.errors).hasSize(1);
            LlmApiException error = (LlmApiException) result.errors.getFirst();
            assertThat(error.getStatusCode()).isEqualTo(status);
            assertThat(error.isRetryable()).isEqualTo(status == 429 || status >= 500);
            assertThat(error.isRetrySuppressed()).isFalse();
        }
    }

    @Test
    void errorTypeAndUnknownErrorsAreNotTurnedIntoSuccess() throws Exception {
        Capture typed = run("data:{\"error\":{\"type\":\"overloaded_error\",\"message\":\"fixture\"}}\n\n");
        assertThat(((LlmApiException) typed.errors.getFirst()).getStatusCode()).isEqualTo(529);
        Capture unknown = run("data:{\"error\":{\"message\":\"fixture\"}}\n\n");
        assertThat(((LlmApiException) unknown.errors.getFirst()).isRetryable()).isFalse();
        assertThat(unknown.completions).isZero();
    }

    @Test
    void suppressesClassifierRetriesAfterTextThinkingOrToolProgress() throws Exception {
        for (String first : List.of(TEXT,
                "{\"choices\":[{\"delta\":{\"reasoning\":\"think\"}}]}",
                "{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c\",\"function\":{\"name\":\"Read\",\"arguments\":\"{}\"}}]}}]}")) {
            Capture result = run("data:" + first + "\n\ndata:{\"error\":{\"code\":529,\"type\":\"overloaded_error\"}}\n\n");
            assertThat(result.errors).hasSize(1);
            LlmApiException error = (LlmApiException) result.errors.getFirst();
            assertThat(error.isRetrySuppressed()).isTrue();
            assertThat(error.getStatusCode()).isEqualTo(529);
            assertThat(result.completions).isZero();
            assertThat(result.events).noneMatch(LlmStreamEvent.ProviderResponseState.class::isInstance);
        }
    }

    @Test
    void incompleteAndMalformedStreamsNeverSaveStateOrComplete() throws Exception {
        for (String body : List.of("data:" + TEXT + "\n\ndata:[DONE]\n\n",
                "data:" + TEXT + "\n\n", "data:" + FINISH,
                "data:{bad json}\n\ndata:" + FINISH + "\n\ndata:[DONE]\n\n",
                "data:" + FINISH + "\n\ndata:{\"broken\":")) {
            Capture result = run(body);
            assertThat(result.errors).hasSize(1);
            assertThat(result.completions).isZero();
            assertThat(result.events).noneMatch(LlmStreamEvent.ProviderResponseState.class::isInstance);
        }
    }

    @Test
    void validFinishAtEventBoundaryMayEndAtEof() throws Exception {
        Capture result = run("data:" + FINISH + "\n\n");
        assertThat(result.errors).isEmpty();
        assertThat(result.completions).isEqualTo(1);
    }

    @Test
    void cancellationAfterBufferedTextDoesNotSaveStateOrComplete() throws Exception {
        Capture result = runResponse(new MockResponse().setBody(
                "data:" + TEXT + "\n\ndata:" + FINISH + "\n\ndata:[DONE]\n\n"), true);
        assertThat(result.errors).hasSize(1);
        assertThat(result.errors.getFirst().getMessage()).isEqualTo("LLM_CALL_CANCELLED");
        assertThat(result.completions).isZero();
        assertThat(result.events).noneMatch(LlmStreamEvent.ProviderResponseState.class::isInstance);
    }

    private Capture run(String sse) throws Exception {
        return runResponse(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse));
    }

    private Capture runResponse(MockResponse response) throws Exception {
        return runResponse(response, false);
    }

    private Capture runResponse(MockResponse response, boolean cancelAfterText) throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(response);
            var provider = new OpenAiCompatibleProvider("zenmux", new ObjectMapper(),
                    new LlmHttpProperties(new LlmHttpProperties.PoolProperties(2, 30), 5, 5, false),
                    new ApiKeyRotationManager("fixture"), "fixture", server.url("/api/v1").toString(), MODEL, List.of(MODEL));
            Capture capture = new Capture();
            java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
            java.util.concurrent.atomic.AtomicReference<Runnable> cancelCall = new java.util.concurrent.atomic.AtomicReference<>(() -> { });
            CancellationSignal signal = new CancellationSignal() {
                public boolean isCancelled() { return cancelled.get(); }
                public Registration register(Runnable callback) {
                    cancelCall.set(callback);
                    return () -> cancelCall.set(() -> { });
                }
            };
            if (cancelAfterText) capture.cancelOnText = () -> { cancelled.set(true); cancelCall.get().run(); };
            provider.streamChat(MODEL, List.of(Map.of("role", "user", "content", "hello")), "system", List.of(),
                    1024, new ThinkingConfig.Disabled(), new LlmCallContext("fixture", signal), capture);
            assertThat(server.getRequestCount()).isEqualTo(1);
            return capture;
        }
    }

    private static final class Capture implements StreamChatCallback {
        final List<LlmStreamEvent> events = new ArrayList<>();
        final List<Throwable> errors = new ArrayList<>();
        int completions;
        Runnable cancelOnText = () -> { };
        @Override public void onEvent(LlmStreamEvent event) {
            events.add(event);
            if (event instanceof LlmStreamEvent.TextDelta) cancelOnText.run();
        }
        @Override public void onComplete() { completions++; }
        @Override public void onError(Throwable error) { errors.add(error); }
    }
}
