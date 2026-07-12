package com.aicodeassistant.tool.agent;

import java.time.Instant;
import java.util.UUID;

/**
 * 子代理检查点数据记录 — 保存代理执行过程中的状态快照。
 * <p>
 * 用于超时恢复和部分结果提取。
 */
public record AgentCheckpoint(
    String id,
    String runId,
    String sessionId,
    String agentId,
    int seq,
    String messagesJson,
    String fileStateJson,
    int toolCallCount,
    int turnCount,
    long tokensConsumed,
    String workingDir,
    Instant createdAt
) {
    /**
     * 工厂方法 — 自动生成 id 和 createdAt。
     */
    public static AgentCheckpoint create(String runId, String sessionId, String agentId,
                                         int seq, String messagesJson, String fileStateJson,
                                         int toolCallCount, int turnCount, long tokensConsumed,
                                         String workingDir) {
        return new AgentCheckpoint(
            UUID.randomUUID().toString(), runId, sessionId, agentId,
            seq, messagesJson, fileStateJson, toolCallCount, turnCount, tokensConsumed,
            workingDir, Instant.now());
    }
}
