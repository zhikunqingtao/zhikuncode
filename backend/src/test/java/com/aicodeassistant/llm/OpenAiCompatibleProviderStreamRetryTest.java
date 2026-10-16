package com.aicodeassistant.llm;

import com.aicodeassistant.engine.ApiRetryService;
import com.aicodeassistant.engine.ModelTierService;
import com.aicodeassistant.llm.impl.OpenAiCompatibleProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises actual retry classification and priority key failover against local SSE fixtures. */
class OpenAiCompatibleProviderStreamRetryTest {
    private static final String MODEL = "anthropic/claude-opus-5.5";
    private static final String PRIMARY = "fixture-priority-key";
    private static final String BACKUP = "fixture-backup-key";
    private static final String SUCCESS = "data:{\"choices\":[{\"delta\":{\"content\":\"done\"},\"finish_reason\":\"stop\"}]}\n\ndata:[DONE]\n\n";
    private static final String RATE_LIMIT = "data:{\"error\":{\"type\":\"rate_limit_error\",\"message\":\"fixture limit\"}}\n\n";

    @Test
    void rateLimitFrameCoolsActualPriorityKeyAndRealRetryUsesBackup() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.enqueue(RATE_LIMIT);
            fixture.enqueue(SUCCESS);

            fixture.retries.executeWithRetry(fixture::attempt, "sdk", MODEL);

            assertThat(fixture.attempts.get()).isEqualTo(2);
            assertThat(fixture.server.getRequestCount()).isEqualTo(2);
            assertThat(fixture.authorization()).isEqualTo("Bearer " + PRIMARY);
            assertThat(fixture.authorization()).isEqualTo("Bearer " + BACKUP);
            assertThat(fixture.captures.getLast().completions).isEqualTo(1);
            verify(fixture.policy, times(1)).calculateDelay(MODEL, 0);
            verify(fixture.circuit, never()).recordFailure();
        }
    }

    @Test
    void providerOnlyProgressSuppressesRealClassifierRetryAndSecondRequest() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String privateProgress = "data:{\"choices\":[{\"delta\":{\"reasoning_details\":[{\"index\":0,\"type\":\"reasoning.encrypted\",\"data\":\"fixture-cipher\"}]}}]}\n\n";
            fixture.enqueue(privateProgress + RATE_LIMIT);
            fixture.enqueue(SUCCESS); // A regression that retries terminates promptly and fails assertions.

            LlmApiException failure = assertThrows(LlmApiException.class,
                    () -> fixture.retries.executeWithRetry(fixture::attempt, "sdk", MODEL));

            assertThat(failure.getStatusCode()).isEqualTo(429);
            assertThat(failure.isRetrySuppressed()).isTrue();
            assertThat(fixture.attempts.get()).isEqualTo(1);
            assertThat(fixture.server.getRequestCount()).isEqualTo(1);
            assertThat(fixture.authorization()).isEqualTo("Bearer " + PRIMARY);
            Capture capture = fixture.captures.getFirst();
            assertThat(capture.events).anyMatch(LlmStreamEvent.ProviderProgress.class::isInstance);
            assertThat(capture.events).noneMatch(event -> event instanceof LlmStreamEvent.TextDelta
                    || event instanceof LlmStreamEvent.ThinkingDelta || event instanceof LlmStreamEvent.ProviderResponseState);
            assertThat(capture.completions).isZero();
            verify(fixture.policy, never()).calculateDelay(anyString(), anyInt());
            verify(fixture.circuit, times(1)).recordFailure();
        }
    }

    @Test
    void subscriptionErrorFramesKeepNoRetryPolicyButCoolKeyForNextCall() throws Exception {
        for (int status : List.of(402, 404)) {
            try (Fixture fixture = new Fixture()) {
                String type = status == 402 ? "quote_exceeded" : "model_not_available";
                fixture.enqueue("data:{\"error\":{\"code\":" + status + ",\"type\":\"" + type + "\",\"message\":\"fixture subscription\"}}\n\n");

                LlmApiException failure = assertThrows(LlmApiException.class,
                        () -> fixture.retries.executeWithRetry(fixture::attempt, "sdk", MODEL));

                assertThat(failure.getStatusCode()).isEqualTo(status);
                assertThat(fixture.attempts.get()).isEqualTo(1);
                assertThat(fixture.server.getRequestCount()).isEqualTo(1);
                assertThat(fixture.authorization()).isEqualTo("Bearer " + PRIMARY);
                verify(fixture.policy, never()).calculateDelay(anyString(), anyInt());

                fixture.enqueue(SUCCESS);
                fixture.retries.executeWithRetry(fixture::attempt, "sdk", MODEL);
                assertThat(fixture.attempts.get()).isEqualTo(2);
                assertThat(fixture.authorization()).isEqualTo("Bearer " + BACKUP);
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final MockWebServer server = new MockWebServer();
        final ModelAwareRetryPolicy policy = mock(ModelAwareRetryPolicy.class);
        final ApiCircuitBreaker circuit = mock(ApiCircuitBreaker.class);
        final ApiRetryService retries;
        final OpenAiCompatibleProvider provider;
        final AtomicInteger attempts = new AtomicInteger();
        final List<Capture> captures = new ArrayList<>();

        Fixture() throws Exception {
            server.start();
            when(policy.calculateDelay(anyString(), anyInt())).thenReturn(Duration.ZERO);
            when(circuit.allowRequest()).thenReturn(true);
            retries = new ApiRetryService(mock(ModelTierService.class), policy, circuit);
            var keys = new ApiKeyRotationManager(List.of(PRIMARY, BACKUP),
                    ApiKeyRotationManager.KeySelectionStrategy.PRIORITY_FAILOVER);
            provider = new OpenAiCompatibleProvider("zenmux", new ObjectMapper(),
                    new LlmHttpProperties(new LlmHttpProperties.PoolProperties(2, 30), 5, 5, false),
                    keys, PRIMARY, server.url("/api/v1").toString(), MODEL, List.of(MODEL));
        }

        void enqueue(String sse) {
            server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse));
        }

        Void attempt() {
            int number = attempts.incrementAndGet();
            Capture capture = new Capture();
            captures.add(capture);
            provider.streamChat(MODEL, List.of(Map.of("role", "user", "content", "fixture request")),
                    "system", List.of(), 1024, new ThinkingConfig.Disabled(),
                    new LlmCallContext("fixture-attempt-" + number, CancellationSignal.none()), capture);
            if (capture.error instanceof LlmApiException apiError) throw apiError;
            if (capture.error != null) throw new AssertionError(capture.error);
            assertThat(capture.completions).isEqualTo(1);
            return null;
        }

        String authorization() throws Exception {
            var request = server.takeRequest(5, TimeUnit.SECONDS);
            assertThat(request).isNotNull();
            return request.getHeader("Authorization");
        }

        @Override public void close() throws Exception { server.close(); }
    }

    private static final class Capture implements StreamChatCallback {
        final List<LlmStreamEvent> events = new ArrayList<>();
        Throwable error;
        int completions;
        @Override public void onEvent(LlmStreamEvent event) { events.add(event); }
        @Override public void onComplete() { completions++; }
        @Override public void onError(Throwable failure) { error = failure; }
    }
}
