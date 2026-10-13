package com.aicodeassistant.engine;

import com.aicodeassistant.llm.ApiCircuitBreaker;
import com.aicodeassistant.llm.LlmApiException;
import com.aicodeassistant.llm.ModelAwareRetryPolicy;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ApiRetryDiagnosticsTest {

    private static final String SECRET = "sk-test-private-token-0123456789";
    private static final String URL = "https://provider.invalid/chat?api_key=" + SECRET;
    private static final String BODY = "private-user-request-body";
    private static final String PRIVATE_MESSAGE = SECRET + " " + URL + " " + BODY;

    private ModelTierService tiers;
    private ModelAwareRetryPolicy policy;
    private ApiCircuitBreaker circuit;
    private ApiRetryService retries;
    private CapturingAppender appender;
    private LoggerConfig appLoggers;

    @BeforeEach
    void setUp() {
        tiers = mock(ModelTierService.class);
        policy = mock(ModelAwareRetryPolicy.class);
        circuit = mock(ApiCircuitBreaker.class);
        when(circuit.allowRequest()).thenReturn(true);
        when(policy.calculateDelay(anyString(), anyInt())).thenReturn(Duration.ZERO);
        retries = new ApiRetryService(tiers, policy, circuit);

        appender = new CapturingAppender();
        appender.start();
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        // Attach to the existing shared configuration without creating a persistent class logger.
        appLoggers = context.getConfiguration().getLoggerConfig("com.aicodeassistant");
        appLoggers.addAppender(appender, null, null);
    }

    @AfterEach
    void tearDown() {
        appLoggers.removeAppender(appender.getName());
        appender.stop();
    }

    @Test
    void scheduledRetryReportsNestedTimeoutTypesWithoutChangingSuccessfulResult() {
        LlmApiException failure = new LlmApiException(PRIVATE_MESSAGE,
                new IOException(PRIVATE_MESSAGE, new SocketTimeoutException(PRIVATE_MESSAGE)), true);
        Object expected = new Object();
        AtomicInteger calls = new AtomicInteger();

        Object result = retries.executeWithRetry(() -> {
            if (calls.incrementAndGet() == 1) throw failure;
            return expected;
        }, "sdk", "model");

        assertSame(expected, result);
        assertEquals(2, calls.get());
        assertEquals(1, appender.events.size());
        LogEvent event = appender.events.getFirst();
        assertEquals(Level.WARN, event.getLevel());
        assertTrue(message(event).startsWith("LLM retry scheduled:"));
        assertTrue(message(event).contains("status=0, errorType=null"));
        assertTrue(message(event).endsWith("exceptionChain=" + LlmApiException.class.getName()
                + " -> java.io.IOException -> java.net.SocketTimeoutException"));
        assertSafeDiagnostics();
        verify(tiers).reportSuccess("model");
        verify(circuit).recordSuccess();
        verify(circuit, never()).recordFailure();
        verify(policy).calculateDelay("model", 0);
    }

    @Test
    void exhaustedRetryReportsNestedEofTypesAndThrowsOriginalException() {
        LlmApiException failure = new LlmApiException(PRIVATE_MESSAGE,
                new IOException(PRIVATE_MESSAGE, new EOFException(PRIVATE_MESSAGE)), true);
        AtomicInteger calls = new AtomicInteger();

        LlmApiException thrown = assertThrows(LlmApiException.class,
                () -> retries.executeWithRetry(() -> {
                    calls.incrementAndGet();
                    throw failure;
                }, "sdk", "model"));

        assertSame(failure, thrown);
        assertEquals(10, calls.get());
        assertEquals(10, appender.events.size());
        assertEquals(9, appender.events.stream()
                .filter(event -> message(event).startsWith("LLM retry scheduled:")).count());
        LogEvent exhausted = appender.events.getLast();
        assertEquals(Level.ERROR, exhausted.getLevel());
        assertTrue(message(exhausted).startsWith("LLM retry exhausted:"));
        assertTrue(message(exhausted).endsWith("exceptionChain=" + LlmApiException.class.getName()
                + " -> java.io.IOException -> java.io.EOFException"));
        assertSafeDiagnostics();
        verify(circuit).recordFailure();
        verify(circuit, never()).recordSuccess();
        verify(policy, times(9)).calculateDelay(eq("model"), anyInt());
    }

    @Test
    void directSuccessHasNoFailureLogAndAnExceptionWithoutCauseCanStillRetry() {
        Object expected = new Object();
        assertSame(expected, retries.executeWithRetry(() -> expected, "sdk", "model"));
        assertTrue(appender.events.isEmpty());

        AtomicInteger calls = new AtomicInteger();
        assertSame(expected, retries.executeWithRetry(() -> {
            if (calls.incrementAndGet() == 1) throw new LlmApiException(PRIVATE_MESSAGE, true);
            return expected;
        }, "sdk", "model"));

        assertEquals(2, calls.get());
        assertEquals(1, appender.events.size());
        assertTrue(message(appender.events.getFirst())
                .endsWith("exceptionChain=" + LlmApiException.class.getName()));
        assertSafeDiagnostics();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cyclicAndDeepCauseChainsAreBoundedAndAllowRetrySuccess(boolean cyclic) {
        IOException cause = new IOException(PRIVATE_MESSAGE);
        if (cyclic) {
            IOException next = new IOException(PRIVATE_MESSAGE, cause);
            cause.initCause(next);
        } else {
            for (int depth = 0; depth < 24; depth++) {
                cause = new IOException(PRIVATE_MESSAGE, cause);
            }
        }
        LlmApiException failure = new LlmApiException(PRIVATE_MESSAGE, cause, true);
        AtomicInteger calls = new AtomicInteger();
        Object expected = new Object();

        Object result = assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> retries.executeWithRetry(() -> {
                    if (calls.incrementAndGet() == 1) throw failure;
                    return expected;
                }, "sdk", "model"));

        assertSame(expected, result);
        assertEquals(2, calls.get());
        assertEquals(1, appender.events.size());
        String diagnostic = message(appender.events.getFirst());
        assertTrue(diagnostic.endsWith(" -> [truncated]"));
        String chain = diagnostic.substring(diagnostic.indexOf("exceptionChain=") + "exceptionChain=".length());
        assertEquals(17, chain.split(" -> ").length, "At most 16 types plus the truncation marker");
        assertSafeDiagnostics();
    }

    private void assertSafeDiagnostics() {
        for (LogEvent event : appender.events) {
            assertFalse(message(event).contains(SECRET));
            assertFalse(message(event).contains(URL));
            assertFalse(message(event).contains(BODY));
            assertNull(event.getThrown(), "Diagnostics must not attach the exception");
            assertNull(event.getThrownProxy(), "Diagnostics must not include a stack trace");
        }
    }

    private static String message(LogEvent event) {
        return event.getMessage().getFormattedMessage();
    }

    private static final class CapturingAppender extends AbstractAppender {
        private final List<LogEvent> events = new CopyOnWriteArrayList<>();

        private CapturingAppender() {
            super("api-retry-diagnostics-test", null, null, true, null);
        }

        @Override
        public void append(LogEvent event) {
            if (ApiRetryService.class.getName().equals(event.getLoggerName())) {
                events.add(event.toImmutable());
            }
        }
    }
}
