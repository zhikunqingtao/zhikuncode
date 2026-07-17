package com.aicodeassistant.llm;

import java.util.Objects;
import java.util.UUID;

/** Identity and cancellation authority for one provider HTTP call. */
public record LlmCallContext(String requestId, CancellationSignal cancellation) {
    public LlmCallContext {
        if (requestId == null || requestId.isBlank()) throw new IllegalArgumentException("requestId is required");
        cancellation = Objects.requireNonNull(cancellation, "cancellation");
    }

    public static LlmCallContext unscoped() {
        return new LlmCallContext("unscoped-" + UUID.randomUUID(), CancellationSignal.none());
    }
}
