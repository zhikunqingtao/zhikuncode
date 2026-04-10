package com.aicodeassistant.mcp;

import com.aicodeassistant.mcp.McpServerConnection.McpToolDefinition;
import com.aicodeassistant.tool.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * MCP 客户端管理器 — 管理所有 MCP 服务器连接的生命周期。
 * <p>
 * 实现 SmartLifecycle，在 Spring 容器启动/停止时自动管理连接。
 * Phase = 2: 在 Python 服务 (1) 之后、FeatureFlagService (3) 之前。
 * <p>
 * 重连策略:
 * <ul>
 *   <li>最大重试次数: 5</li>
 *   <li>指数退避: 1s, 2s, 4s, 8s, 16s (上限 30s)</li>
 *   <li>stdio 不重连（本地进程终止 = 不可恢复）</li>
 * </ul>
 *
 * @see <a href="SPEC §4.3.3">MCP 客户端管理</a>
 */
@Service
public class McpClientManager implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(McpClientManager.class);

    static final int MAX_RECONNECT_ATTEMPTS = 5;
    static final long INITIAL_BACKOFF_MS = 1000;
    static final long MAX_BACKOFF_MS = 30_000;

    private final McpConfiguration mcpConfiguration;
    private final ToolRegistry toolRegistry;
    private final McpApprovalService approvalService;
    private final Map<String, McpServerConnection> connections = new ConcurrentHashMap<>();
    private volatile boolean running = false;

    public McpClientManager(McpConfiguration mcpConfiguration, @Lazy ToolRegistry toolRegistry,
                            McpApprovalService approvalService) {
        this.mcpConfiguration = mcpConfiguration;
        this.toolRegistry = toolRegistry;
        this.approvalService = approvalService;
    }

    // ===== SmartLifecycle =====

    @Override
    public void start() {
        log.info("McpClientManager starting — initializing MCP connections");
        initializeAll();
        running = true;
    }

    @Override
    public void stop() {
        log.info("McpClientManager stopping — closing all MCP connections");
        shutdown();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return 2; // @Order(2)
    }

    // ===== 连接管理 =====

    /** 从配置源加载并连接所有 MCP 服务器 */
    public void initializeAll() {
        // 从 application.yml 加载配置
        List<McpServerConfig> configs = mcpConfiguration.toMcpServerConfigs();
        for (McpServerConfig config : configs) {
            addServer(config);
        }
        log.info("MCP initialization complete — {} connections from config", connections.size());
    }

    /** 动态添加 MCP 服务器 — 含信任检查 (§11.2.2) */
    public McpServerConnection addServer(McpServerConfig config) {
        // ★ 信任检查: 未信任的服务器设置为 PENDING_APPROVAL
        if (!approvalService.isTrusted(config)) {
            log.info("MCP server not trusted, pending approval: {}", config.name());
            McpServerConnection conn = new McpServerConnection(config);
            conn.setStatus(McpConnectionStatus.NEEDS_AUTH);
            connections.put(config.name(), conn);
            return conn;
        }

        McpServerConnection conn = new McpServerConnection(config);
        try {
            conn.connect();
            connections.put(config.name(), conn);
            registerToolsFromConnection(conn);
            log.info("MCP server connected: {}", config.name());
        } catch (Exception e) {
            log.error("Failed to connect MCP server: {}", config.name(), e);
            conn.setStatus(McpConnectionStatus.FAILED);
            connections.put(config.name(), conn);
        }
        return conn;
    }

    /** 移除 MCP 服务器 */
    public boolean removeServer(String name) {
        McpServerConnection conn = connections.remove(name);
        if (conn != null) {
            toolRegistry.unregisterByPrefix("mcp__" + name + "__");
            conn.close();
            log.info("MCP server removed: {}", name);
            return true;
        }
        return false;
    }

    /** 获取指定服务器连接 */
    public Optional<McpServerConnection> getConnection(String name) {
        return Optional.ofNullable(connections.get(name));
    }

    /** 获取所有连接 */
    public List<McpServerConnection> listConnections() {
        return List.copyOf(connections.values());
    }

    /** 获取所有已连接的服务器 */
    public List<McpServerConnection> getConnectedServers() {
        return connections.values().stream()
                .filter(c -> c.getStatus() == McpConnectionStatus.CONNECTED)
                .toList();
    }

    // ===== 工具发现 =====

    /**
     * 重启指定 MCP 服务器。
     */
    public void restartServer(String name) {
        McpServerConnection conn = connections.get(name);
        if (conn == null) {
            throw new IllegalArgumentException("MCP server not found: " + name);
        }
        log.info("Restarting MCP server: {}", name);
        conn.close();
        try {
            conn.connect();
            conn.resetReconnectAttempts();
            log.info("MCP server restarted: {}", name);
        } catch (Exception e) {
            log.error("Failed to restart MCP server: {}", name, e);
            conn.setStatus(McpConnectionStatus.FAILED);
        }
    }

    /**
     * 获取 MCP 服务器日志。
     * P0 占位实现 — 返回基本状态信息。
     */
    public List<String> getServerLogs(String name, int lines) {
        McpServerConnection conn = connections.get(name);
        if (conn == null) {
            return List.of("MCP server not found: " + name);
        }
        // P0: 返回基本状态信息，后续可对接实际日志收集
        return List.of(
                "Server: " + name,
                "Status: " + conn.getStatus(),
                "Transport: " + conn.getConfig().type(),
                "Tools: " + conn.getTools().size()
        );
    }

    /**
     * 发现并包装所有已连接 MCP 服务器的工具为内部 Tool 接口。
     */
    public List<Tool> discoverAndWrapTools() {
        return connections.values().stream()
                .filter(c -> c.getStatus() == McpConnectionStatus.CONNECTED)
                .flatMap(c -> wrapMcpTools(c).stream())
                .toList();
    }

    /** 将 MCP 服务器工具包装为内部 Tool 接口 */
    private List<Tool> wrapMcpTools(McpServerConnection connection) {
        return connection.getTools().stream()
                .map(mcpTool -> (Tool) new McpToolAdapter(
                        "mcp__" + connection.getName() + "__" + mcpTool.name(),
                        mcpTool.description(),
                        mcpTool.inputSchema(),
                        connection,
                        mcpTool.name()))
                .toList();
    }

    // ===== 重连 =====

    /**
     * 断线重连 — 对 FAILED/PENDING 状态的服务器执行重试。
     * stdio 类型不重连（本地进程终止 = 不可恢复）。
     */
    public void reconnectFailed() {
        connections.forEach((name, conn) -> {
            if (conn.getStatus() != McpConnectionStatus.FAILED
                    && conn.getStatus() != McpConnectionStatus.PENDING) {
                return;
            }
            // stdio 不重连
            if (conn.getConfig().type() == McpTransportType.STDIO) {
                return;
            }
            if (conn.getReconnectAttempts() >= MAX_RECONNECT_ATTEMPTS) {
                return;
            }

            conn.incrementReconnectAttempts();
            long delay = calculateBackoff(conn.getReconnectAttempts());

            try {
                Thread.sleep(delay);
                conn.connect();
                conn.resetReconnectAttempts();
                log.info("MCP server reconnected: {}", name);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("MCP reconnect failed for {} (attempt {}): {}",
                        name, conn.getReconnectAttempts(), e.getMessage());
            }
        });
    }

    /** 计算指数退避延迟 */
    static long calculateBackoff(int attempt) {
        return Math.min(INITIAL_BACKOFF_MS * (1L << (attempt - 1)), MAX_BACKOFF_MS);
    }

    // ===== 关闭 =====

    /** 优雅关闭所有 MCP 连接 */
    public void shutdown() {
        connections.values().forEach(conn -> {
            try {
                conn.close();
            } catch (Exception e) {
                log.warn("Error closing MCP connection: {}", e.getMessage());
            }
        });
        connections.clear();
    }

    // ===== 工具动态注册与权限控制 =====

    /**
     * 从已连接的 MCP 服务器注册工具 — 对齐原版 registerMcpTools。
     * 包括：工具发现、包装为 McpToolAdapter、权限控制、变更通知监听。
     */
    private void registerToolsFromConnection(McpServerConnection conn) {
        for (McpToolDefinition mcpTool : conn.getTools()) {
            // 权限检查
            if (!isToolAllowed(conn.getName(), mcpTool.name())) {
                log.info("MCP tool {}:{} blocked by channel permissions",
                        conn.getName(), mcpTool.name());
                continue;
            }
            McpToolAdapter adapter = new McpToolAdapter(
                    "mcp__" + conn.getName() + "__" + mcpTool.name(),
                    mcpTool.description(), mcpTool.inputSchema(),
                    conn, mcpTool.name());
            toolRegistry.registerDynamic(adapter);
        }

        // 监听工具变更通知 — 自动重新注册
        conn.onToolsChanged(() -> {
            toolRegistry.unregisterByPrefix("mcp__" + conn.getName() + "__");
            registerToolsFromConnection(conn);
            log.info("MCP tools refreshed for server: {}", conn.getName());
        });
    }

    /**
     * 检查 MCP 工具是否被允许 — 对齐原版 channelPermissions。
     */
    private boolean isToolAllowed(String serverName, String toolName) {
        Map<String, List<String>> permissions = mcpConfiguration.getChannelPermissions();
        if (permissions == null || permissions.isEmpty()) return true;
        List<String> blocked = permissions.getOrDefault(serverName, List.of());
        return !blocked.contains(toolName) && !blocked.contains("*");
    }

    // ===== 健康检查 + 指数退避重连 =====

    /**
     * 健康检查 + 自动重连 — 对齐原版重连策略。
     */
    @Scheduled(fixedDelay = 30000)
    public void healthCheck() {
        if (!running) return;
        connections.forEach((id, conn) -> {
            if (conn.getStatus() == McpConnectionStatus.CONNECTED && !conn.isAlive()) {
                log.warn("MCP server {} connection lost", id);
                conn.setStatus(McpConnectionStatus.FAILED);
            }
            if (conn.getStatus() == McpConnectionStatus.FAILED
                    && conn.getConfig().type() != McpTransportType.STDIO) {
                attemptReconnect(id, conn);
            }
        });
    }

    private void attemptReconnect(String serverId, McpServerConnection conn) {
        int attempt = conn.getReconnectAttempts();
        if (attempt >= MAX_RECONNECT_ATTEMPTS) {
            log.error("MCP server {} exceeded max reconnect attempts ({})",
                    serverId, MAX_RECONNECT_ATTEMPTS);
            return;
        }
        long backoffMs = calculateBackoff(attempt + 1);
        log.info("Reconnecting MCP server {} in {}ms (attempt {}/{})",
                serverId, backoffMs, attempt + 1, MAX_RECONNECT_ATTEMPTS);

        try {
            Thread.sleep(backoffMs);
            conn.connect();
            conn.resetReconnectAttempts();
            registerToolsFromConnection(conn);
            log.info("MCP server {} reconnected successfully", serverId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            conn.incrementReconnectAttempts();
            log.warn("Reconnect failed for {}: {}", serverId, e.getMessage());
        }
    }

    /** 连接数量（测试用） */
    int connectionCount() {
        return connections.size();
    }
}
