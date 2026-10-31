package com.aicodeassistant.llm.impl;

import com.aicodeassistant.engine.ApiRetryService;
import com.aicodeassistant.engine.ModelTierService;
import com.aicodeassistant.llm.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.net.ssl.SSLException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** All requests stay on MockWebServer; no upstream model or credentials are used. */
class GenerationTransportReplayTest {
    private static final String CLAUDE = "anthropic/claude-opus-5.5";
    private static final String RESPONSES = "openai/gpt-5.6-sol";
    private static final String SUCCESS = "data: {\"choices\":[{\"delta\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";

    @Test
    void acceptedStreamWithoutVisibleOutputDoesNotRetryEitherApi() throws Exception {
        for (String model : List.of(CLAUDE, RESPONSES, "deepseek-flash")) {
            for (String body : List.of("", ": keepalive\n\n", "data: {bad-json}\n\n", "data: {\"unfinished\":")) {
                try (Fixture fixture = new Fixture(model)) {
                    fixture.server.enqueue(new MockResponse().setBody(body));
                    fixture.server.enqueue(new MockResponse().setBody(SUCCESS));

                    LlmApiException failure = assertThrows(LlmApiException.class, fixture::runWithRetries);

                    assertUnknown(failure, 200);
                    assertThat(fixture.attempts.get()).isEqualTo(1);
                    assertThat(fixture.server.getRequestCount()).isEqualTo(1);
                    assertThat(fixture.capture.events).isEmpty();
                    assertThat(fixture.capture.completions).isZero();
                }
            }
        }
    }

    @Test
    void lostResponseAfterRequestWasSentDoesNotRetryBelowOrAboveProvider() throws Exception {
        try (Fixture fixture = new Fixture(CLAUDE); CaptureLogs logs = new CaptureLogs()) {
            fixture.server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
            fixture.server.enqueue(new MockResponse().setBody(SUCCESS));

            LlmApiException failure = assertThrows(LlmApiException.class, fixture::runWithRetries);

            assertUnknown(failure, 0);
            assertThat(fixture.attempts.get()).isEqualTo(1);
            assertThat(fixture.server.getRequestCount()).isEqualTo(1);
            assertThat(String.join("\n", logs.messages)).contains(
                    "phase=request_started", "requestBodyState=completed", "requestFailedMs=-1");
        }
    }

    @Test
    void stalledRequestBodyTimesOutWithoutReplayAndIsNotReportedAsCompleted() throws Exception {
        try (Fixture fixture = new Fixture("deepseek-flash"); CaptureLogs logs = new CaptureLogs()) {
            // A real local socket that never consumes the request, larger than socket buffers.
            // Scale down the timeout so the regression never waits the production 10/60 seconds.
            fixture.server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.STALL_SOCKET_AT_START));
            fixture.prompt += "x".repeat(8 * 1024 * 1024);
            OkHttpClient original = (OkHttpClient) ReflectionTestUtils.getField(fixture.provider, "httpClient");
            ReflectionTestUtils.setField(fixture.provider, "httpClient", original.newBuilder()
                    .writeTimeout(Duration.ofMillis(250)).build());

            LlmApiException failure = assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> assertThrows(LlmApiException.class, fixture::runWithRetries));

            assertUnknown(failure, 0);
            assertThat(failure).hasCauseInstanceOf(SocketTimeoutException.class);
            assertThat(fixture.attempts.get()).isEqualTo(1);
            assertThat(fixture.capture.completions).isZero();
            assertThat(fixture.capture.events).isEmpty();
            List<String> finished = logs.messages.stream()
                    .filter(s -> s.startsWith("LLM transport attempt finished:")).toList();
            assertThat(finished).singleElement().satisfies(s -> assertThat(s).contains(
                    "outcome=response_interrupted", "phase=request_started", "status=0",
                    "requestBodyState=failed", "requestBodyCompletedBytes=-1", "requestBodyEndMs=-1",
                    "requestFailureType=SocketTimeoutException", "writeTimeoutMs=250")
                    .doesNotContain("requestBodyStartMs=-1", "requestFailedMs=-1",
                            "SECRET_TEST_KEY", "SECRET_TEST_PROMPT"));
        }
    }

    @Test
    void tlsFailureAfterHeadersIsUnknownEvenBeforeAnySseEvent() throws Exception {
        try (Fixture fixture = new Fixture(CLAUDE); CaptureLogs logs = new CaptureLogs()) {
            fixture.server.enqueue(new MockResponse().setHeader("x-request-id", "upstream-fixture").setBody(": heartbeat\n\n"));
            OkHttpClient original = (OkHttpClient) ReflectionTestUtils.getField(fixture.provider, "httpClient");
            ReflectionTestUtils.setField(fixture.provider, "httpClient", original.newBuilder().addNetworkInterceptor(chain -> {
                try (Response ignored = chain.proceed(chain.request())) {
                    throw new SSLException("RAW_EXCEPTION_SECRET");
                }
            }).build());

            LlmApiException failure = assertThrows(LlmApiException.class, fixture::runWithRetries);

            assertUnknown(failure, 200);
            assertThat(fixture.server.getRequestCount()).isEqualTo(1);
            assertThat(fixture.capture.events).isEmpty();
            String diagnostic = String.join("\n", logs.messages);
            assertThat(diagnostic).contains("attemptId=attempt-", "upstreamRequestId=upstream-fixture",
                    "phase=response", "status=200", "events=0", "SSLException");
            assertThat(diagnostic).doesNotContain("RAW_EXCEPTION_SECRET", "SECRET_TEST_KEY", "SECRET_TEST_PROMPT");
        }
    }

    @Test
    void okhttpDoesNotReplay503Or408Followups() throws Exception {
        for (int status : List.of(503, 408)) {
            try (Fixture fixture = new Fixture(CLAUDE)) {
                fixture.server.enqueue(new MockResponse().setResponseCode(status).setHeader("Retry-After", "0")
                        .setBody("{\"error\":{\"message\":\"fixture rejection\"}}"));
                fixture.server.enqueue(new MockResponse().setBody(SUCCESS));

                fixture.attempt(); // Inspect transport only; application policy may retry explicit rejections.

                assertThat(fixture.server.getRequestCount()).isEqualTo(1);
                assertThat(fixture.capture.failure).isInstanceOf(LlmApiException.class);
                assertThat(((LlmApiException) fixture.capture.failure).getStatusCode()).isEqualTo(status);
                assertThat(fixture.capture.completions).isZero();
            }
        }
    }

    @Test
    void responsesExplicitRejectionRetainsClassificationButCannotReplayAfterProgress() throws Exception {
        for (String code : List.of("rate_limit_exceeded", "server_error")) {
            for (boolean progress : List.of(false, true)) {
                try (Fixture fixture = new Fixture(RESPONSES)) {
                    String first = progress ? "data: {\"type\":\"response.output_item.added\"}\n\n" : "";
                    fixture.server.enqueue(new MockResponse().setBody(first
                            + "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"" + code + "\",\"message\":\"fixture rejection\"}}}\n\n"));
                    fixture.attempt();
                    LlmApiException failure = (LlmApiException) fixture.capture.failure;
                    assertThat(failure.getErrorType()).isEqualTo(code);
                    assertThat(failure.isRetryable()).isEqualTo(!progress);
                    assertThat(failure.isRetrySuppressed()).isEqualTo(progress);
                    assertThat(fixture.server.getRequestCount()).isEqualTo(1);
                }
            }
        }
    }

    @Test
    void completedResponseWithInvalidFunctionArgumentsKeepsValidationReason() throws Exception {
        try (Fixture fixture = new Fixture(RESPONSES)) {
            fixture.server.enqueue(new MockResponse().setBody("""
                    data: {"type":"response.completed","response":{"output":[{"type":"function_call","call_id":"c1","name":"Read","arguments":"not-json"}]}}

                    """));
            assertKnownResponseFailure(fixture, "INVALID_RESPONSES_FUNCTION_ARGUMENTS");
        }
    }

    @Test
    void incompleteResponseWithFunctionCallKeepsExplicitTokenLimitReason() throws Exception {
        try (Fixture fixture = new Fixture(RESPONSES)) {
            fixture.server.enqueue(new MockResponse().setBody("""
                    data: {"type":"response.incomplete","response":{"output":[{"type":"function_call","call_id":"c1","name":"Read","arguments":"{}"}],"incomplete_details":{"reason":"max_output_tokens"}}}

                    """));
            assertKnownResponseFailure(fixture, "ZENMUX_RESPONSES_INCOMPLETE: max_output_tokens");
        }
    }

    private static void assertKnownResponseFailure(Fixture fixture, String expectedMessage) {
        fixture.server.enqueue(new MockResponse().setBody("data: {\"type\":\"response.completed\",\"response\":{\"output\":[]}}\n\n"));
        LlmApiException failure = assertThrows(LlmApiException.class, fixture::runWithRetries);
        assertThat(failure.isResponseInterrupted()).isFalse();
        assertThat(failure.getErrorType()).isNull(); // Preserve the original local validation error contract.
        assertThat(failure.isRetryable()).isFalse();
        ProviderErrorClassifier.ClassifiedError classified = ProviderErrorClassifier.classify(failure);
        assertThat(classified).isNull();
        // WebSocket's existing unclassified-error fallback must display the precise original reason.
        String displayed = classified == null ? failure.getMessage() : classified.message();
        assertThat(displayed).isEqualTo(expectedMessage).doesNotContain("未知", "response_interrupted");
        assertThat(ProviderErrorClassifier.retryableOrDefault(failure, true)).isFalse();
        assertThat(fixture.attempts.get()).isEqualTo(1);
        assertThat(fixture.server.getRequestCount()).isEqualTo(1);
        assertThat(fixture.capture.completions).isZero();
        assertThat(fixture.capture.events).noneMatch(LlmStreamEvent.ToolUseStart.class::isInstance);
    }

    @Test
    void attemptsHaveDistinctIdsAndCountBodyBytesWithoutLoggingContents() throws Exception {
        try (Fixture fixture = new Fixture(CLAUDE); CaptureLogs logs = new CaptureLogs()) {
            fixture.server.enqueue(new MockResponse().setBody(SUCCESS));
            fixture.server.enqueue(new MockResponse().setBody(SUCCESS));
            fixture.attempt();
            fixture.attempt();

            List<String> finished = logs.messages.stream().filter(s -> s.startsWith("LLM transport attempt finished:")).toList();
            assertThat(finished).hasSize(2);
            assertThat(finished.stream().map(s -> s.split("attemptId=", 2)[1].split(",", 2)[0]).distinct()).hasSize(2);
            assertThat(finished).allSatisfy(s -> assertThat(s).contains("callId=logical-fixture", "outcome=completed",
                    "receivedBytes=" + SUCCESS.length(), "frames=2", "events=2", "protocol=http/1.1",
                    "requestBodyState=completed", "requestFailedMs=-1", "requestFailureType=none",
                    "connectTimeoutMs=2000", "readTimeoutMs=600000", "writeTimeoutMs=2000", "callTimeoutMs=0")
                    .doesNotContain("requestBodyStartMs=-1", "requestBodyEndMs=-1", "requestBodyMs=-1"));
            for (String diagnostic : finished) {
                var sent = fixture.server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(sent).isNotNull();
                assertThat(diagnostic).contains("requestBodyDeclaredBytes=" + sent.getBodySize(),
                        "requestBodyCompletedBytes=" + sent.getBodySize());
            }
            assertThat(finished.get(1)).contains("reused=true");
            assertThat(String.join("\n", logs.messages)).doesNotContain("SECRET_TEST_KEY", "SECRET_TEST_PROMPT");
        }
    }

    @Test
    void onlyConnectExceptionBeforeSendingIsRetryable() {
        StreamTransportAttempt before = new StreamTransportAttempt("before");
        LlmApiException connect = before.transportFailure(new ConnectException("private message"));
        assertThat(connect.isRetryable()).isTrue();
        assertThat(connect.isRetrySuppressed()).isFalse();
        assertThat(before.transportFailure(new SSLException("private message")).isRetrySuppressed()).isTrue();

        StreamTransportAttempt after = new StreamTransportAttempt("after");
        after.requestHeadersStart(null);
        assertUnknown(after.transportFailure(new ConnectException("private message")), 0);
    }

    private static void assertUnknown(LlmApiException failure, int status) {
        assertThat(failure.getErrorType()).isEqualTo("response_interrupted");
        assertThat(failure.isRetryable()).isFalse();
        assertThat(failure.isRetrySuppressed()).isTrue();
        assertThat(failure.getStatusCode()).isEqualTo(status);
    }

    private static final class Fixture implements AutoCloseable {
        final MockWebServer server = new MockWebServer();
        final OpenAiCompatibleProvider provider;
        final ApiRetryService retry;
        final AtomicInteger attempts = new AtomicInteger();
        final String model;
        String prompt = "SECRET_TEST_PROMPT";
        Capture capture;

        Fixture(String model) throws Exception {
            this.model = model;
            server.start();
            provider = new OpenAiCompatibleProvider("zenmux", new ObjectMapper(),
                    new LlmHttpProperties(new LlmHttpProperties.PoolProperties(2, 30), 2, 2, true),
                    new ApiKeyRotationManager("SECRET_TEST_KEY"), "SECRET_TEST_KEY", server.url("/v1").toString(), model, List.of(model));
            ModelAwareRetryPolicy policy = mock(ModelAwareRetryPolicy.class);
            when(policy.calculateDelay(anyString(), anyInt())).thenReturn(Duration.ZERO);
            ApiCircuitBreaker circuit = mock(ApiCircuitBreaker.class);
            when(circuit.allowRequest()).thenReturn(true);
            retry = new ApiRetryService(mock(ModelTierService.class), policy, circuit);
        }
        void attempt() {
            attempts.incrementAndGet();
            capture = new Capture();
            provider.streamChat(model, List.of(Map.of("role", "user", "content", prompt)),
                    "system", List.of(), 32, new ThinkingConfig.Disabled(),
                    new LlmCallContext("logical-fixture", CancellationSignal.none()), capture);
        }
        void runWithRetries() {
            retry.executeWithRetry(() -> {
                attempt();
                if (capture.failure instanceof LlmApiException failure) throw failure;
                if (capture.failure != null) throw new AssertionError(capture.failure);
                return null;
            }, "sdk", model);
        }
        @Override public void close() throws Exception { server.close(); }
    }

    private static final class Capture implements StreamChatCallback {
        final List<LlmStreamEvent> events = new ArrayList<>();
        Throwable failure;
        int completions;
        @Override public void onEvent(LlmStreamEvent event) { events.add(event); }
        @Override public void onComplete() { completions++; }
        @Override public void onError(Throwable error) { failure = error; }
    }

    private static final class CaptureLogs extends AbstractAppender implements AutoCloseable {
        final List<String> messages = new ArrayList<>();
        final org.apache.logging.log4j.core.config.LoggerConfig config;
        CaptureLogs() {
            super("generation-transport-test", null, null, true, null);
            config = ((LoggerContext) LogManager.getContext(false)).getConfiguration()
                    .getLoggerConfig(StreamTransportAttempt.class.getName());
            start();
            config.addAppender(this, null, null);
        }
        @Override public void append(LogEvent event) {
            if (event.getLoggerName().equals(StreamTransportAttempt.class.getName())) messages.add(event.getMessage().getFormattedMessage());
        }
        @Override public void close() { config.removeAppender(getName()); stop(); }
    }
}
