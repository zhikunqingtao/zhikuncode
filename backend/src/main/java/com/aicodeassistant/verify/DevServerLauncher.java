package com.aicodeassistant.verify;

import com.aicodeassistant.observability.SafeLogValue;

import com.aicodeassistant.tool.bash.ProcessTreeManager;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
public class DevServerLauncher {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration GRACE_PERIOD = Duration.ofSeconds(2);
    private static final Logger log = LoggerFactory.getLogger(DevServerLauncher.class);

    private final ProcessTreeManager processTreeManager;
    private final ConcurrentHashMap<String, DevServerHandle> activeHandles = new ConcurrentHashMap<>();

    public DevServerLauncher(ProcessTreeManager processTreeManager) {
        this.processTreeManager = processTreeManager;
    }

    public DevServerHandle start(Path workspace, String command, int port, Duration timeout) {
        requireAvailablePort(port);
        // D7: npm install 前置检查（仅针对 Node 项目，需存在 package.json）
        Path packageJson = workspace.resolve("package.json");
        Path nodeModules = workspace.resolve("node_modules");
        if (Files.exists(packageJson) && !Files.isDirectory(nodeModules)) {
            log.info("node_modules not found, running npm install...");
            runSync(workspace, "npm install", Duration.ofSeconds(300));
        }

        return startProcess(workspace, List.of("bash", "-c", command), port, timeout);
    }

    /** Serve only the staged static files; never install dependencies or run project scripts. */
    public DevServerHandle startStatic(Path directory, Duration timeout) {
        int port;
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            port = socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot allocate a static preview port", e);
        }
        return startProcess(directory, List.of("python3", "-m", "http.server", String.valueOf(port),
                "--bind", "127.0.0.1"), port, timeout);
    }

    private static void requireAvailablePort(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", port));
        } catch (IOException e) {
            throw new IllegalStateException("Dev server port is already in use: " + port, e);
        }
    }

    private DevServerHandle startProcess(Path workspace, List<String> command, int port, Duration timeout) {
        requireAvailablePort(port);

        // 日志文件
        Path logFile = workspace.resolve(".ai-code-assistant/devserver.log");
        try {
            Files.createDirectories(logFile.getParent());
        } catch (IOException e) {
            throw new RuntimeException("Failed to create log directory", e);
        }

        // D5: bash -c
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workspace.toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));

        Process process;
        try {
            process = pb.start();
            process.getOutputStream().close();
        } catch (IOException e) {
            throw new RuntimeException("Failed to start dev server: " + e.getMessage(), e);
        }

        long pid = process.pid();
        log.info("Dev server started: pid={}, commandLength={}, commandFingerprint={}, port={}", pid,
                SafeLogValue.length(command.toString()), SafeLogValue.fingerprint(command.toString()), port);

        // PID 持久化
        Path pidFile = workspace.resolve(".ai-code-assistant/devserver.pid");
        try {
            Files.writeString(pidFile, String.valueOf(pid));
        } catch (IOException e) {
            log.warn("Failed to write PID file", e);
        }

        // HTTP 轮询就绪
        boolean ready = pollUntilReady(process, port, timeout);
        if (!ready) {
            String logTail = readLogTail(logFile, 2000);
            boolean exited = !process.isAlive();
            int exitCode = exited ? process.exitValue() : -1;
            DevServerHandle handle = new DevServerHandle(process, pid, port, logFile, pidFile);
            stop(handle);
            if (exited) throw new IllegalStateException("Dev server exited with code " + exitCode + ": " + logTail);
            throw new DevServerTimeoutException(port, timeout, logTail);
        }

        DevServerHandle handle = new DevServerHandle(process, pid, port, logFile, pidFile);
        activeHandles.put(String.valueOf(pid), handle);
        return handle;
    }

    public void stop(DevServerHandle handle) {
        activeHandles.remove(String.valueOf(handle.pid()));
        processTreeManager.destroyProcessTree(handle.process(), GRACE_PERIOD);
        try { Files.deleteIfExists(handle.pidFile()); } catch (IOException ignored) {}
        log.info("Dev server stopped: pid={}", handle.pid());
    }

    @PreDestroy
    public void shutdownAll() {
        activeHandles.values().forEach(this::stop);
    }

    private boolean pollUntilReady(Process process, int port, Duration timeout) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive()) return false;
            try {
                var req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + port + "/"))
                    .timeout(Duration.ofSeconds(3))
                    .GET().build();
                var resp = client.send(req, HttpResponse.BodyHandlers.discarding());
                if (resp.statusCode() < 500 && process.isAlive()) return true;
            } catch (Exception ignored) {}
            try { Thread.sleep(1000); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void runSync(Path workspace, String command, Duration timeout) {
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
            pb.directory(workspace.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean done = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!done) {
                processTreeManager.destroyProcessTree(p, GRACE_PERIOD);
                throw new RuntimeException("npm install timed out after " + timeout.toSeconds() + "s");
            }
            if (p.exitValue() != 0) {
                String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                throw new RuntimeException("npm install failed (exit " + p.exitValue() + "): "
                    + output.substring(0, Math.min(output.length(), 2000)));
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("npm install failed: " + e.getMessage(), e);
        }
    }

    private String readLogTail(Path logFile, int maxChars) {
        try {
            String content = Files.readString(logFile);
            return content.length() > maxChars
                ? "..." + content.substring(content.length() - maxChars)
                : content;
        } catch (IOException e) {
            return "(log file unreadable: " + e.getMessage() + ")";
        }
    }
}
