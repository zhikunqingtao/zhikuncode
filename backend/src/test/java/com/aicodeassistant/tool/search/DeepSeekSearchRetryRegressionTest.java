package com.aicodeassistant.tool.search;

import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeepSeekSearchRetryRegressionTest {
    private MockWebServer server;
    private DeepSeekSearchBackend backend;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        backend = new DeepSeekSearchBackend("dummy-key", server.url("/").toString());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private static MockResponse response(int status, String retryAfter) {
        MockResponse result = new MockResponse().setResponseCode(status)
                .setBody(status == 200 ? "{\"results\":[]}" : "{}");
        if (retryAfter != null) result.setHeader("Retry-After", retryAfter);
        return result;
    }

    private void alwaysRespond(int status, String retryAfter) {
        server.setDispatcher(new Dispatcher() {
            @Override public MockResponse dispatch(RecordedRequest request) {
                return response(status, retryAfter);
            }
        });
    }

    @ParameterizedTest
    @CsvSource(value = {"503|0", "503|ABSENT", "408|ABSENT", "429|0"}, delimiter = '|')
    @Timeout(8)
    void retryableStatusesHaveThreeActualRequestsAndLocalBackoff(int status, String header) throws Exception {
        alwaysRespond(status, "ABSENT".equals(header) ? null : header);
        long started = System.nanoTime();
        assertThatThrownBy(() -> backend.search("probe", SearchOptions.defaults()))
                .isInstanceOf(IOException.class).hasMessageContaining("HTTP " + status);
        assertThat(server.getRequestCount()).isEqualTo(3);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isGreaterThanOrEqualTo(1200L);
        String expectedBody = null;
        for (int i = 0; i < 3; i++) {
            RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
            assertThat(request).isNotNull();
            assertThat(request.getMethod()).isEqualTo("POST");
            assertThat(request.getPath()).isEqualTo("/v1/tools/search");
            assertThat(request.getHeader("Authorization")).isEqualTo("Bearer dummy-key");
            String actualBody = request.getBody().readUtf8();
            if (expectedBody == null) expectedBody = actualBody;
            assertThat(actualBody).isEqualTo(expectedBody);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 402})
    @Timeout(5)
    void nonRetryableStatusSendsOnlyOnce(int status) {
        alwaysRespond(status, null);
        assertThatThrownBy(() -> backend.search("probe", SearchOptions.defaults()))
                .isInstanceOf(IOException.class);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @Timeout(5)
    void retries408AfterTheRequestedSeconds() throws Exception {
        server.enqueue(response(408, "1"));
        server.enqueue(response(200, null));
        long started = System.nanoTime();
        assertThat(backend.search("probe", SearchOptions.defaults())).isEmpty();
        assertThat(server.getRequestCount()).isEqualTo(2);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isGreaterThanOrEqualTo(1000L);
    }

    @Test
    @Timeout(5)
    void honorsHttpDateBeforeSendingTheSecondRequest() throws Exception {
        Instant notBefore = Instant.now().plusSeconds(2).truncatedTo(ChronoUnit.SECONDS);
        String header = DateTimeFormatter.RFC_1123_DATE_TIME.format(notBefore.atZone(ZoneOffset.UTC));
        AtomicLong secondRequestAt = new AtomicLong();
        server.setDispatcher(new Dispatcher() {
            private int seen;
            @Override public MockResponse dispatch(RecordedRequest request) {
                if (++seen == 1) return response(503, header);
                secondRequestAt.set(System.currentTimeMillis());
                return response(200, null);
            }
        });
        assertThat(backend.search("probe", SearchOptions.defaults())).isEmpty();
        assertThat(server.getRequestCount()).isEqualTo(2);
        assertThat(secondRequestAt.get()).isGreaterThanOrEqualTo(notBefore.toEpochMilli());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Sat, 17 Oct 2015 00:00:00 GMT"})
    @Timeout(5)
    void pastRetryAfterStillUsesLocalBackoff(String header) throws Exception {
        server.enqueue(response(429, header));
        server.enqueue(response(200, null));
        long started = System.nanoTime();
        assertThat(backend.search("probe", SearchOptions.defaults())).isEmpty();
        assertThat(server.getRequestCount()).isEqualTo(2);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isGreaterThanOrEqualTo(300L);
    }

    @Test
    @Timeout(5)
    void retryAfterAboveCapFailsWithoutRetrying() {
        alwaysRespond(503, "31");
        assertThatThrownBy(() -> backend.search("probe", SearchOptions.defaults()))
                .isInstanceOf(SocketTimeoutException.class).hasMessageContaining("Retry-After");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @Timeout(5)
    void huge429RetryAfterCannotOverflowIntoAnEarlyRetry() {
        alwaysRespond(429, "9999999999999999999999999999");
        assertThatThrownBy(() -> backend.search("probe", SearchOptions.defaults()))
                .isInstanceOf(SocketTimeoutException.class).hasMessageContaining("Retry-After");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @Timeout(5)
    void okhttpNumericHeaderOverflowStopsWithoutAnotherRequest() {
        alwaysRespond(503, "9999999999999999999999999999");
        assertThatThrownBy(() -> backend.search("probe", SearchOptions.defaults()))
                .isInstanceOf(NumberFormatException.class);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @Timeout(5)
    void interruptingBackoffStopsFurtherRequestsAndRestoresInterruptFlag() throws Exception {
        alwaysRespond(429, "10");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            try {
                backend.search("probe", SearchOptions.defaults());
            } catch (Throwable error) {
                failure.set(error);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        }, "search-retry-interruption-test");
        worker.start();
        try {
            assertThat(server.takeRequest(2, TimeUnit.SECONDS)).isNotNull();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (worker.getState() != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            assertThat(worker.getState()).isEqualTo(Thread.State.TIMED_WAITING);
            worker.interrupt();
            worker.join(2000);
            assertThat(worker.isAlive()).isFalse();
            assertThat(failure.get()).isInstanceOf(InterruptedIOException.class);
            assertThat(interrupted.get()).isTrue();
            assertThat(server.getRequestCount()).isEqualTo(1);
        } finally {
            worker.interrupt();
            worker.join(2000);
        }
    }

    @Test
    @Timeout(5)
    void preInterruptedCallSendsNoRequestAndRetainsTheFlag() {
        // Keep the baseline run bounded if it incorrectly sends a request.
        server.enqueue(response(200, null));
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> backend.search("probe", SearchOptions.defaults()))
                    .isInstanceOf(InterruptedIOException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(server.getRequestCount()).isZero();
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"garbage", "-1", "Sat, 17 Oct 2026 12:00:00 GMT garbage"})
    @Timeout(5)
    void unrecognizedRetryAfterStopsWithoutAnEarlyRetry(String header) {
        alwaysRespond(429, header);
        assertThatThrownBy(() -> backend.search("probe", SearchOptions.defaults()))
                .isInstanceOf(IOException.class).hasMessageContaining("Unrecognized Retry-After");
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Tue, 06 Oct 2026 12:00:01 GMT",
            "Tuesday, 06-Oct-26 12:00:01 GMT", "Tue Oct  6 12:00:01 2026"})
    void acceptsAllThreeHttpDateFormsIncludingOneDigitAsctimeDay(String header) throws Exception {
        assertThat(DeepSeekSearchBackend.retryAfterMillis(header, Instant.parse("2026-10-06T12:00:00.250Z")))
                .isEqualTo(750L);
    }

    @Test
    void obsoleteRfc850YearUsesTheRollingCenturyWindow() throws Exception {
        assertThat(DeepSeekSearchBackend.retryAfterMillis(
                "Sunday, 06-Nov-94 08:49:37 GMT", Instant.parse("2026-10-06T12:00:00Z")))
                .isZero();
    }

    @Test
    @Timeout(5)
    void connectionFailureBeforeSendingCanTryAnotherAddress() throws Exception {
        server.shutdown();
        server = new MockWebServer();
        server.start(InetAddress.getByName("127.0.0.1"), 0);
        server.enqueue(response(200, null));
        List<InetSocketAddress> failedRoutes = new CopyOnWriteArrayList<>();
        InetAddress unreachableAddress = InetAddress.getByName("::1");
        OkHttpClient client = new OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .dns(host -> List.of(InetAddress.getByName("::1"), InetAddress.getByName("127.0.0.1")))
                .eventListener(new EventListener() {
                    @Override public void connectFailed(Call call, InetSocketAddress address, Proxy proxy,
                            okhttp3.Protocol protocol, IOException failure) {
                        failedRoutes.add(address);
                    }
                }).build();
        backend = new DeepSeekSearchBackend("dummy-key", "http://search.invalid:" + server.getPort(), client);
        assertThat(backend.search("probe", SearchOptions.defaults())).isEmpty();
        assertThat(failedRoutes).anySatisfy(address ->
                assertThat(address.getAddress()).isEqualTo(unreachableAddress));
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @Timeout(5)
    void failureAfterTheRequestWasSentDoesNotReplayIt() {
        server.enqueue(response(200, null).setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
        server.enqueue(response(200, null));
        assertThatThrownBy(() -> backend.search("probe", SearchOptions.defaults()))
                .isInstanceOf(IOException.class);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }
}
