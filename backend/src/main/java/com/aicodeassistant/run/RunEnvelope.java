package com.aicodeassistant.run;

import java.time.Instant;
import java.util.UUID;

/**
 * 运行信封 — 记录一次 LLM 交互的完整生命周期。
 * <p>
 * 不可变 record，状态转换通过工厂方法返回新实例。
 */
public record RunEnvelope(
        String id,
        String sessionId,
        String parentRunId,
        RunStatus status,
        String agentType,
        String model,
        String promptHash,
        Instant startedAt,
        Instant finishedAt,
        String abortReason,
        int totalTokens,
        double totalCostUsd,
        int toolCallCount,
        int turnCount,
        String errorSummary,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * 运行状态枚举。
     */
    public enum RunStatus {
        RUNNING, COMPLETED, TIMEOUT, ABORTED, ERROR;

        /** 数据库存储值 — 小写 */
        public String dbValue() {
            return name().toLowerCase();
        }

        /** 从数据库值解析 */
        public static RunStatus fromDbValue(String value) {
            return valueOf(value.toUpperCase());
        }
    }

    /**
     * 启动一次新运行。
     */
    public static RunEnvelope start(String sessionId, String parentRunId, String agentType, String model) {
        Instant now = Instant.now();
        return new RunEnvelope(
                UUID.randomUUID().toString(),
                sessionId,
                parentRunId,
                RunStatus.RUNNING,
                agentType,
                model,
                null,
                now,        // startedAt
                null,       // finishedAt
                null,       // abortReason
                0, 0.0, 0, 0,
                null,       // errorSummary
                now,        // createdAt
                now         // updatedAt
        );
    }

    /**
     * 完成运行 — 附带统计数据。
     */
    public RunEnvelope complete(int totalTokens, double totalCostUsd, int toolCallCount, int turnCount) {
        return new RunEnvelope(
                id, sessionId, parentRunId,
                RunStatus.COMPLETED,
                agentType, model, promptHash,
                startedAt, Instant.now(),
                abortReason,
                totalTokens, totalCostUsd, toolCallCount, turnCount,
                errorSummary,
                createdAt, Instant.now()
        );
    }

    /**
     * 运行失败。
     */
    public RunEnvelope fail(String errorSummary) {
        return new RunEnvelope(
                id, sessionId, parentRunId,
                RunStatus.ERROR,
                agentType, model, promptHash,
                startedAt, Instant.now(),
                abortReason,
                totalTokens, totalCostUsd, toolCallCount, turnCount,
                errorSummary,
                createdAt, Instant.now()
        );
    }

    /**
     * 中止运行。
     */
    public RunEnvelope abort(String reason) {
        return new RunEnvelope(
                id, sessionId, parentRunId,
                RunStatus.ABORTED,
                agentType, model, promptHash,
                startedAt, Instant.now(),
                reason,
                totalTokens, totalCostUsd, toolCallCount, turnCount,
                errorSummary,
                createdAt, Instant.now()
        );
    }
}
