package com.aicodeassistant.llm.impl;

import com.aicodeassistant.llm.LlmApiException;
import com.aicodeassistant.llm.LlmStreamEvent;
import com.aicodeassistant.llm.ProviderErrorClassifier;
import com.aicodeassistant.llm.StreamChatCallback;
import okhttp3.*;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** One physical generation attempt. No payloads, credentials or exception messages enter diagnostics. */
final class StreamTransportAttempt extends EventListener {
    private static final Logger log = LoggerFactory.getLogger(StreamTransportAttempt.class);
    private final String callId;
    private final String attemptId = "attempt-" + UUID.randomUUID();
    private final long startedNanos = System.nanoTime();
    private boolean connected;
    private boolean requestStarted;
    private boolean responseReceived;
    private boolean terminal;
    private boolean progress;
    private int status;
    private long firstByteMillis = -1;
    private long headersMillis = -1;
    private long requestBodyDeclaredBytes = -1;
    private long requestBodyCompletedBytes = -1;
    private long requestBodyStartMillis = -1;
    private long requestBodyEndMillis = -1;
    private long requestFailedMillis = -1;
    private String requestFailureType = "none";
    private int connectTimeoutMillis = -1;
    private int readTimeoutMillis = -1;
    private int writeTimeoutMillis = -1;
    private int callTimeoutMillis = -1;
    private long bytes;
    private long frames;
    private long events;
    private String protocol = "unknown";
    private String tls = "unknown";
    private String reused = "unknown";
    private String upstreamId = "none";
    private String outcome = "unfinished";
    private String exceptionType = "none";

    StreamTransportAttempt(String callId) { this.callId = safeId(callId); }

    Call newCall(OkHttpClient client, Request request) {
        connectTimeoutMillis = client.connectTimeoutMillis();
        readTimeoutMillis = client.readTimeoutMillis();
        writeTimeoutMillis = client.writeTimeoutMillis();
        callTimeoutMillis = client.callTimeoutMillis();
        // retryOnConnectionFailure alone does not disable a 503/Retry-After:0 follow-up.
        // isOneShot also forbids all follow-ups that would replay this generation body.
        Request oneShot = request.newBuilder().method(request.method(), oneShot(request.body())).build();
        return client.newBuilder().retryOnConnectionFailure(false).eventListener(this).build().newCall(oneShot);
    }

    private RequestBody oneShot(RequestBody body) {
        if (body == null) throw new IllegalArgumentException("Generation requires a request body");
        return new RequestBody() {
            @Override public MediaType contentType() { return body.contentType(); }
            @Override public long contentLength() throws IOException {
                // Observe the existing query; never read or serialize the body again for diagnostics.
                return requestBodyDeclaredBytes = body.contentLength();
            }
            @Override public boolean isOneShot() { return true; }
            @Override public void writeTo(BufferedSink sink) throws IOException { body.writeTo(sink); }
        };
    }

    @Override public void connectStart(Call call, InetSocketAddress address, Proxy proxy) { connected = true; }
    @Override public void connectionAcquired(Call call, Connection connection) {
        reused = Boolean.toString(!connected);
        protocol = connection.protocol().toString();
        if (connection.handshake() != null) tls = connection.handshake().tlsVersion().javaName();
    }
    @Override public void requestHeadersStart(Call call) {
        requestStarted = true;
        log.info("LLM transport attempt started: callId={}, attemptId={}", callId, attemptId);
    }
    @Override public void requestBodyStart(Call call) { requestBodyStartMillis = elapsedMillis(); }
    @Override public void requestBodyEnd(Call call, long byteCount) {
        // Local write completion is not confirmation that the upstream processed the request.
        requestBodyEndMillis = elapsedMillis();
        requestBodyCompletedBytes = byteCount;
    }
    @Override public void requestFailed(Call call, IOException failure) {
        requestFailedMillis = elapsedMillis();
        requestFailureType = exceptionTypes(failure);
    }
    @Override public void responseHeadersEnd(Call call, Response response) { response(response); }

    void response(Response response) {
        // A permitted redirect may change a POST into a GET. Keep the final response status;
        // calling this again after execute() merely supplies a fallback for synthetic test clients.
        if (responseReceived && status == response.code()) return;
        responseReceived = true;
        status = response.code();
        headersMillis = elapsedMillis();
        protocol = response.protocol().toString();
        if (response.handshake() != null) tls = response.handshake().tlsVersion().javaName();
        String id = response.header("x-request-id");
        if (id == null) id = response.header("x-trace-id");
        upstreamId = safeId(id);
    }

    BufferedSource source(ResponseBody body) {
        return Okio.buffer(new ForwardingSource(body.source()) {
            @Override public long read(Buffer sink, long count) throws IOException {
                long read = super.read(sink, count);
                if (read > 0) {
                    if (firstByteMillis < 0) firstByteMillis = elapsedMillis();
                    bytes += read;
                }
                return read;
            }
        });
    }

    void frame() { frames++; }
    boolean terminal() { return terminal; }

    StreamChatCallback callback(StreamChatCallback delegate) {
        return new StreamChatCallback() {
            @Override public void onEvent(LlmStreamEvent event) {
                if (terminal) return;
                events++;
                if (event instanceof LlmStreamEvent.TextDelta || event instanceof LlmStreamEvent.ThinkingDelta
                        || event instanceof LlmStreamEvent.ToolUseStart || event instanceof LlmStreamEvent.ToolInputDelta
                        || event instanceof LlmStreamEvent.ProviderProgress || event instanceof LlmStreamEvent.ProviderResponseState) {
                    progress = true;
                }
                delegate.onEvent(event);
            }
            @Override public void onComplete() {
                if (terminal) return;
                terminal = true;
                outcome = "completed";
                delegate.onComplete();
            }
            @Override public void onError(Throwable failure) {
                if (terminal) return;
                terminal = true;
                Throwable reported = failure;
                boolean cancelled = failure instanceof LlmApiException error
                        && ("cancelled".equals(error.getErrorType()) || "LLM_CALL_CANCELLED".equals(error.getMessage()));
                if (progress && reported instanceof LlmApiException error && !error.isRetrySuppressed()) {
                    reported = error.withRetryable(false);
                }
                outcome = cancelled ? "cancelled"
                        : reported instanceof LlmApiException error && error.isResponseInterrupted()
                        ? "response_interrupted" : "failed";
                exceptionType = exceptionTypes(failure);
                delegate.onError(reported);
            }
        };
    }

    LlmApiException transportFailure(IOException failure) {
        exceptionType = exceptionTypes(failure);
        if (requestStarted || responseReceived) {
            return interrupted("OpenAI stream error: response interrupted; upstream outcome unknown", failure);
        }
        boolean connectFailure = ProviderErrorClassifier.findConnectException(failure) != null;
        LlmApiException error = new LlmApiException("Model connection failed before request transmission", failure,
                connectFailure, 0, "transport_error", 0);
        return connectFailure ? error : error.withRetryable(false);
    }

    /** Only callers that observed a transport/parsing interruption may mark the result unknown. */
    LlmApiException interrupted(String message, Throwable failure) {
        return new LlmApiException(message, failure, false, status, LlmApiException.RESPONSE_INTERRUPTED, 0);
    }

    void logFinished() {
        long elapsed = elapsedMillis();
        String bodyState = requestBodyEndMillis >= 0 ? "completed"
                : requestBodyStartMillis < 0 ? "not_started"
                : requestFailedMillis >= 0 ? "failed" : "unfinished";
        long bodyEnd = requestBodyEndMillis >= 0 ? requestBodyEndMillis
                : requestFailedMillis >= 0 ? requestFailedMillis : elapsed;
        long bodyMillis = requestBodyStartMillis < 0 ? -1 : bodyEnd - requestBodyStartMillis;
        log.info("LLM transport attempt finished: callId={}, attemptId={}, outcome={}, phase={}, status={}, protocol={}, tls={}, reused={}, upstreamRequestId={}, receivedBytes={}, frames={}, events={}, headersMs={}, firstByteMs={}, elapsedMs={}, exceptionType={}, requestBodyState={}, requestBodyDeclaredBytes={}, requestBodyCompletedBytes={}, requestBodyStartMs={}, requestBodyEndMs={}, requestFailedMs={}, requestBodyMs={}, requestFailureType={}, connectTimeoutMs={}, readTimeoutMs={}, writeTimeoutMs={}, callTimeoutMs={}",
                callId, attemptId, outcome, responseReceived ? "response" : requestStarted ? "request_started" : "before_send",
                status, protocol, tls, reused, upstreamId, bytes, frames, events, headersMillis, firstByteMillis,
                elapsed, exceptionType, bodyState, requestBodyDeclaredBytes, requestBodyCompletedBytes,
                requestBodyStartMillis, requestBodyEndMillis, requestFailedMillis, bodyMillis, requestFailureType,
                connectTimeoutMillis, readTimeoutMillis, writeTimeoutMillis, callTimeoutMillis);
    }

    private long elapsedMillis() { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos); }
    private static String exceptionTypes(Throwable failure) {
        StringBuilder types = new StringBuilder();
        for (int depth = 0; failure != null && depth < 8; depth++, failure = failure.getCause()) {
            if (!types.isEmpty()) types.append('>');
            types.append(failure.getClass().getSimpleName());
        }
        return types.toString();
    }
    private static String safeId(String value) {
        if (value == null || value.isBlank()) return "none";
        String safe = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return safe.substring(0, Math.min(safe.length(), 128));
    }
}
