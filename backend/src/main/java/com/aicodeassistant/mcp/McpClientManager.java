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
import org.springframework.core.env.Environment;

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
    private final McpConfigurationResolver configurationResolver;
    private final ToolRegistry toolRegistry;
    private final McpApprovalService approvalService;
    private final McpCapabilityRegistryService registryService;
    private final Environment environment;
    private final Map<String, McpServerConnection> connections = new ConcurrentHashMap<>();
    private volatile boolean running = false;

    public McpClientManager(McpConfiguration mcpConfiguration,
                            McpConfigurationResolver configurationResolver,
                            @Lazy ToolRegistry toolRegistry,
                            McpApprovalService approvalService,
                            @Lazy McpCapabilityRegistryService registryService,
                            Environment environment) {
        this.mcpConfiguration = mcpConfiguration;
        this.configurationResolver = configurationResolver;
        this.toolRegistry = toolRegistry;
        this.approvalService = approvalService;
        this.registryService = registryService;
        this.environment = environment;
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
        // 1. 从多来源解析器加载合并配置（LOCAL > USER > ENTERPRISE > ENV）
        //    ★ 来自配置文件的服务器自动信任
        List<McpServerConfig> resolvedConfigs = configurationResolver.resolveAll();
        for (McpServerConfig config : resolvedConfigs) {
            if (!approvalService.isTrusted(config)) {
                approvalService.recordApproval(config, config.scope() != null ? config.scope().name() : "LOCAL");
                log.info("Auto-trusted config-file MCP server: {} (scope={})", config.name(), config.scope());
            }
            addServer(config);
        }

        // 2. 从 application.yml 加载配置（补充未被多来源覆盖的服务器）
        //    ★ application.yml 中配置的服务器自动信任 — 来自应用配置文件的服务器无需交互式审批
        List<McpServerConfig> appConfigs = mcpConfiguration.toMcpServerConfigs();
        for (McpServerConfig config : appConfigs) {
            if (!connections.containsKey(config.name())) {
                // 自动信任 application.yml 配置的服务器
                if (!approvalService.isTrusted(config)) {
                    approvalService.recordApproval(config, "APPLICATION_CONFIG");
                    log.info("Auto-trusted application.yml MCP server: {}", config.name());
                }
                addServer(config);
            }
        }

        log.info("MCP initialization complete — {} connections ({} from resolver, {} from application.yml)",
                connections.size(), resolvedConfigs.size(), appConfigs.size());

        // 3. 从能力注册表加载已启用的工具定义，自动创建连接
        if (registryService != null && registryService.size() > 0) {
            List<McpCapabilityDefinition> enabledCaps = registryService.listEnabled();
            int registryConnections = 0;
            for (McpCapabilityDefinition cap : enabledCaps) {
                String serverKey = cap.extractServerKey();
                if (!connections.containsKey(serverKey)) {
                    try {
                        enableFromRegistry(cap);
                        registryConnections++;
                    } catch (Exception e) {
                        log.warn("Failed to enable registry capability '{}': {}", cap.id(), e.getMessage());
                    }
                }
            }
            log.info("MCP registry: activated {} capabilities from {} enabled entries",
                    registryConnections, enabledCaps.size());
        }
    }

    /** 动态添加 MCP 服务器 — 含信任检查 (§11.2.2) */
    public McpServerConnection addServer(McpServerConfig config) {
        // 运行时添加的服务器，若来源为可信配置文件则自动信任
        if (!approvalService.isTrusted(config) && config.scope() != null) {
            // scope 非空表示来自配置文件解析（LOCAL/USER/ENTERPRISE），而非手动添加
            approvalService.recordApproval(config,
                    config.scope().name() + "_RUNTIME");
            log.info("Auto-trusted runtime MCP server: {} (scope={})",
                    config.name(), config.scope());
        }

        // 原有信任检查逻辑保留
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
     * 发现并注册所有已连接 MCP 服务器的 prompt 模板为 slash 命令。
     *
     * @return 所有发现的 prompt 适配器列表
     */
    public List<McpPromptAdapter> discoverPrompts() {
        List<McpPromptAdapter> adapters = new ArrayList<>();
        for (McpServerConnection conn : getConnectedServers()) {
            List<McpServerConnection.McpPromptDefinition> prompts = conn.listPrompts();
            for (McpServerConnection.McpPromptDefinition prompt : prompts) {
                McpPromptAdapter adapter = new McpPromptAdapter(
                        conn.getName(), prompt, conn);
                adapters.add(adapter);
            }
        }
        log.info("Discovered {} MCP prompts across {} servers",
                adapters.size(), getConnectedServers().size());
        return adapters;
    }

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
                .map(mcpTool -> {
                    String enhancedDesc = null;
                    long customTimeout = 0;
                    if (registryService != null) {
                        var capOpt = registryService.findByToolName(
                                connection.getName(), mcpTool.name());
                        if (capOpt.isPresent()) {
                            enhancedDesc = capOpt.get().description();
                            customTimeout = capOpt.get().timeoutMs();
                        }
                    }
                    return (Tool) new McpToolAdapter(
                            "mcp__" + connection.getName() + "__" + mcpTool.name(),
                            mcpTool.description(), mcpTool.inputSchema(),
                            connection, mcpTool.name(),
                            enhancedDesc, customTimeout);
                })
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
            String enhancedDesc = null;
            long customTimeout = 0;
            if (registryService != null) {
                var capOpt = registryService.findByToolName(conn.getName(), mcpTool.name());
                if (capOpt.isPresent()) {
                    McpCapabilityDefinition cap = capOpt.get();
                    enhancedDesc = cap.description();
                    customTimeout = cap.timeoutMs();
                }
            }
            McpToolAdapter adapter = new McpToolAdapter(
                    "mcp__" + conn.getName() + "__" + mcpTool.name(),
                    mcpTool.description(), mcpTool.inputSchema(),
                    conn, mcpTool.name(),
                    enhancedDesc, customTimeout);
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

    // ===== 注册表集成 =====

    /**
     * 从能力注册表启用工具 — 自动构建 McpServerConfig 并创建连接。
     */
    public McpServerConnection enableFromRegistry(McpCapabilityDefinition def) {
        McpServerConfig config = buildConfigFromRegistry(def);

        log.info("Enabling MCP capability '{}' \u2192 server '{}'", def.id(), config.name());

        // 注册表工具自动信任 — 跳过交互式审批
        if (!approvalService.isTrusted(config)) {
            approvalService.recordApproval(config, "REGISTRY");
            log.info("Auto-trusted registry capability: {}", def.id());
        }

        return addServer(config);
    }

    /** 从注册表定义构建 McpServerConfig — 供 enableFromRegistry 和 testCapability 共用 */
    public McpServerConfig buildConfigFromRegistry(McpCapabilityDefinition def) {
        String serverKey = def.extractServerKey();
        String apiKey = null;
        if (def.apiKeyConfig() != null) {
            apiKey = environment.getProperty(def.apiKeyConfig());
        }
        if (apiKey == null || apiKey.isEmpty()) {
            apiKey = def.apiKeyDefault();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        if (apiKey != null && !apiKey.isEmpty()) {
            headers.put("Authorization", "Bearer " + apiKey);
        }
        return new McpServerConfig(
                serverKey, McpTransportType.SSE,
                null, List.of(), Map.of(),
                def.sseUrl(), headers, McpConfigScope.DYNAMIC);
    }
}
