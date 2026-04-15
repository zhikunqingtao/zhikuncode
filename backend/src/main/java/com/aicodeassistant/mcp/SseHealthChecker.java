package com.aicodeassistant.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ERR-3 fix: SSE 连接健康检查器 — 周期性主动 ping 检测死连接。
 * <p>
 * 通过 MCP 标准 notifications/ping 消息探测连接活性，
 * 发送失败时触发重连流程。
 * <p>
 * 与 {@link McpClientManager#healthCheck()} 的职责区分：
 * <ul>
 *   <li>healthCheck(): 被动检查 — 通过 isAlive() 标志位检测 FAILED 状态并重连</li>
 *   <li>SseHealthChecker: 主动探测 — 发送 ping 检测 CONNECTED 但实际已断开的连接</li>
 * </ul>
 */
@Component
public class SseHealthChecker {

    private static final Logger log = LoggerFactory.getLogger(SseHealthChecker.class);

    private final McpClientManager mcpClientManager;

    public SseHealthChecker(McpClientManager mcpClientManager) {
        this.mcpClientManager = mcpClientManager;
    }

    /**
     * 每 30s 对所有已连接的 MCP SSE 连接执行主动探测。
     */
    @Scheduled(fixedRate = 30_000, initialDelay = 30_000)
    public void performActiveHealthCheck() {
        for (McpServerConnection connection : mcpClientManager.listConnections()) {
            if (connection.getStatus() != McpConnectionStatus.CONNECTED) {
                continue;
            }
            // ★ ERR-3 修复: 使用返回 boolean 的 sendHealthPing() 替代会吞异常的 sendNotification()
            if (!connection.sendHealthPing()) {
                log.warn("Active ping failed for '{}', marking as DEGRADED",
                        connection.getName());
                connection.setStatus(McpConnectionStatus.DEGRADED);
                mcpClientManager.scheduleReconnect(connection.getName());
            }
        }
    }
}
