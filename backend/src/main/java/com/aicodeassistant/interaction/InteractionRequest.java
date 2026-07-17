package com.aicodeassistant.interaction;

import java.time.Instant;
import java.util.Locale;

public record InteractionRequest(
        String interactionId, String correlationKey, String sessionId, String runId,
        Type type, Status status, String promptJson, String allowedDecisionsJson,
        String scopeOptionsJson, String responseJson, Instant createdAt,
        Instant deliveryWindowEndsAt, Instant firstDispatchedAt, Instant deliveryAckDeadlineAt,
        Instant receivedAt, Instant decisionDeadlineAt, Instant decidedAt,
        String terminalReason, String source, String childSessionId,
        int deliveryGeneration, int dispatchAttempts, String lastTransportId, Instant updatedAt, long version) {
    public enum Type { PERMISSION, ELICITATION, PLAN_APPROVAL }
    public enum Status { PENDING, ANSWERED, DENIED, EXPIRED, CANCELLED, UNDELIVERABLE }
    public static String db(Enum<?> value) { return value.name().toLowerCase(Locale.ROOT); }
}
