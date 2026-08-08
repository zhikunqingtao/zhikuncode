package com.aicodeassistant.observability;

import org.slf4j.MDC;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Best-effort MDC scope that always restores the caller's context. */
public final class MdcScope implements AutoCloseable {
    private final Map<String, String> previous;
    private boolean closed;

    private MdcScope(Map<String, String> values) {
        Map<String, String> snapshot;
        try {
            Map<String, String> current = MDC.getCopyOfContextMap();
            snapshot = current == null ? Collections.emptyMap() : new LinkedHashMap<>(current);
            if (values != null) {
                values.forEach((key, value) -> {
                    try {
                        if (key != null && value != null && !value.isBlank()) MDC.put(key, value);
                    } catch (Throwable ignored) {
                        // Correlation must never affect the wrapped operation.
                    }
                });
            }
        } catch (Throwable ignored) {
            snapshot = Collections.emptyMap();
        }
        this.previous = snapshot;
    }

    public static MdcScope open(Map<String, String> values) {
        return new MdcScope(values);
    }

    public static Map<String, String> capture() {
        try {
            Map<String, String> current = MDC.getCopyOfContextMap();
            return current == null ? Collections.emptyMap() : Map.copyOf(current);
        } catch (Throwable ignored) {
            return Collections.emptyMap();
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            MDC.clear();
            if (!previous.isEmpty()) MDC.setContextMap(previous);
        } catch (Throwable ignored) {
            // Restoring diagnostic context must never mask a business exception.
        }
    }
}
