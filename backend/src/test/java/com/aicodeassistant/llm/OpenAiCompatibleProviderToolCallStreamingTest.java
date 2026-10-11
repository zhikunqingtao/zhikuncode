package com.aicodeassistant.llm;

import com.aicodeassistant.llm.impl.OpenAiCompatibleProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
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
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class OpenAiCompatibleProviderToolCallStreamingTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private MockWebServer server;
    private OpenAiCompatibleProvider provider;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        String baseUrl = server.url("/v1").toString().replaceAll("/$", "");
        LlmHttpProperties http = new LlmHttpProperties(
                new LlmHttpProperties.PoolProperties(2, 30), 10, 10, true);
        provider = new OpenAiCompatibleProvider(
                "test", mapper, http, new ApiKeyRotationManager("key"),
                "key", baseUrl, "qwen3.8-max",
                List.of("qwen3.8-max", "kimi-k3"));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void acceptsQwenEmptyIdentityContinuation() {
        assertAccepted("qwen3.8-max", "", "", false);
    }

    @Test
    void preservesKimiSparseContinuation() {
        assertAccepted("kimi-k3", null, null, false);
    }

    @Test
    void openRouterUnionUsesChatCompletionsAndAcceptsToolDeltas() throws Exception {
        provider = new OpenAiCompatibleProvider(
                "openrouter", mapper,
                new LlmHttpProperties(new LlmHttpProperties.PoolProperties(2, 30), 10, 10, true),
                new ApiKeyRotationManager("key"), "key", server.url("/api/v1").toString(),
                "stealth/union-alpha", List.of("stealth/union-alpha"));
        assertAccepted("stealth/union-alpha", null, null, true);
        var request = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals("/api/v1/chat/completions", request.getPath());
        assertEquals("Bearer key", request.getHeader("Authorization"));
        var body = mapper.readTree(request.getBody().readUtf8());
        assertEquals("stealth/union-alpha", body.path("model").asText());
        assertEquals(1024, body.path("max_tokens").asInt());
        assertTrue(body.path("stream").asBoolean());
        assertFalse(body.has("thinking"));
        assertFalse(body.has("enable_thinking"));
        assertFalse(body.has("reasoning_effort"));
    }

    @Test
    void usageOnlyTailDoesNotReplaceToolUseStopReason() {
        assertAccepted("qwen3.8-max", "", "", true);
    }

    @Test
    void repeatedFinishChunkDoesNotEmitDuplicateBlockStop() {
        Capture capture = run(
                toolChunk("call-1", "Brief", "{}"),
                finishChunk(), finishChunk());

        assertNull(capture.error);
        assertEquals(1, capture.events.stream()
                .filter(LlmStreamEvent.BlockStop.class::isInstance)
                .count());
    }

    @Test
    void rejectsEmptyIdentityBeforeItIsEstablished() {
        Capture capture = run(toolChunk("", "", "{}"), finishChunk());

        assertFalse(capture.completed);
        assertTrue(capture.events.isEmpty());
        LlmApiException error = assertInstanceOf(LlmApiException.class, capture.error);
        assertFalse(error.isRetryable());
        assertTrue(error.getMessage().startsWith("INVALID_TOOL_CALL_STREAM:"));
    }

    @Test
    void rejectsIdentityChangesForTheSameToolIndex() {
        Capture capture = run(
                toolChunk("call-1", "Brief", "{"),
                toolChunk("call-2", "Bash", "}"),
                finishChunk());

        assertFalse(capture.completed);
        LlmApiException error = assertInstanceOf(LlmApiException.class, capture.error);
        assertFalse(error.isRetryable());
        assertTrue(error.getMessage().contains("conflicting id"));
    }

    @Test
    void rejectsEofAndDoneWithoutFinishReason() {
        Capture withDone = run(toolChunk("call-1", "Brief", "{}"));
        assertFalse(withDone.completed);
        assertInstanceOf(LlmApiException.class, withDone.error);

        Capture atEof = runRaw("data: " + toolChunk("call-1", "Brief", "{}") + "\n\n");
        assertFalse(atEof.completed);
        assertInstanceOf(LlmApiException.class, atEof.error);
    }

    @Test
    void acceptsValidFinishAtEofForOrdinaryCompatibleProvider() {
        Capture capture = runRaw("data: " + finishChunk() + "\n\n");
        assertTrue(capture.completed);
        assertNull(capture.error);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void completedStreamDiagnosesCrossDeltaMarkerWithoutChangingOutput(boolean withDone) {
        String first = "private reply\n...(content ";
        String second = "truncated)";
        String thinking = "private reasoning";
        String trailingWhitespace = "\n" + " ".repeat(256);
        String upstreamId = "upstream/\t injected " + "x".repeat(140);
        String body = "data: " + textChunk(first, thinking, null) + "\n\n"
                + "data: " + textChunk(second, null, "stop") + "\n\n"
                + "data: " + textChunk(trailingWhitespace, null, null) + "\n\n"
                + "data: " + usageChunk() + "\n\n"
                + (withDone ? "data: [DONE]\n\n" : "");
        var appender = new CapturingAppender();
        appender.start();
        LoggerConfig appLoggers = appLoggerConfig();
        appLoggers.addAppender(appender, null, null);
        try {
            server.enqueue(new MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setHeader("x-request-id", upstreamId).setBody(body));
            Capture capture = new Capture();
            provider.streamChat("qwen3.8-max",
                    List.of(Map.of("role", "user", "content", "test")),
                    "system", List.of(), 1024, new ThinkingConfig.Disabled(),
                    new LlmCallContext("output-diagnostic-test", new TestCancellationSignal()), capture);

            assertTrue(capture.completed);
            assertNull(capture.error);
            assertEquals(6, capture.events.size());
            assertEquals(thinking, assertInstanceOf(
                    LlmStreamEvent.ThinkingDelta.class, capture.events.get(0)).thinking());
            assertEquals(first, assertInstanceOf(
                    LlmStreamEvent.TextDelta.class, capture.events.get(1)).text());
            assertEquals(second, assertInstanceOf(
                    LlmStreamEvent.TextDelta.class, capture.events.get(2)).text());
            assertEquals("end_turn", assertInstanceOf(
                    LlmStreamEvent.MessageDelta.class, capture.events.get(3)).stopReason());
            assertEquals(trailingWhitespace, assertInstanceOf(
                    LlmStreamEvent.TextDelta.class, capture.events.get(4)).text());
            assertNull(assertInstanceOf(
                    LlmStreamEvent.MessageDelta.class, capture.events.get(5)).stopReason());

            List<LogEvent> diagnostics = appender.outputDiagnostics();
            assertEquals(1, diagnostics.size());
            assertEquals(Level.WARN, diagnostics.getFirst().getLevel());
            String diagnostic = diagnostics.getFirst().getMessage().getFormattedMessage();
            assertTrue(diagnostic.contains("callId=output-diagnostic-test"));
            assertTrue(diagnostic.contains("end=" + (withDone ? "done" : "eof")));
            assertTrue(diagnostic.contains("rawFinishReason=stop"));
            assertTrue(diagnostic.contains("textChars="
                    + (first.length() + second.length() + trailingWhitespace.length())));
            String safeUpstreamId = upstreamId.substring(0, 128).replaceAll("[^A-Za-z0-9._-]", "_");
            assertTrue(diagnostic.endsWith("upstreamRequestId=" + safeUpstreamId));
            assertFalse(diagnostic.contains(first));
            assertFalse(diagnostic.contains(second));
            assertFalse(diagnostic.contains(thinking));
            assertFalse(diagnostic.contains("Bearer key"));
            assertFalse(diagnostic.contains(upstreamId));
            assertFalse(diagnostic.contains("\n"));
            assertFalse(diagnostic.contains("\t"));
        } finally {
            appLoggers.removeAppender(appender.getName());
            appender.stop();
        }
        assertNoResidualProviderLoggerConfig();
    }

    @Test
    void ordinaryOutputAndOtherEndingsHaveNoNewDiagnostic() {
        var appender = new CapturingAppender();
        appender.start();
        LoggerConfig appLoggers = appLoggerConfig();
        appLoggers.addAppender(appender, null, null);
        try {
            for (String[] chunks : List.of(
                    new String[]{textChunk("ordinary reply", null, "stop")},
                    new String[]{textChunk("...(con", null, null), textChunk("\n", null, null),
                            textChunk("tent truncated)", null, "stop")},
                    new String[]{textChunk("...(content truncated)", null, null),
                            textChunk(" ", null, null), textChunk("continued", null, "stop")},
                    new String[]{textChunk("[content truncated by system]", null, "stop")},
                    new String[]{textChunk("...(content truncated)", null, "tool_calls")},
                    new String[]{textChunk("...(content truncated)", null, "length")},
                    new String[]{textChunk("...(content truncated)", null, "private-finish\nforged-log")})) {
                Capture capture = run(chunks);
                assertTrue(capture.completed);
                assertNull(capture.error);
            }
            assertTrue(appender.outputDiagnostics().isEmpty());
        } finally {
            appLoggers.removeAppender(appender.getName());
            appender.stop();
        }
        assertNoResidualProviderLoggerConfig();
    }

    @Test
    void disabledTailDiagnosticLeavesOutputUnchanged() {
        var appender = new CapturingAppender();
        appender.start();
        LoggerConfig appLoggers = appLoggerConfig();
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        Level originalLevel = appLoggers.getLevel();
        appLoggers.addAppender(appender, null, null);
        try {
            appLoggers.setLevel(Level.ERROR);
            context.updateLoggers();
            String text = "reply\n...(content truncated)";
            Capture capture = run(textChunk(text, null, "stop"));
            assertTrue(capture.completed);
            assertNull(capture.error);
            assertEquals(text, assertInstanceOf(LlmStreamEvent.TextDelta.class, capture.events.getFirst()).text());
            assertEquals("end_turn", assertInstanceOf(
                    LlmStreamEvent.MessageDelta.class, capture.events.get(1)).stopReason());
            assertTrue(appender.outputDiagnostics().isEmpty());
        } finally {
            appLoggers.setLevel(originalLevel);
            context.updateLoggers();
            appLoggers.removeAppender(appender.getName());
            appender.stop();
        }
        assertNoResidualProviderLoggerConfig();
    }

    @Test
    void outputDiagnosticAppenderFailureDoesNotChangeCompletion() {
        var diagnosticAttempted = new AtomicBoolean();
        var failingAppender = new AbstractAppender("failing-output-diagnostic-test", null, null, false, null) {
            @Override public void append(LogEvent event) {
                if (event.getMessage().getFormattedMessage().startsWith("OpenAI stream tail marker observed:")) {
                    diagnosticAttempted.set(true);
                    throw new IllegalStateException("diagnostic write failed");
                }
            }
        };
        failingAppender.start();
        LoggerConfig appLoggers = appLoggerConfig();
        appLoggers.addAppender(failingAppender, null, null);
        try {
            String text = "reply\n...(content truncated)";
            Capture capture = run(textChunk(text, null, "stop"));
            assertTrue(diagnosticAttempted.get());
            assertTrue(capture.completed);
            assertNull(capture.error);
            assertEquals(text, assertInstanceOf(LlmStreamEvent.TextDelta.class, capture.events.getFirst()).text());
            assertEquals(2, capture.events.size());
        } finally {
            appLoggers.removeAppender(failingAppender.getName());
            failingAppender.stop();
        }
        assertNoResidualProviderLoggerConfig();
    }

    @Test
    void incompleteStreamLogIdentifiesIgnoredFinishFrameWithoutLoggingContent() {
        var appender = new CapturingAppender();
        appender.start();
        LoggerConfig appLoggers = appLoggerConfig();
        appLoggers.addAppender(appender, null, null);
        try {
            Capture capture = runRaw("data:{\"choices\":[{\"delta\":{\"content\":\"private text\"},"
                    + "\"finish_reason\":\"stop\"}]}\n\ndata:[DONE]\n\n");
            assertFalse(capture.completed);
            assertTrue(capture.error.getMessage().contains("missing finish_reason"));
            var diagnostic = appender.messages.stream()
                    .filter(message -> message.startsWith("OpenAI stream missing finish_reason:"))
                    .toList();
            assertEquals(1, diagnostic.size());
            assertTrue(diagnostic.getFirst().contains("end=eof"));
            assertTrue(diagnostic.getFirst().contains("ignoredDataFrames=1"));
            assertTrue(diagnostic.getFirst().contains("firstIssue=none"));
            assertTrue(diagnostic.getFirst().contains("no_space:choices=1,finish=stop"));
            assertFalse(diagnostic.getFirst().contains("private text"));

            appender.messages.clear();
            Capture valid = runRaw("data: " + finishChunk() + "\n\ndata: [DONE]\n\n");
            assertTrue(valid.completed);
            assertNull(valid.error);
            assertTrue(appender.messages.stream().noneMatch(message ->
                    message.startsWith("OpenAI stream missing finish_reason:")));

            appender.messages.clear();
            Capture withEarlierError = runRaw("data: {\"error\":{\"code\":429,"
                    + "\"message\":\"private error\"}}\n\ndata: [DONE]\n\n");
            assertFalse(withEarlierError.completed);
            assertTrue(appender.messages.stream().anyMatch(message ->
                    message.contains("firstIssue=provider_error_frame")
                            && !message.contains("private error")));
        } finally {
            appLoggers.removeAppender(appender.getName());
            appender.stop();
        }
        assertNoResidualProviderLoggerConfig();
    }

    @Test
    void diagnosticAppenderFailureDoesNotReplaceStreamError() {
        var diagnosticAttempted = new AtomicBoolean();
        var failingAppender = new AbstractAppender("failing-stream-diagnostic-test", null, null, false, null) {
            @Override public void append(LogEvent event) {
                if (event.getMessage().getFormattedMessage().startsWith("OpenAI stream missing finish_reason:")) {
                    diagnosticAttempted.set(true);
                    throw new IllegalStateException("diagnostic write failed");
                }
            }
        };
        failingAppender.start();
        LoggerConfig appLoggers = appLoggerConfig();
        appLoggers.addAppender(failingAppender, null, null);
        try {
            Capture capture = runRaw("data: [DONE]\n\n");
            assertTrue(diagnosticAttempted.get());
            assertFalse(capture.completed);
            LlmApiException error = assertInstanceOf(LlmApiException.class, capture.error);
            assertEquals("OPENAI_COMPATIBLE_INCOMPLETE_STREAM: missing finish_reason", error.getMessage());
            assertFalse(error.isRetryable());
        } finally {
            appLoggers.removeAppender(failingAppender.getName());
            failingAppender.stop();
        }
        assertNoResidualProviderLoggerConfig();
    }

    @Test
    void truncatedStreamEndingWithoutNewlineIsDiagnosedAsIncomplete() {
        var appender = new CapturingAppender();
        appender.start();
        LoggerConfig appLoggers = appLoggerConfig();
        appLoggers.addAppender(appender, null, null);
        try {
            // The connection is cut mid-frame: the final line never receives its newline,
            // so readUtf8LineStrict throws EOFException instead of ending the loop normally.
            Capture capture = runRaw("data: " + toolChunk("call-1", "Brief", "{}")
                    + "\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"truncated-secret\"}");
            assertFalse(capture.completed);
            LlmApiException error = assertInstanceOf(LlmApiException.class, capture.error);
            assertTrue(error.isRetryable());
            assertTrue(error.getMessage().startsWith("OpenAI stream error:"));
            var diagnostic = appender.messages.stream()
                    .filter(message -> message.startsWith("OpenAI stream missing finish_reason:"))
                    .toList();
            assertEquals(1, diagnostic.size());
            assertTrue(diagnostic.getFirst().contains("end=eof"));
            assertTrue(diagnostic.getFirst().contains("firstIssue=none"));
            assertFalse(diagnostic.getFirst().contains("truncated-secret"));
        } finally {
            appLoggers.removeAppender(appender.getName());
            appender.stop();
        }
        assertNoResidualProviderLoggerConfig();
    }

    @Test
    void truncatedTailAfterFinishKeepsStreamErrorWithoutMissingFinishDiagnostic() {
        var appender = new CapturingAppender();
        appender.start();
        LoggerConfig appLoggers = appLoggerConfig();
        appLoggers.addAppender(appender, null, null);
        try {
            // Existing compatibility limitation, not a requirement to reject this forever:
            // this diagnostic-only change deliberately preserves the terminal error policy.
            Capture capture = runRaw("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}"
                    + "\n\ndata: [DONE]");

            assertEquals(1, capture.events.stream()
                    .filter(event -> event instanceof LlmStreamEvent.MessageDelta delta
                            && "end_turn".equals(delta.stopReason()))
                    .count());
            assertFalse(capture.completed);
            LlmApiException error = assertInstanceOf(LlmApiException.class, capture.error);
            assertTrue(error.isRetryable());
            assertTrue(error.getMessage().startsWith("OpenAI stream error:"));
            assertInstanceOf(java.io.EOFException.class, error.getCause());
            assertTrue(appender.messages.stream().noneMatch(message ->
                    message.startsWith("OpenAI stream missing finish_reason:")));
        } finally {
            appLoggers.removeAppender(appender.getName());
            appender.stop();
        }
        assertNoResidualProviderLoggerConfig();
    }

    @Test
    void canceledTruncatedTailIsNotDiagnosedAsMissingFinishReason() {
        var appender = new CapturingAppender();
        appender.start();
        LoggerConfig appLoggers = appLoggerConfig();
        appLoggers.addAppender(appender, null, null);
        try {
            var signal = new TestCancellationSignal();
            var error = new AtomicReference<Throwable>();
            var completed = new AtomicBoolean();
            server.enqueue(new MockResponse().setResponseCode(200)
                    .setHeader("Content-Type", "text/event-stream")
                    .setBody("data: " + toolChunk("call-1", "Brief", "{}")
                            + "\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"truncated-secret\"}"));
            provider.streamChat("qwen3.8-max",
                    List.of(Map.of("role", "user", "content", "test")),
                    "system", List.of(), 1024, new ThinkingConfig.Disabled(),
                    new LlmCallContext("cancel-guard-test", signal),
                    new StreamChatCallback() {
                        @Override public void onEvent(LlmStreamEvent event) { signal.cancel(); }
                        @Override public void onComplete() { completed.set(true); }
                        @Override public void onError(Throwable failure) { error.set(failure); }
                    });

            assertTrue(signal.isCancelled());
            LlmApiException failure = assertInstanceOf(LlmApiException.class, error.get());
            assertEquals("LLM_CALL_CANCELLED", failure.getMessage());
            assertFalse(completed.get());
            assertTrue(appender.messages.stream().noneMatch(message ->
                    message.startsWith("OpenAI stream missing finish_reason:")));
        } finally {
            appLoggers.removeAppender(appender.getName());
            appender.stop();
        }
        assertNoResidualProviderLoggerConfig();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void canceledBufferedDoneOrEofDoesNotLogMissingFinish(boolean withDone) {
        String body = "data: {\"choices\":[{\"delta\":{\"content\":\"buffered\"},\"finish_reason\":null}]}\n\n"
                + (withDone ? "data: [DONE]\n\n" : "");
        // An in-memory response makes the already-buffered-tail race deterministic:
        // canceling the call does not discard bytes already delivered to the reader.
        var client = new okhttp3.OkHttpClient.Builder().addInterceptor(chain ->
                new okhttp3.Response.Builder().request(chain.request())
                        .protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("OK")
                        .body(okhttp3.ResponseBody.create(body,
                                okhttp3.MediaType.get("text/event-stream"))).build()).build();
        ReflectionTestUtils.setField(provider, "httpClient", client);
        var appender = new CapturingAppender();
        appender.start();
        LoggerConfig appLoggers = appLoggerConfig();
        appLoggers.addAppender(appender, null, null);
        try {
            var signal = new TestCancellationSignal();
            var error = new AtomicReference<Throwable>();
            var completed = new AtomicBoolean();
            provider.streamChat("qwen3.8-max",
                    List.of(Map.of("role", "user", "content", "test")),
                    "system", List.of(), 1024, new ThinkingConfig.Disabled(),
                    new LlmCallContext("cancel-buffered-test", signal),
                    new StreamChatCallback() {
                        @Override public void onEvent(LlmStreamEvent event) { signal.cancel(); }
                        @Override public void onComplete() { completed.set(true); }
                        @Override public void onError(Throwable failure) { error.set(failure); }
                    });

            assertTrue(signal.isCancelled());
            assertFalse(completed.get());
            // Preserve the existing provider error classification; this is not a test
            // claiming that all provider cancellation outcomes have been normalized.
            LlmApiException failure = assertInstanceOf(LlmApiException.class, error.get());
            assertEquals("OPENAI_COMPATIBLE_INCOMPLETE_STREAM: missing finish_reason", failure.getMessage());
            assertFalse(failure.isRetryable());
            assertTrue(appender.messages.stream().noneMatch(message ->
                    message.startsWith("OpenAI stream missing finish_reason:")));
        } finally {
            appLoggers.removeAppender(appender.getName());
            appender.stop();
            client.connectionPool().evictAll();
            client.dispatcher().executorService().shutdown();
        }
        assertNoResidualProviderLoggerConfig();
    }

    @Test
    void incompleteStreamDiagnosticsDistinguishTruncatedFrameFromExactLimit() {
        var appender = new CapturingAppender();
        appender.start();
        LoggerConfig appLoggers = appLoggerConfig();
        appLoggers.addAppender(appender, null, null);
        try {
            // Exactly at the retention limit: the frame is complete and still parsed as-is.
            Capture exact = runRaw(paddedFrame(16_384) + "\n\ndata: [DONE]\n\n");
            assertFalse(exact.completed);
            var exactDiagnostic = appender.messages.stream()
                    .filter(message -> message.startsWith("OpenAI stream missing finish_reason:"))
                    .toList();
            assertEquals(1, exactDiagnostic.size());
            assertTrue(exactDiagnostic.getFirst().contains(
                    "lastFrame=standard:choices=1,finish=none,error=false"));
            assertFalse(exactDiagnostic.getFirst().contains("truncated"));

            appender.messages.clear();
            // One character over the limit: retention cuts the frame and flags it explicitly.
            Capture oversized = runRaw(paddedFrame(16_385) + "\n\ndata: [DONE]\n\n");
            assertFalse(oversized.completed);
            var oversizedDiagnostic = appender.messages.stream()
                    .filter(message -> message.startsWith("OpenAI stream missing finish_reason:"))
                    .toList();
            assertEquals(1, oversizedDiagnostic.size());
            assertTrue(oversizedDiagnostic.getFirst().contains("lastFrame=standard:truncated"));
        } finally {
            appLoggers.removeAppender(appender.getName());
            appender.stop();
        }
        assertNoResidualProviderLoggerConfig();
    }

    /** Pads an otherwise valid stream frame with trailing spaces to an exact line length. */
    private String paddedFrame(int lineLength) {
        String json = "{\"choices\":[{\"delta\":{},\"finish_reason\":null}]}";
        return "data: " + json + " ".repeat(lineLength - 6 - json.length());
    }

    /**
     * P2-10: diagnostics appenders are attached to the shared {@code com.aicodeassistant}
     * LoggerConfig. {@code LogManager.getLogger(Provider.class).addAppender(...)} must not be
     * used — it materializes a dedicated per-class LoggerConfig (inheriting additivity=false)
     * which survives appender removal with zero appenders and silently drops all later provider
     * log events in the surefire JVM.
     */
    private static LoggerConfig appLoggerConfig() {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        return context.getConfiguration().getLoggerConfig("com.aicodeassistant");
    }

    private static void assertNoResidualProviderLoggerConfig() {
        String providerLoggerName = OpenAiCompatibleProvider.class.getName();
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        assertNotEquals(providerLoggerName,
                context.getConfiguration().getLoggerConfig(providerLoggerName).getName(),
                "appender capture must not leave a per-class LoggerConfig behind");
    }

    private static final class CapturingAppender extends AbstractAppender {
        final List<String> messages = new ArrayList<>();
        final List<LogEvent> events = new ArrayList<>();

        CapturingAppender() { super("stream-diagnostic-test", null, null, true, null); }

        @Override public void append(LogEvent event) {
            messages.add(event.getMessage().getFormattedMessage());
            events.add(event.toImmutable());
        }

        List<LogEvent> outputDiagnostics() {
            return events.stream().filter(event -> event.getMessage().getFormattedMessage()
                    .startsWith("OpenAI stream tail marker observed:")).toList();
        }
    }

    /** Minimal race-safe signal driving the provider's registered cancellation callback. */
    private static final class TestCancellationSignal implements CancellationSignal {
        private final List<Runnable> callbacks = new ArrayList<>();
        private boolean cancelled;

        @Override public synchronized boolean isCancelled() { return cancelled; }

        @Override public synchronized Registration register(Runnable callback) {
            if (cancelled) callback.run(); else callbacks.add(callback);
            return () -> { synchronized (TestCancellationSignal.this) { callbacks.remove(callback); } };
        }

        synchronized void cancel() {
            cancelled = true;
            for (Runnable callback : new ArrayList<>(callbacks)) callback.run();
        }
    }

    private void assertAccepted(
            String model,
            String continuationId, String continuationName,
            boolean includeUsageTail) {
        List<String> chunks = new ArrayList<>(List.of(
                toolChunk("call-1", "Brief", "{\"query\":\""),
                toolChunk(continuationId, continuationName, "hello\"}"),
                finishChunk()));
        if (includeUsageTail) chunks.add(usageChunk());
        Capture capture = runForModel(model, chunks.toArray(String[]::new));

        assertNull(capture.error);
        assertTrue(capture.completed);
        assertEquals(includeUsageTail ? 6 : 5, capture.events.size());
        LlmStreamEvent.ToolUseStart start = assertInstanceOf(
                LlmStreamEvent.ToolUseStart.class, capture.events.get(0));
        LlmStreamEvent.ToolInputDelta first = assertInstanceOf(
                LlmStreamEvent.ToolInputDelta.class, capture.events.get(1));
        LlmStreamEvent.ToolInputDelta second = assertInstanceOf(
                LlmStreamEvent.ToolInputDelta.class, capture.events.get(2));
        assertInstanceOf(LlmStreamEvent.BlockStop.class, capture.events.get(3));
        LlmStreamEvent.MessageDelta end = assertInstanceOf(
                LlmStreamEvent.MessageDelta.class, capture.events.get(4));

        assertEquals("call-1", start.id());
        assertEquals("Brief", start.name());
        assertEquals("call-1", first.toolUseId());
        assertEquals("call-1", second.toolUseId());
        assertEquals("{\"query\":\"hello\"}", first.jsonDelta() + second.jsonDelta());
        assertEquals("tool_use", end.stopReason());
        if (includeUsageTail) {
            LlmStreamEvent.MessageDelta usage = assertInstanceOf(
                    LlmStreamEvent.MessageDelta.class, capture.events.get(5));
            assertNull(usage.stopReason());
            assertEquals(7, usage.usage().inputTokens());
        }
    }

    private Capture run(String... chunks) {
        return runForModel("qwen3.8-max", chunks);
    }

    private Capture runForModel(String model, String... chunks) {
        StringBuilder body = new StringBuilder();
        for (String chunk : chunks) {
            body.append("data: ").append(chunk).append("\n\n");
        }
        body.append("data: [DONE]\n\n");
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body.toString()));

        Capture capture = new Capture();
        provider.streamChat(
                model,
                List.of(Map.of("role", "user", "content", "test")),
                "system", List.of(), 1024, new ThinkingConfig.Disabled(),
                LlmCallContext.unscoped(), capture);
        return capture;
    }

    private Capture runRaw(String body) {
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream").setBody(body));
        Capture capture = new Capture();
        provider.streamChat("qwen3.8-max",
                List.of(Map.of("role", "user", "content", "test")),
                "system", List.of(), 1024, new ThinkingConfig.Disabled(),
                LlmCallContext.unscoped(), capture);
        return capture;
    }

    private String toolChunk(String id, String name, String arguments) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode choice = root.putArray("choices").addObject();
        choice.putNull("finish_reason");
        ObjectNode toolCall = choice.putObject("delta")
                .putArray("tool_calls").addObject();
        toolCall.put("index", 0);
        if (id != null) toolCall.put("id", id);
        ObjectNode function = toolCall.putObject("function");
        if (name != null) function.put("name", name);
        function.put("arguments", arguments);
        return root.toString();
    }

    private String textChunk(String text, String thinking, String finishReason) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode choice = root.putArray("choices").addObject();
        ObjectNode delta = choice.putObject("delta");
        if (text != null) delta.put("content", text);
        if (thinking != null) delta.put("reasoning_content", thinking);
        if (finishReason == null) choice.putNull("finish_reason");
        else choice.put("finish_reason", finishReason);
        return root.toString();
    }

    private String finishChunk() {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode choice = root.putArray("choices").addObject();
        choice.putObject("delta");
        choice.put("finish_reason", "tool_calls");
        return root.toString();
    }

    private String usageChunk() {
        ObjectNode root = mapper.createObjectNode();
        root.putArray("choices");
        ObjectNode usage = root.putObject("usage");
        usage.put("prompt_tokens", 7);
        usage.put("completion_tokens", 3);
        return root.toString();
    }

    private static final class Capture implements StreamChatCallback {
        final List<LlmStreamEvent> events = new ArrayList<>();
        Throwable error;
        boolean completed;

        @Override public void onEvent(LlmStreamEvent event) { events.add(event); }
        @Override public void onComplete() { completed = true; }
        @Override public void onError(Throwable error) { this.error = error; }
    }
}
