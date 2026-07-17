package com.aicodeassistant.llm;

import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

/** Atomic ownership lease between one request id, one cancellation signal and one provider call. */
public final class LlmCallRegistration {
    private LlmCallRegistration() { }

    public static <T> AutoCloseable register(ConcurrentMap<String, T> activeCalls, String requestId,
                                             T call, CancellationSignal signal, Consumer<T> cancel) {
        T previous = activeCalls.putIfAbsent(requestId, call);
        if (previous != null) throw new IllegalStateException("Duplicate LLM request id: " + requestId);
        CancellationSignal.Registration cancellation;
        try {
            cancellation = signal.register(() -> cancel.accept(call));
        } catch (RuntimeException registrationFailure) {
            activeCalls.remove(requestId, call);
            throw registrationFailure;
        }
        return () -> {
            cancellation.close();
            activeCalls.remove(requestId, call);
        };
    }

    public static <T> void cancelAll(ConcurrentMap<String, T> activeCalls, Consumer<T> cancel) {
        activeCalls.values().forEach(cancel);
        activeCalls.clear();
    }
}
