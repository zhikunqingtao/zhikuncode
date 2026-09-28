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
        VerificationStatus verificationStatus, UsageStatus usageStatus,
        Instant terminalAt, String waitingReason
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
        SERVICE_RESTART, INCOMPLETE, INTERNAL_ERROR,
        TOKEN_BUDGET_EXHAUSTED, MAX_TURNS, UNKNOWN;
        public String dbValue() { return name().toLowerCase(Locale.ROOT); }
        public static RunExitReason fromDbValue(String value) {
            if (value == null) return null;
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unrecognized) {
                return UNKNOWN;
            }
        }
    }

    public enum VerificationStatus {
        NOT_REQUESTED, PENDING, VERIFIED, UNVERIFIED, FAILED, UNKNOWN;
        public String dbValue() { return name().toLowerCase(Locale.ROOT); }
        /** 未识别或缺失的DB值安全映射为UNKNOWN，不抛异常、不重写历史记录。 */
        public static VerificationStatus fromDbValue(String value) {
            if (value == null) return UNKNOWN;
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unrecognized) {
                return UNKNOWN;
            }
        }
    }

    /** 用量观测状态 — 只描述"已报告的部分"，unknown 表示未报告而非零消费。 */
    public enum UsageStatus {
        KNOWN, PARTIAL, UNKNOWN;
        public String dbValue() { return name().toLowerCase(Locale.ROOT); }
        public static UsageStatus fromDbValue(String value) {
            if (value == null) return UNKNOWN;
            try {
                return valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unrecognized) {
                return UNKNOWN;
            }
        }
    }

    /** 现有产物验证字段仅覆盖 artifact_manifest 清单；UNKNOWN 表示范围未知。 */
    @com.fasterxml.jackson.annotation.JsonProperty("verificationScope")
    public String verificationScope() {
        return verificationStatus == null || verificationStatus == VerificationStatus.UNKNOWN
                ? "unknown" : "artifact_manifest";
    }

    public static RunEnvelope start(String sessionId, String parentRunId, String agentType, String model) {
        Instant now = Instant.now();
        return new RunEnvelope(UUID.randomUUID().toString(), sessionId, parentRunId,
                RunStatus.RUNNING, agentType, model, null, now, null, null,
                0, 0.0, 0, 0, null, now, now, 0, null, null,
                VerificationStatus.NOT_REQUESTED, UsageStatus.UNKNOWN, null, null);
    }

}
