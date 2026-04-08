package com.aicodeassistant.bridge;

/**
 * 桥接轮询配置 — 控制 v1 协议下客户端的工作轮询行为。
 *
 * @param pollIntervalMs         轮询间隔（默认 1000ms）
 * @param heartbeatIntervalMs    心跳间隔（默认 30000ms）
 * @param maxReconnectAttempts   最大重连次数（默认 5）
 * @param reconnectBackoffBaseMs 重连退避基数（默认 2000ms）
 * @param reconnectBackoffMaxMs  重连退避上限（默认 30000ms）
 * @param sessionTimeoutMs       会话超时（默认 300000ms = 5 分钟）
 * @see <a href="SPEC §4.5.5">轮询配置</a>
 */
public record BridgePollConfig(
        long pollIntervalMs,
        long heartbeatIntervalMs,
        int maxReconnectAttempts,
        long reconnectBackoffBaseMs,
        long reconnectBackoffMaxMs,
        long sessionTimeoutMs
) {
    public static BridgePollConfig defaults() {
        return new BridgePollConfig(1000, 30000, 5, 2000, 30000, 300000);
    }
}
