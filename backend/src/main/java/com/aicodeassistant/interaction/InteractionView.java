package com.aicodeassistant.interaction;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Stable protocol DTO shared by WebSocket delivery and REST recovery. */
public record InteractionView(
        int protocolVersion,
        String interactionId,
        String correlationKey,
        String sessionId,
        String runId,
        String interactionType,
        String status,
        Map<String, Object> prompt,
        List<String> allowedDecisions,
        List<String> scopeOptions,
        Object response,
        String source,
        String childSessionId,
        int deliveryGeneration,
        int dispatchAttempts,
        Instant createdAt,
        Instant receivedAt,
        Instant decisionDeadlineAt,
        Instant deliveryWindowEndsAt,
        Instant decidedAt,
        String terminalReason,
        long version,
        long serverNow) {
}
