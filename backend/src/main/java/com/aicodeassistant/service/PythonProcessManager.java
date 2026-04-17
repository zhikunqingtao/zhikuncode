package com.aicodeassistant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Python 子进程生命周期管理器。
 * <p>
 * 管理 Python FastAPI 服务的启动、监控、重启和停止。
 * <ul>
 *     <li>自动启动 Python 服务</li>
 *     <li>定期健康检查</li>
 *     <li>自动重启策略（最多 3 次连续失败后停止重试）</li>
 *     <li>优雅关闭</li>
 * </ul>
 *
 * @see <a href="SPEC section 4.14">Python 生态集成</a>
 */
@Service
public class PythonProcessManager {

    private static final Logger log = LoggerFactory.getLogger(PythonProcessManager.class);

    /** 最大连续重启次数 */
    private static final int MAX_RESTART_ATTEMPTS = 3;

    /** 重启间隔（毫秒） */
    private static final long RESTART_DELAY_MS = 5000;

    @Value("${python.service.host:127.0.0.1}")
    private String pythonHost;

    @Value("${python.service.port:8000}")
    private int pythonPort;

    @Value("${python.service.health-check-interval:30000}")
    private long healthCheckInterval;

    private final AtomicReference<Process> processRef = new AtomicReference<>();
    private final AtomicReference<ProcessState> stateRef = new AtomicReference<>(ProcessState.STOPPED);
    private final AtomicInteger restartCount = new AtomicInteger(0);
    private volatile Instant lastHealthCheck;
    private volatile boolean autoStart = false;

    /**
     * 进程状态。
     */
    public enum ProcessState {
        STOPPED,
        STARTING,
        RUNNING,
        HEALTH_CHECK_FAILED,
        RESTARTING,
        FAILED
    }

    /**
     * 启动 Python 服务。
     */
    public synchronized boolean start() {
        if (stateRef.get() == ProcessState.RUNNING) {
            log.warn("Python service is already running");
            return true;
        }

        stateRef.set(ProcessState.STARTING);
        log.info("Starting Python service on {}:{}...", pythonHost, pythonPort);

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "python", "-m", "uvicorn",
                    "src.main:app",
                    "--host", pythonHost,
                    "--port", String.valueOf(pythonPort),
                    "--reload"
            );

            // 设置工作目录为 python-service
            Path pythonServiceDir = Path.of("python-service");
            if (pythonServiceDir.toFile().exists()) {
                pb.directory(pythonServiceDir.toFile());
            }

            pb.redirectErrorStream(true);
            Process process = pb.start();
            processRef.set(process);

            // 后台线程读取输出
            Thread.ofVirtual().name("zhiku-python-drain").start(() -> drainOutput(process));

            // 等待启动
            Thread.sleep(2000);

            if (process.isAlive() && checkHealth()) {
                stateRef.set(ProcessState.RUNNING);
                restartCount.set(0);
                log.info("Python service started successfully on port {}", pythonPort);
                return true;
            } else {
                stateRef.set(ProcessState.FAILED);
                log.error("Python service failed to start");
                return false;
            }

        } catch (Exception e) {
            stateRef.set(ProcessState.FAILED);
            log.error("Failed to start Python service: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 停止 Python 服务。
     */
    public synchronized void stop() {
        Process process = processRef.get();
        if (process == null || !process.isAlive()) {
            stateRef.set(ProcessState.STOPPED);
            return;
        }

        log.info("Stopping Python service...");
        process.destroy();

        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("Python service force-killed");
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }

        processRef.set(null);
        stateRef.set(ProcessState.STOPPED);
        log.info("Python service stopped");
    }

    /**
     * 重启 Python 服务。
     */
    public synchronized boolean restart() {
        stop();
        try {
            Thread.sleep(RESTART_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return start();
    }

    /**
     * 健康检查 — 调用 Python 服务的 /health 端点。
     */
    public boolean checkHealth() {
        try {
            URI uri = URI.create("http://" + pythonHost + ":" + pythonPort + "/health");
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");

            int responseCode = conn.getResponseCode();
            conn.disconnect();

            lastHealthCheck = Instant.now();
            boolean healthy = responseCode == 200;

            if (healthy) {
                if (stateRef.get() == ProcessState.HEALTH_CHECK_FAILED) {
                    stateRef.set(ProcessState.RUNNING);
                    restartCount.set(0);
                }
            }

            return healthy;
        } catch (Exception e) {
            log.debug("Health check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 定期健康检查（由 Spring Scheduler 调用）。
     */
    @Scheduled(fixedDelayString = "${python.service.health-check-interval:30000}")
    public void scheduledHealthCheck() {
        if (stateRef.get() != ProcessState.RUNNING &&
                stateRef.get() != ProcessState.HEALTH_CHECK_FAILED) {
            return;
        }

        if (!checkHealth()) {
            stateRef.set(ProcessState.HEALTH_CHECK_FAILED);
            int attempts = restartCount.incrementAndGet();

            if (attempts <= MAX_RESTART_ATTEMPTS) {
                log.warn("Python service health check failed, attempting restart ({}/{})",
                        attempts, MAX_RESTART_ATTEMPTS);
                stateRef.set(ProcessState.RESTARTING);
                restart();
            } else {
                log.error("Python service restart limit reached ({}). Manual restart required.",
                        MAX_RESTART_ATTEMPTS);
                stateRef.set(ProcessState.FAILED);
            }
        }
    }

    @PreDestroy
    public void onShutdown() {
        stop();
    }

    // ===== 查询 API =====

    public ProcessState getState() {
        return stateRef.get();
    }

    public boolean isRunning() {
        return stateRef.get() == ProcessState.RUNNING;
    }

    public Instant getLastHealthCheck() {
        return lastHealthCheck;
    }

    public int getRestartCount() {
        return restartCount.get();
    }

    public String getServiceUrl() {
        return "http://" + pythonHost + ":" + pythonPort;
    }

    // ===== 内部方法 =====

    private void drainOutput(Process process) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("[python] {}", line);
            }
        } catch (Exception e) {
            // 进程结束时正常退出
        }
    }
}
