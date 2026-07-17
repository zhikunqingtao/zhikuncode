package com.aicodeassistant.run;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** Immutable authoritative Run snapshot. */
public record RunEnvelope(
        String id, String sessionId, String parentRunId, RunStatus status,
        String agentType, String model, String promptHash,
        Instant startedAt, Instant finishedAt, String abortReason,
        int totalTokens, double totalCostUsd, int toolCallCount, int turnCount,
        String errorSummary, Instant createdAt, Instant updatedAt,
        long version, RunExitReason exitReason, RunExitReason requestedExitReason,
        VerificationStatus verificationStatus, Instant terminalAt, String waitingReason
) {
    public enum RunStatus {
        QUEUED, RUNNING, WAITING_INTERACTION, CANCELLING,
        COMPLETED, FAILED, CANCELLED, INTERRUPTED;
        public String dbValue() { return name().toLowerCase(Locale.ROOT); }
        public static RunStatus fromDbValue(String value) {
            return valueOf(value.toUpperCase(Locale.ROOT));
        }
        public boolean terminal() {
            return this == COMPLETED || this == FAILED || this == CANCELLED || this == INTERRUPTED;
        }
    }

    public enum RunExitReason {
        MODEL_FINISHED, USER_CANCELLED, DEADLINE_EXCEEDED, INTERACTION_EXPIRED,
        TOOL_FAILURE, PROVIDER_FAILURE, INTERACTION_CAPACITY_EXCEEDED,
        PROCESS_TERMINATION_UNCONFIRMED, TOOL_TERMINATION_UNCONFIRMED,
        SERVICE_RESTART, INTERNAL_ERROR;
        public String dbValue() { return name().toLowerCase(Locale.ROOT); }
        public static RunExitReason fromDbValue(String value) {
            return value == null ? null : valueOf(value.toUpperCase(Locale.ROOT));
        }
    }

    public enum VerificationStatus {
        NOT_REQUESTED, PENDING, VERIFIED, UNVERIFIED, FAILED;
        public String dbValue() { return name().toLowerCase(Locale.ROOT); }
        public static VerificationStatus fromDbValue(String value) {
            return valueOf(value.toUpperCase(Locale.ROOT));
        }
    }

    public static RunEnvelope start(String sessionId, String parentRunId, String agentType, String model) {
        Instant now = Instant.now();
        return new RunEnvelope(UUID.randomUUID().toString(), sessionId, parentRunId,
                RunStatus.RUNNING, agentType, model, null, now, null, null,
                0, 0.0, 0, 0, null, now, now, 0, null, null,
                VerificationStatus.NOT_REQUESTED, null, null);
    }

}
