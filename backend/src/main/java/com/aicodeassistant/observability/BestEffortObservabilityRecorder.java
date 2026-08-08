package com.aicodeassistant.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Lossy supplemental-event writer. It is deliberately isolated from every business executor:
 * saturation, shutdown and storage failures are observable but never propagated to callers.
 */
@Service
public class BestEffortObservabilityRecorder {
    private static final Logger eventLog = LoggerFactory.getLogger("observability-events");
    static final int QUEUE_CAPACITY = 2048;
    private static final int MAX_FIELDS = 64;
    private static final int MAX_STRING_CHARS = 512;
    private static final Set<String> PLAINTEXT_KEYS = Set.of(
            "text", "prompt", "command", "body", "response", "content",
            "error", "message", "args", "input", "output", "detail", "summary",
            "systemprompt", "password", "apikey", "authorization", "jwt",
            "credential", "secret");

    private final EventSink sink;
    private final ThreadPoolExecutor executor;
    private final Counter dropped;
    private final Counter writeFailures;

    @Autowired
    public BestEffortObservabilityRecorder(MeterRegistry registry, ObjectMapper objectMapper) {
        this(registry, event -> {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("eventType", event.eventType());
            line.put("runId", event.runId());
            if (event.toolUseId() != null && !event.toolUseId().isBlank()) {
                line.put("toolUseId", event.toolUseId());
            }
            line.put("data", event.payload());
            eventLog.info("{}", objectMapper.writeValueAsString(line));
        });
    }

    BestEffortObservabilityRecorder(MeterRegistry registry, EventSink sink) {
        this.sink = sink;
        this.dropped = registerCounter(registry, "zhiku.observability.events.dropped",
                "Supplemental observability events dropped before persistence");
        this.writeFailures = registerCounter(registry, "zhiku.observability.events.write_failures",
                "Supplemental observability event persistence failures");
        this.executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "observability-event-writer");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        try {
            Gauge.builder("zhiku.observability.events.queue.depth", executor,
                            value -> value.getQueue().size())
                    .description("Queued supplemental observability events")
                    .register(registry);
        } catch (Throwable ignored) {
            // Metrics registration is diagnostic-only and must not prevent startup.
        }
    }

    public boolean record(String runId, String eventType, String toolUseId, Object data) {
        try {
            if (runId == null || runId.isBlank() || eventType == null || eventType.isBlank()) {
                dropped.increment();
                return false;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("schemaVersion", 1);
            payload.put("occurredAt", Instant.now().toString());
            payload.put("runId", runId);
            String sessionId = safeMdc("sessionId");
            if (sessionId != null) payload.put("sessionId", sessionId);
            if (toolUseId != null && !toolUseId.isBlank()) payload.put("toolUseId", toolUseId);
            if (data instanceof Map<?, ?> map) copyMap(map, payload);
            else if (data != null) payload.put("value", normalize(data));

            executor.execute(() -> persist(runId, eventType, toolUseId, Map.copyOf(payload)));
            return true;
        } catch (Throwable ignored) {
            safeIncrement(dropped);
            return false;
        }
    }

    private void persist(String runId, String eventType, String toolUseId, Map<String, Object> payload) {
        try {
            sink.write(new QueuedEvent(runId, eventType, toolUseId, payload));
        } catch (Throwable ignored) {
            safeIncrement(writeFailures);
        }
    }

    private static void copyMap(Map<?, ?> source, Map<String, Object> target) {
        int count = 0;
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (count++ >= MAX_FIELDS) break;
            if (entry.getKey() == null) continue;
            String key = String.valueOf(entry.getKey());
            if (key.length() > 80 || target.containsKey(key)) continue;
            if (PLAINTEXT_KEYS.contains(key.toLowerCase())) {
                String rendered = entry.getValue() == null ? null : String.valueOf(entry.getValue());
                target.put(key + "Length", SafeLogValue.length(rendered));
                target.put(key + "Fingerprint", SafeLogValue.fingerprint(rendered));
            } else {
                target.put(key, normalize(entry.getValue()));
            }
        }
    }

    private static Object normalize(Object value) {
        if (value == null) return "unknown";
        if (value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof Enum<?> enumeration) return enumeration.name();
        if (value instanceof CharSequence chars) {
            return chars.length() <= MAX_STRING_CHARS
                    ? chars.toString() : chars.subSequence(0, MAX_STRING_CHARS).toString();
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> copy = new ArrayList<>();
            for (Object item : iterable) {
                if (copy.size() >= 32) break;
                copy.add(normalize(item));
            }
            return List.copyOf(copy);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            copyMap(map, copy);
            return Map.copyOf(copy);
        }
        return value.getClass().getSimpleName();
    }

    private static String safeMdc(String key) {
        try { return MDC.get(key); }
        catch (Throwable ignored) { return null; }
    }

    private static void safeIncrement(Counter counter) {
        try { counter.increment(); }
        catch (Throwable ignored) { }
    }

    private static Counter registerCounter(MeterRegistry registry, String name, String description) {
        try {
            return Counter.builder(name).description(description).register(registry);
        } catch (Throwable ignored) {
            return Counter.builder(name).description(description).register(new SimpleMeterRegistry());
        }
    }

    int queueSize() { return executor.getQueue().size(); }
    boolean isShutdown() { return executor.isShutdown(); }

    @PreDestroy
    void shutdown() {
        try {
            int discarded = executor.shutdownNow().size();
            for (int i = 0; i < discarded; i++) safeIncrement(dropped);
        }
        catch (Throwable ignored) { }
    }

    @FunctionalInterface
    interface EventSink {
        void write(QueuedEvent event) throws Exception;
    }

    record QueuedEvent(String runId, String eventType, String toolUseId,
                       Map<String, Object> payload) { }
}
