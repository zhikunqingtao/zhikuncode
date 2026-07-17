package com.aicodeassistant.mcp;

import com.aicodeassistant.engine.AbortContext;
import com.aicodeassistant.engine.QueryEngine;
import com.aicodeassistant.mcp.McpServerConnection.McpToolDefinition;
import com.aicodeassistant.mcp.progress.McpProgressTracker;
import com.aicodeassistant.mcp.roots.McpRootsProvider;
import com.aicodeassistant.mcp.roots.WorkspaceChangedEvent;
import com.aicodeassistant.mcp.schema.SchemaCompressor;
import com.aicodeassistant.tool.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.aicodeassistant.websocket.WebSocketSessionManager;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
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
    private final SimpMessagingTemplate messaging;
    private final WebSocketSessionManager wsSessionManager;
    private final SchemaCompressor schemaCompressor;
    private final McpRootsProvider rootsProvider;
    private final McpProgressTracker progressTracker;
    private final QueryEngine queryEngine; // 用于 M4 AbortContext 查找（@Lazy 避免循环依赖）
    private final Map<String, McpServerConnection> connections = new ConcurrentHashMap<>();
    private volatile boolean running = false;

    // ★ 自定义重连线程池（避免占用公共 ForkJoinPool）
    private final ExecutorService reconnectPool =
        Executors.newFixedThreadPool(2,
            r -> { Thread t = new Thread(r, "mcp-reconnect"); t.setDaemon(true); return t; });

    // ★ 异步延迟重连调度器（替代 healthCheck 中的阻塞 Thread.sleep）
    private final ScheduledExecutorService reconnectScheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> { Thread t = new Thread(r, "mcp-reconnect-scheduler"); t.setDaemon(true); return t; });

    // ★ 新增：幂等重连保护（原子操作，不使用 synchronized）
    private final ConcurrentHashMap<String, McpServerConnection> reconnectingServers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ScheduledReconnect> scheduledReconnects =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ActiveReconnect> activeReconnects =
            new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> connectionGenerations =
            new ConcurrentHashMap<>();

    public McpClientManager(McpConfiguration mcpConfiguration,
                            McpConfigurationResolver configurationResolver,
                            @Lazy ToolRegistry toolRegistry,
                            McpApprovalService approvalService,
                            @Lazy McpCapabilityRegistryService registryService,
                            Environment environment,
                            SimpMessagingTemplate messaging,
                            WebSocketSessionManager wsSessionManager,
                            SchemaCompressor schemaCompressor,
                            McpRootsProvider rootsProvider,
                            McpProgressTracker progressTracker,
                            @Lazy QueryEngine queryEngine) {
        this.mcpConfiguration = mcpConfiguration;
        this.configurationResolver = configurationResolver;
        this.toolRegistry = toolRegistry;
        this.approvalService = approvalService;
        this.registryService = registryService;
        this.environment = environment;
        this.messaging = messaging;
        this.wsSessionManager = wsSessionManager;
        this.schemaCompressor = schemaCompressor;
        this.rootsProvider = rootsProvider;
        this.progressTracker = progressTracker;
        this.queryEngine = queryEngine;
    }

    /** M4 AbortContext 查找函数 — 供 McpToolAdapter 注册取消回调。 */
    private java.util.function.Function<String, AbortContext> abortContextLookup() {
        return sessionId -> {
            try {
                return queryEngine != null ? queryEngine.getAbortContext(sessionId) : null;
            } catch (Exception e) {
                return null;
            }
        };
    }

    // ===== SmartLifecycle =====

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (reconnectPool.isShutdown() || reconnectScheduler.isShutdown()) {
            throw new IllegalStateException("MCP_CLIENT_MANAGER_CANNOT_RESTART_AFTER_SHUTDOWN");
        }
        log.info("McpClientManager starting — initializing MCP connections");
        running = true;
        initializeDefaultRoots();
        initializeAll();
    }

    @Override
    public void stop() {
        log.info("McpClientManager stopping — closing all MCP connections");
        shutdown();
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
        requireRunning();
        long generation = nextGeneration(config.name());
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
            conn.setRootsProvider(rootsProvider);
            conn.setProgressTracker(progressTracker);
            conn.setStatus(McpConnectionStatus.NEEDS_AUTH);
            installConnection(config.name(), generation, conn);
            return conn;
        }

        McpServerConnection conn = new McpServerConnection(config);
        conn.setRootsProvider(rootsProvider);
        conn.setProgressTracker(progressTracker);
        try {
            conn.connect();
            installConnection(config.name(), generation, conn);
            if (conn.getStatus() == McpConnectionStatus.CONNECTED) {
                registerToolsFromConnection(conn);
                log.info("MCP server connected: {}", config.name());
            } else {
                log.warn("MCP server did not reach CONNECTED state: {} (status={})",
                        config.name(), conn.getStatus());
            }
        } catch (Exception e) {
            log.debug("Failed to connect MCP server: {}", config.name(), e);
            conn.setStatus(McpConnectionStatus.FAILED);
            installConnection(config.name(), generation, conn);
        }
        return conn;
    }

    /** 移除 MCP 服务器 */
    public boolean removeServer(String name) {
        nextGeneration(name);
        McpServerConnection conn = connections.remove(name);
        cancelReconnectWork(name);
        if (conn != null) {
            reconnectingServers.remove(name, conn);
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
        requireRunning();
        McpServerConnection conn = connections.get(name);
        if (conn == null) {
            throw new IllegalArgumentException("MCP server not found: " + name);
        }
        long generation = nextGeneration(name);
        cancelReconnectWork(name);
        log.info("Restarting MCP server: {}", name);
        toolRegistry.unregisterByPrefix("mcp__" + name + "__");
        conn.close();
        try {
            conn.connect();
            if (!isCurrentConnection(name, conn, generation)) {
                closeQuietly(conn);
                return;
            }
            if (conn.getStatus() == McpConnectionStatus.CONNECTED) {
                conn.resetReconnectAttempts();
                registerToolsFromConnection(conn);
                log.info("MCP server restarted: {}", name);
            } else {
                log.warn("MCP server restart did not reach CONNECTED state: {} (status={})",
                        name, conn.getStatus());
            }
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
                            enhancedDesc, customTimeout, schemaCompressor,
                            progressTracker, abortContextLookup());
                })
                .toList();
    }

    // ===== 重连 =====

    /**
     * 断线重连 — 对 FAILED/PENDING 状态的服务器执行异步重试。
     * stdio 类型不重连（本地进程终止 = 不可恢复）。
     * ★ 修复: 改为异步延迟重连，不再阻塞调用线程。
     */
    public void reconnectFailed() {
        connections.forEach((name, conn) -> {
            if (conn.getStatus() != McpConnectionStatus.FAILED
                    && conn.getStatus() != McpConnectionStatus.PENDING) {
                return;
            }
            if (conn.getConfig().type() == McpTransportType.STDIO) {
                return;
            }
            if (conn.getReconnectAttempts() >= MAX_RECONNECT_ATTEMPTS) {
                return;
            }
            scheduleDelayedReconnect(name, conn);
        });
    }

    /** 计算指数退避延迟（无抖动，保持向后兼容） */
    static long calculateBackoff(int attempt) {
        return Math.min(INITIAL_BACKOFF_MS * (1L << (attempt - 1)), MAX_BACKOFF_MS);
    }

    /**
     * 计算指数退避延迟 + 随机抖动 — 防止多连接同时重连 (thundering herd)。
     * 抖动范围: ±25% 的基础退避值。
     */
    static long calculateBackoffWithJitter(int attempt) {
        long base = calculateBackoff(attempt);
        long jitter = (long) (base * 0.25 * (ThreadLocalRandom.current().nextDouble() * 2 - 1));
        return Math.max(INITIAL_BACKOFF_MS, base + jitter);
    }

    // ===== 关闭 =====

    /** 优雅关闭所有 MCP 连接 */
    public void shutdown() {
        running = false;
        connectionGenerations.forEach((name, ignored) -> nextGeneration(name));
        scheduledReconnects.values().forEach(scheduled -> scheduled.future().cancel(false));
        scheduledReconnects.clear();
        activeReconnects.values().forEach(active -> active.future().cancel(true));
        activeReconnects.clear();
        reconnectingServers.clear();
        connections.values().forEach(conn -> {
            try {
                conn.close();
            } catch (Exception e) {
                log.warn("Error closing MCP connection: {}", e.getMessage());
            }
        });
        connections.clear();
        reconnectPool.shutdownNow();
        reconnectScheduler.shutdownNow();
        awaitTermination(reconnectPool, "reconnect worker");
        awaitTermination(reconnectScheduler, "reconnect scheduler");
    }

    private void requireRunning() {
        if (!running) {
            throw new IllegalStateException("MCP_CLIENT_MANAGER_NOT_RUNNING");
        }
    }

    // ===== M3 Roots 安全边界 =====

    /**
     * 启动时初始化默认 roots — 使用 {@code user.dir} 作为当前工作区。
     * 后续可通过 {@link WorkspaceChangedEvent} 动态更新。
     */
    private void initializeDefaultRoots() {
        try {
            String workspacePath = System.getProperty("user.dir");
            if (workspacePath == null || workspacePath.isBlank()) {
                log.warn("user.dir not available — MCP roots left empty");
                return;
            }
            String projectName = java.nio.file.Path.of(workspacePath).getFileName() != null
                    ? java.nio.file.Path.of(workspacePath).getFileName().toString()
                    : "workspace";
            rootsProvider.updateRoots(workspacePath, projectName);
            log.info("MCP roots initialized: workspace='{}' name='{}'", workspacePath, projectName);
        } catch (Exception e) {
            log.warn("Failed to initialize default MCP roots: {}", e.getMessage());
        }
    }

    /**
     * 监听工程切换事件 — 更新 roots 并通知所有已连接的 MCP 服务器。
     */
    @EventListener
    public void onWorkspaceChanged(WorkspaceChangedEvent event) {
        rootsProvider.updateRoots(event.getWorkspacePath(), event.getProjectName());
        log.info("Workspace changed — roots updated: workspace='{}' name='{}'",
                event.getWorkspacePath(), event.getProjectName());
        for (McpServerConnection connection : getConnectedServers()) {
            try {
                connection.sendNotification("notifications/roots/list_changed", null);
            } catch (Exception e) {
                log.warn("Failed to notify MCP server '{}' of roots change: {}",
                        connection.getName(), e.getMessage());
            }
        }
    }

    // ===== 工具动态注册与权限控制 =====

    /**
     * 从已连接的 MCP 服务器注册工具。
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
                    enhancedDesc, customTimeout, schemaCompressor,
                    progressTracker, abortContextLookup());
            toolRegistry.registerDynamic(adapter);
        }

        // 监听工具变更通知 — 自动重新注册
        conn.onToolsChanged(() -> {
            if (!isCurrentConnection(conn.getName(), conn, generationOf(conn.getName()))) return;
            toolRegistry.unregisterByPrefix("mcp__" + conn.getName() + "__");
            registerToolsFromConnection(conn);
            log.info("MCP tools refreshed for server: {}", conn.getName());
        });
    }

    /**
     * 检查 MCP 工具是否被允许。
     */
    private boolean isToolAllowed(String serverName, String toolName) {
        Map<String, List<String>> permissions = mcpConfiguration.getChannelPermissions();
        if (permissions == null || permissions.isEmpty()) return true;
        List<String> blocked = permissions.getOrDefault(serverName, List.of());
        return !blocked.contains(toolName) && !blocked.contains("*");
    }

    // ===== 健康检查 + 指数退避重连 =====

    /**
     * 健康检查 + 自动重连
     * ★ 修复: 重连全部异步执行，不再阻塞调度线程。
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
                // ★ 修复: 异步延迟重连，不阻塞健康检查调度线程
                scheduleDelayedReconnect(id, conn);
            }
        });
    }

    /**
     * 异步延迟重连 — 在 RECONNECT_SCHEDULER 上调度，不阻塞调用线程。
     * ★ 使用 scheduleReconnect 的幂等保护避免并发重连。
     */
    private void scheduleDelayedReconnect(String serverId, McpServerConnection conn) {
        long generation = generationOf(serverId);
        if (!isCurrentConnection(serverId, conn, generation)) return;
        int attempt = conn.getReconnectAttempts();
        if (attempt >= MAX_RECONNECT_ATTEMPTS) {
            log.warn("MCP server {} exceeded max reconnect attempts ({}), giving up",
                    serverId, MAX_RECONNECT_ATTEMPTS);
            return;
        }
        scheduledReconnects.compute(serverId, (id, existing) -> {
            if (existing != null && existing.connection() == conn && !existing.future().isDone()) return existing;
            if (existing != null) existing.future().cancel(false);
            long backoffMs = calculateBackoffWithJitter(attempt + 1);
            log.info("Scheduling reconnect for MCP server {} in {}ms (attempt {}/{})",
                    serverId, backoffMs, attempt + 1, MAX_RECONNECT_ATTEMPTS);
            if (!isCurrentConnection(serverId, conn, generation)) return null;
            java.util.concurrent.atomic.AtomicReference<ScheduledReconnect> self = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.ScheduledFuture<?> future = reconnectScheduler.schedule(() -> {
                ScheduledReconnect scheduled = self.get();
                if (scheduled != null) scheduledReconnects.remove(serverId, scheduled);
                if (isCurrentConnection(serverId, conn, generation)) {
                    submitReconnect(serverId, conn, generation);
                }
            }, backoffMs, TimeUnit.MILLISECONDS);
            ScheduledReconnect scheduled = new ScheduledReconnect(conn, generation, future);
            self.set(scheduled);
            return scheduled;
        });
    }

    /**
     * 执行实际重连 — 在重连线程池中运行。
     */
    private void attemptReconnect(String serverId, McpServerConnection conn, long generation) {
        if (!isCurrentConnection(serverId, conn, generation)) return;
        // 幂等保护: 避免同一服务器并发重连
        if (reconnectingServers.putIfAbsent(serverId, conn) != null) {
            log.debug("Reconnect already in progress for '{}', skipping", serverId);
            return;
        }
        try {
            if (!isCurrentConnection(serverId, conn, generation)) return;
            conn.connect();
            if (!isCurrentConnection(serverId, conn, generation)) {
                closeQuietly(conn);
                return;
            }
            if (conn.getStatus() == McpConnectionStatus.CONNECTED) {
                conn.resetReconnectAttempts();
                registerToolsFromConnection(conn);
                log.info("MCP server {} reconnected successfully", serverId);
                broadcastHealthStatus(serverId, McpConnectionStatus.CONNECTED);
            } else {
                conn.incrementReconnectAttempts();
                log.debug("Reconnect failed for {} (status={})", serverId, conn.getStatus());
            }
        } catch (Exception e) {
            conn.incrementReconnectAttempts();
            log.debug("Reconnect failed for {}: {}", serverId, e.getMessage());
        } finally {
            reconnectingServers.remove(serverId, conn);
        }
    }

    private record ScheduledReconnect(McpServerConnection connection,
                                      long generation,
                                      java.util.concurrent.ScheduledFuture<?> future) { }

    private record ActiveReconnect(McpServerConnection connection, long generation, Future<?> future) { }

    /**
     * ERR-3 fix: 调度指定连接的异步重连（健康检查失败时调用）。
     * 由 SseHealthChecker 主动 ping 失败时触发。
     * <p>
     * ★ 幂等保护：通过 attemptReconnect 内部的 putIfAbsent 原子操作防止并发重连。
     * ★ 自定义线程池：使用 RECONNECT_POOL 替代默认 ForkJoinPool。
     * ★ WebSocket 广播：重连完成后推送状态变更。
     */
    public void scheduleReconnect(String connectionName) {
        getConnection(connectionName).ifPresent(conn -> {
            long generation = generationOf(connectionName);
            if (!isCurrentConnection(connectionName, conn, generation)) return;
            conn.setStatus(McpConnectionStatus.DEGRADED);
            broadcastHealthStatus(connectionName, McpConnectionStatus.DEGRADED);
            submitReconnect(connectionName, conn, generation);
        });
    }

    private void submitReconnect(String serverId, McpServerConnection conn, long generation) {
        activeReconnects.compute(serverId, (id, existing) -> {
            if (existing != null && existing.connection() == conn
                    && existing.generation() == generation && !existing.future().isDone()) return existing;
            if (existing != null) existing.future().cancel(true);
            java.util.concurrent.FutureTask<Void> task = new java.util.concurrent.FutureTask<>(() -> {
                try { attemptReconnect(serverId, conn, generation); }
                finally {
                    activeReconnects.computeIfPresent(serverId,
                            (key, active) -> active.connection() == conn && active.generation() == generation
                                    ? null : active);
                }
                return null;
            });
            ActiveReconnect active = new ActiveReconnect(conn, generation, task);
            reconnectPool.execute(task);
            return active;
        });
    }

    private long nextGeneration(String serverId) {
        return connectionGenerations.computeIfAbsent(serverId, ignored -> new AtomicLong()).incrementAndGet();
    }

    private long generationOf(String serverId) {
        AtomicLong generation = connectionGenerations.get(serverId);
        return generation == null ? 0L : generation.get();
    }

    private void installConnection(String serverId, long generation, McpServerConnection conn) {
        if (!running || generationOf(serverId) != generation) {
            closeQuietly(conn);
            throw new IllegalStateException("MCP_CONNECTION_LIFECYCLE_CHANGED");
        }
        McpServerConnection previous = connections.put(serverId, conn);
        if (previous != null && previous != conn) closeQuietly(previous);
    }

    private boolean isCurrentConnection(String serverId, McpServerConnection conn, long generation) {
        return running && generationOf(serverId) == generation && connections.get(serverId) == conn;
    }

    private void cancelReconnectWork(String serverId) {
        ScheduledReconnect scheduled = scheduledReconnects.remove(serverId);
        if (scheduled != null) scheduled.future().cancel(false);
        ActiveReconnect active = activeReconnects.remove(serverId);
        if (active != null) active.future().cancel(true);
    }

    private void closeQuietly(McpServerConnection connection) {
        try { connection.close(); }
        catch (RuntimeException e) { log.debug("Failed to close stale MCP connection {}", connection.getName(), e); }
    }

    private void awaitTermination(java.util.concurrent.ExecutorService executor, String label) {
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("MCP {} did not terminate within 5 seconds", label);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * ★ 新增: WebSocket 广播 MCP 连接状态变更 — 推送到所有活跃前端会话。
     */
    private void broadcastHealthStatus(String serverName, McpConnectionStatus status) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", "mcp_health_status");
            payload.put("ts", System.currentTimeMillis());
            payload.put("serverName", serverName);
            payload.put("status", status.name());
            payload.put("timestamp", Instant.now().toEpochMilli());

            wsSessionManager.getActiveSessionIds().forEach(sessionId -> {
                try {
                    String principal = wsSessionManager.getPrincipalForSession(sessionId);
                    if (principal != null) {
                        messaging.convertAndSendToUser(principal, "/queue/messages", payload);
                    }
                } catch (Exception e) {
                    log.debug("Failed to push health status to session {}: {}", sessionId, e.getMessage());
                }
            });
        } catch (Exception e) {
            log.debug("Failed to broadcast MCP health status: {}", e.getMessage());
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
