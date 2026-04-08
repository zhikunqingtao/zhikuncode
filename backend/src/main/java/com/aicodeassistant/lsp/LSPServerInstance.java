package com.aicodeassistant.lsp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;

/**
 * LSP 服务器实例 — 单个语言服务器的封装。
 * <p>
 * P1 简化实现: 实际的子进程管理和 JSON-RPC 通信将在 Python 辅助服务集成后完善。
 * 当前提供接口定义和模拟行为。
 *
 * @see <a href="SPEC §4.1.4a">LSPServerManager 服务层</a>
 */
public class LSPServerInstance {

    private static final Logger log = LoggerFactory.getLogger(LSPServerInstance.class);

    private final LSPServerConfig config;
    private volatile boolean running;
    private volatile Instant lastActivity;

    public LSPServerInstance(LSPServerConfig config) {
        this.config = config;
        this.running = false;
        this.lastActivity = Instant.now();
    }

    /**
     * 启动 LSP 服务器子进程。
     * P1 占位: 实际启动需要 ProcessBuilder + JSON-RPC 握手。
     */
    public void start() {
        log.info("Starting LSP server: {} ({})", config.name(), config.command());
        // P1: ProcessBuilder + stdin/stdout JSON-RPC
        this.running = true;
        this.lastActivity = Instant.now();
        log.info("LSP server started: {}", config.name());
    }

    /**
     * 优雅关闭 — 发送 shutdown → exit。
     */
    public void stop() {
        log.info("Stopping LSP server: {}", config.name());
        this.running = false;
    }

    /** 服务器是否正在运行 */
    public boolean isRunning() {
        return running;
    }

    /**
     * 发送 LSP 请求并等待响应。
     * P1 占位: 返回模拟数据。
     *
     * @param method  LSP 方法名 (如 "textDocument/definition")
     * @param params  请求参数
     * @return 响应结果 Map
     */
    public Map<String, Object> sendRequest(String method, Map<String, Object> params) {
        if (!running) {
            throw new IllegalStateException("LSP server " + config.name() + " is not running");
        }
        this.lastActivity = Instant.now();
        log.debug("LSP request: {} → {}", config.name(), method);

        // P1 占位: 返回模拟结果
        return Map.of(
                "method", method,
                "server", config.name(),
                "status", "placeholder",
                "message", "LSP server integration pending (requires Python pygls service)"
        );
    }

    /**
     * 发送 LSP 通知（无响应）。
     */
    public void sendNotification(String method, Map<String, Object> params) {
        if (!running) return;
        this.lastActivity = Instant.now();
        log.debug("LSP notification: {} → {}", config.name(), method);
    }

    /** 检查是否空闲超时 */
    public boolean isIdleTimeout() {
        return Instant.now().isAfter(
                lastActivity.plusMillis(config.idleShutdownMs()));
    }

    public LSPServerConfig getConfig() { return config; }
    public Instant getLastActivity() { return lastActivity; }
}
