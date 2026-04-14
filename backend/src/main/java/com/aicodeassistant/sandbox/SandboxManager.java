package com.aicodeassistant.sandbox;

import com.aicodeassistant.tool.ToolInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 沙箱管理器 — Docker 容器隔离执行环境。
 * <p>
 * 对照 SPEC section 9.3 设计:
 * <ul>
 *     <li>Docker 可用性检测</li>
 *     <li>沙箱启用/禁用控制</li>
 *     <li>命令沙箱判断（破坏性命令强制沙箱）</li>
 *     <li>Docker 命令构建（只读挂载 + seccomp + 内存限制）</li>
 * </ul>
 *
 * @see <a href="SPEC section 9.3">SandboxManager</a>
 */
@Service
public class SandboxManager {

    private static final Logger log = LoggerFactory.getLogger(SandboxManager.class);

    private final SandboxConfig config;
    private volatile Boolean dockerAvailable = null;
    private volatile boolean dockerUnavailableWarned = false;

    public SandboxManager(SandboxConfig config) {
        this.config = config;
    }

    /**
     * 沙箱是否启用。
     * <p>
     * 条件: 配置启用 + Docker 可用
     */
    public boolean isSandboxingEnabled() {
        if (config.isEnabled() && !isDockerAvailable()) {
            if (!dockerUnavailableWarned) {
                dockerUnavailableWarned = true;
                log.warn("Sandbox enabled in config but Docker is unavailable — running WITHOUT sandbox isolation");
            }
            return false;
        }
        return config.isEnabled() && isDockerAvailable();
    }

    /**
     * 检测 Docker 是否可用。
     * <p>
     * 通过执行 {@code docker info} 判断，结果缓存。
     */
    public boolean isDockerAvailable() {
        if (dockerAvailable != null) {
            return dockerAvailable;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "info");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean completed = process.waitFor(10, TimeUnit.SECONDS);
            dockerAvailable = completed && process.exitValue() == 0;
        } catch (Exception e) {
            log.debug("Docker not available: {}", e.getMessage());
            dockerAvailable = false;
        }

        log.info("Docker availability: {}", dockerAvailable);
        return dockerAvailable;
    }

    /**
     * 判断是否应使用沙箱执行命令。
     * <p>
     * 条件:
     * <ul>
     *     <li>沙箱已启用</li>
     *     <li>命令包含破坏性操作 (rm, mv, chmod, chown, mkfs 等)</li>
     *     <li>或命令涉及网络操作 (curl, wget 等)</li>
     * </ul>
     */
    public boolean shouldUseSandbox(ToolInput input) {
        if (!isSandboxingEnabled()) {
            return false;
        }

        String command = input.getString("command");
        if (command == null || command.isEmpty()) {
            return false;
        }

        // 破坏性命令列表
        String[] destructiveCommands = {
                "rm", "rmdir", "mv", "chmod", "chown", "mkfs",
                "dd", "format", "fdisk", "parted"
        };

        // 网络命令列表
        String[] networkCommands = {
                "curl", "wget", "nc", "ncat", "telnet"
        };

        String cmdLower = command.toLowerCase().trim();
        String firstToken = cmdLower.split("\\s+")[0];

        // 检查 sudo 包装
        if (firstToken.equals("sudo") && cmdLower.length() > 5) {
            String[] parts = cmdLower.substring(5).trim().split("\\s+");
            if (parts.length > 0) {
                firstToken = parts[0];
            }
        }

        for (String dc : destructiveCommands) {
            if (firstToken.equals(dc) || firstToken.endsWith("/" + dc)) {
                return true;
            }
        }
        for (String nc : networkCommands) {
            if (firstToken.equals(nc) || firstToken.endsWith("/" + nc)) {
                return true;
            }
        }

        return false;
    }

    /**
     * 构建沙箱化的 ProcessBuilder。
     * <p>
     * Docker 命令结构:
     * {@code docker run --rm --read-only -m 512m --network=none
     *        -v /path:/workspace:ro --security-opt seccomp=default
     *        IMAGE bash -c "COMMAND"}
     *
     * @param command      要执行的命令
     * @param workingDir   工作目录
     * @param envVars      环境变量
     * @return 配置好的 ProcessBuilder
     */
    public ProcessBuilder buildSandboxedProcess(String command, Path workingDir,
                                                  Map<String, String> envVars) {
        List<String> dockerCmd = new ArrayList<>();
        dockerCmd.add("docker");
        dockerCmd.add("run");
        dockerCmd.add("--rm");

        // 只读文件系统（tmpfs 挂载 /tmp 供临时写入）
        dockerCmd.add("--read-only");
        dockerCmd.add("--tmpfs");
        dockerCmd.add("/tmp:rw,noexec,nosuid,size=100m");

        // 内存限制
        dockerCmd.add("-m");
        dockerCmd.add(config.getMemoryLimit());

        // 网络隔离
        if (!config.isNetworkEnabled()) {
            dockerCmd.add("--network=none");
        }

        // seccomp 配置
        if (config.getSeccompProfile() != null) {
            dockerCmd.add("--security-opt");
            dockerCmd.add("seccomp=" + config.getSeccompProfile());
        }

        // 超时（通过 timeout 命令实现）
        String timeoutWrapper = "timeout " + config.getTimeoutSeconds() + " " + command;

        // 工作目录挂载
        if (workingDir != null) {
            String mountSpec = workingDir.toAbsolutePath() + ":/workspace:" + config.getMountMode();
            dockerCmd.add("-v");
            dockerCmd.add(mountSpec);
            dockerCmd.add("-w");
            dockerCmd.add("/workspace");
        }

        // 环境变量
        if (envVars != null) {
            for (Map.Entry<String, String> entry : envVars.entrySet()) {
                dockerCmd.add("-e");
                dockerCmd.add(entry.getKey() + "=" + entry.getValue());
            }
        }

        // 镜像和命令
        dockerCmd.add(config.getImage());
        dockerCmd.add("bash");
        dockerCmd.add("-c");
        dockerCmd.add(timeoutWrapper);

        ProcessBuilder pb = new ProcessBuilder(dockerCmd);
        pb.redirectErrorStream(true);
        return pb;
    }

    /**
     * 在沙箱中执行命令并返回输出。
     */
    public SandboxResult execute(String command, Path workingDir,
                                  Map<String, String> envVars) {
        try {
            ProcessBuilder pb = buildSandboxedProcess(command, workingDir, envVars);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean completed = process.waitFor(config.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                return new SandboxResult(output.toString(), -1, true);
            }

            return new SandboxResult(output.toString(), process.exitValue(), false);

        } catch (Exception e) {
            log.error("Sandbox execution failed: {}", e.getMessage());
            return new SandboxResult("Sandbox error: " + e.getMessage(), -1, false);
        }
    }

    /**
     * 重置 Docker 可用性缓存（用于测试或重新检测）。
     */
    public void resetDockerAvailability() {
        dockerAvailable = null;
    }

    /**
     * 沙箱执行结果。
     */
    public record SandboxResult(
            String output,
            int exitCode,
            boolean timedOut
    ) {
        public boolean isSuccess() {
            return exitCode == 0 && !timedOut;
        }
    }
}
