package com.aicodeassistant.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    // ★ 新增健康指标字段
    private final Map<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastSuccessfulPing = new ConcurrentHashMap<>();

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
            String name = connection.getName();
            try {
                boolean alive = connection.sendHealthPing();
                if (alive) {
                    // ★ ping 成功时重置计数器
                    consecutiveFailures.put(name, 0);
                    lastSuccessfulPing.put(name, Instant.now());
                } else {
                    // ★ 记录连续失败次数
                    int failures = consecutiveFailures.merge(name, 1, Integer::sum);
                    log.warn("Health ping failed for '{}', consecutive failures: {}", name, failures);
                    if (failures >= 2) {
                        connection.setStatus(McpConnectionStatus.DEGRADED);
                        mcpClientManager.scheduleReconnect(name);
                    }
                }
            } catch (Exception e) {
                // ★ 异常也计入连续失败
                int failures = consecutiveFailures.merge(name, 1, Integer::sum);
                log.warn("Health ping exception for '{}' (failures={}): {}", name, failures, e.getMessage());
                if (failures >= 2) {
                    connection.setStatus(McpConnectionStatus.DEGRADED);
                    mcpClientManager.scheduleReconnect(name);
                }
            }
        }
    }

    // ───── Getter 方法 ─────

    /**
     * 获取指定服务器的连续失败次数。
     */
    public int getConsecutiveFailures(String serverName) {
        return consecutiveFailures.getOrDefault(serverName, 0);
    }

    /**
     * 获取指定服务器的最后成功 ping 时间。
     */
    public Instant getLastSuccessfulPing(String serverName) {
        return lastSuccessfulPing.get(serverName);
    }
}
