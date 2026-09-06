package com.aicodeassistant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Python 子进程生命周期管理器。
 * <p>
 * 管理 Python FastAPI 服务的启动、监控、重启和停止。
 */
@Service
public class PythonProcessManager {

    private static final Logger log = LoggerFactory.getLogger(PythonProcessManager.class);
    private static final int MAX_RESTART_ATTEMPTS = 3;

    @Value("${python.service.host:127.0.0.1}")
    private String pythonHost;

    @Value("${python.service.bind-host:127.0.0.1}")
    private String pythonBindHost;

    @Value("${python.service.port:8000}")
    private int pythonPort;

    @Value("${python.service.path:../python-service}")
    private String pythonServicePath;

    @Value("${python.service.executable:python}")
    private String pythonExecutable;

    @Value("${python.service.auto-start:false}")
    private boolean autoStart;

    @Value("${python.service.startup-timeout-seconds:30}")
    private int startupTimeoutSeconds;

    @Value("${python.service.restart-delay-ms:5000}")
    private long restartDelayMs;

    private final AtomicReference<Process> processRef = new AtomicReference<>();
    private final AtomicReference<ProcessState> stateRef =
            new AtomicReference<>(ProcessState.STOPPED);
    private final AtomicBoolean healthCheckInProgress = new AtomicBoolean(false);
    private final AtomicInteger restartCount = new AtomicInteger(0);
    private volatile Instant lastHealthCheck;
    private volatile boolean shutdownRequested;

    public enum ProcessState {
        STOPPED,
        STARTING,
        RUNNING,
        HEALTH_CHECK_FAILED,
        RESTARTING,
        FAILED
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (autoStart) {
            startAsync();
        }
    }

    void startAsync() {
        Thread.ofVirtual().name("zhikun-python-startup").start(this::startWithRetry);
    }

    void startWithRetry() {
        if (shutdownRequested || start()) {
            return;
        }

        while (!shutdownRequested && restartCount.get() < MAX_RESTART_ATTEMPTS) {
            int attempt = restartCount.incrementAndGet();
            stateRef.set(ProcessState.RESTARTING);
            log.warn("Python service startup failed, retrying ({}/{})",
                    attempt, MAX_RESTART_ATTEMPTS);
            try {
                Thread.sleep(Math.max(0, restartDelayMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (!shutdownRequested) {
                    stateRef.set(ProcessState.FAILED);
                }
                return;
            }
            if (shutdownRequested || start()) {
                return;
            }
        }

        if (!shutdownRequested) {
            stateRef.set(ProcessState.FAILED);
            log.error("Python service startup retry limit reached ({}).",
                    MAX_RESTART_ATTEMPTS);
        }
    }

    /**
     * 启动 Python 服务。
     */
    public synchronized boolean start() {
        if (shutdownRequested) {
            return false;
        }
        Process existing = processRef.get();
        if (stateRef.get() == ProcessState.RUNNING
                && existing != null && existing.isAlive()) {
            return true;
        }
        if (existing != null && existing.isAlive()) {
            stopProcess(existing);
            processRef.compareAndSet(existing, null);
        }

        stateRef.set(ProcessState.STARTING);
        log.info("Starting Python service on {}:{}...", pythonHost, pythonPort);

        Process process = null;
        try {
            ProcessBuilder builder = createProcessBuilder();
            builder.redirectErrorStream(true);
            process = builder.start();
            processRef.set(process);
            Process started = process;
            Thread.ofVirtual().name("zhikun-python-drain")
                    .start(() -> drainOutput(started));

            if (waitUntilHealthy(process)) {
                stateRef.set(ProcessState.RUNNING);
                restartCount.set(0);
                log.info("Python service started successfully on port {}", pythonPort);
                return true;
            }
            log.error("Python service did not become healthy within {} seconds",
                    Math.max(1, startupTimeoutSeconds));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while starting Python service");
        } catch (Exception e) {
            log.error("Failed to start Python service: {}", e.getMessage());
        }

        if (process != null) {
            if (process.isAlive()) {
                stopProcess(process);
            }
            processRef.compareAndSet(process, null);
        }
        stateRef.set(ProcessState.FAILED);
        return false;
    }

    /**
     * 停止 Python 服务。
     */
    public synchronized void stop() {
        Process process = processRef.getAndSet(null);
        if (process != null && process.isAlive()) {
            log.info("Stopping Python service...");
            stopProcess(process);
        }
        stateRef.set(ProcessState.STOPPED);
    }

    /**
     * 重启 Python 服务。
     */
    public synchronized boolean restart() {
        stop();
        try {
            Thread.sleep(Math.max(0, restartDelayMs));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stateRef.set(ProcessState.FAILED);
            return false;
        }
        return start();
    }

    /**
     * 健康检查 — 调用 Python 服务的 /api/health 端点。
     */
    public boolean checkHealth() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) healthUri().toURL().openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestMethod("GET");
            boolean healthy = connection.getResponseCode() == 200;
            lastHealthCheck = Instant.now();
            if (healthy && stateRef.get() == ProcessState.HEALTH_CHECK_FAILED) {
                stateRef.set(ProcessState.RUNNING);
                restartCount.set(0);
            }
            return healthy;
        } catch (Exception e) {
            lastHealthCheck = Instant.now();
            log.debug("Health check failed: {}", e.getMessage());
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * 定期健康检查（由 Spring Scheduler 调用）。
     */
    @Scheduled(fixedDelayString = "${python.service.health-check-interval:30000}")
    public void scheduledHealthCheck() {
        ProcessState state = stateRef.get();
        if (shutdownRequested || (state != ProcessState.RUNNING
                && state != ProcessState.HEALTH_CHECK_FAILED)) {
            return;
        }
        if (!healthCheckInProgress.compareAndSet(false, true)) {
            return;
        }
        Thread.ofVirtual().name("zhikun-python-health")
                .start(() -> {
                    try {
                        runHealthCheckCycle();
                    } finally {
                        healthCheckInProgress.set(false);
                    }
                });
    }

    synchronized void runHealthCheckCycle() {
        ProcessState state = stateRef.get();
        if (shutdownRequested || (state != ProcessState.RUNNING
                && state != ProcessState.HEALTH_CHECK_FAILED)) {
            return;
        }
        if (checkHealth()) {
            return;
        }
        if (shutdownRequested) {
            return;
        }

        int attempts = restartCount.incrementAndGet();
        if (attempts > MAX_RESTART_ATTEMPTS) {
            stateRef.set(ProcessState.FAILED);
            log.error("Python service restart limit reached ({}). Manual restart required.",
                    MAX_RESTART_ATTEMPTS);
            return;
        }

        stateRef.set(ProcessState.RESTARTING);
        log.warn("Python service health check failed, attempting restart ({}/{})",
                attempts, MAX_RESTART_ATTEMPTS);
        if (restart()) {
            stateRef.set(ProcessState.RUNNING);
            restartCount.set(0);
        } else if (!shutdownRequested) {
            stateRef.set(attempts == MAX_RESTART_ATTEMPTS
                    ? ProcessState.FAILED : ProcessState.HEALTH_CHECK_FAILED);
        }
    }

    @PreDestroy
    public void onShutdown() {
        shutdownRequested = true;
        stop();
    }

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

    ProcessBuilder createProcessBuilder() {
        Path serviceDir = Path.of(pythonServicePath).toAbsolutePath().normalize();
        if (!serviceDir.toFile().isDirectory()) {
            throw new IllegalStateException(
                    "Python service path is not a directory: " + serviceDir);
        }
        ProcessBuilder builder = new ProcessBuilder(
                pythonExecutable,
                "-m", "uvicorn",
                "src.main:app",
                "--host", pythonBindHost,
                "--port", Integer.toString(pythonPort));
        builder.directory(serviceDir.toFile());

        String sourcePath = serviceDir.resolve("src").toString();
        String existingPythonPath = builder.environment().get("PYTHONPATH");
        builder.environment().put("PYTHONPATH",
                existingPythonPath == null || existingPythonPath.isBlank()
                        ? sourcePath
                        : sourcePath + File.pathSeparator + existingPythonPath);
        return builder;
    }

    URI healthUri() {
        return URI.create("http://" + pythonHost + ":" + pythonPort + "/api/health");
    }

    private boolean waitUntilHealthy(Process process) throws InterruptedException {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(Math.max(1, startupTimeoutSeconds));
        while (!shutdownRequested && process.isAlive()
                && System.nanoTime() < deadline) {
            if (checkHealth()) {
                return true;
            }
            Thread.sleep(500);
        }
        return false;
    }

    private void stopProcess(Process process) {
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
    }

    private void drainOutput(Process process) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("[python] {}", line);
            }
        } catch (Exception ignored) {
            // 进程结束时正常退出
        }
    }
}
