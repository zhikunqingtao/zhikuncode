package com.aicodeassistant.sandbox;

import com.aicodeassistant.security.CommandBlacklistService;
import com.aicodeassistant.tool.ToolInput;
import com.aicodeassistant.tool.process.ManagedProcessRunner;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 沙箱管理器 — Docker 容器隔离执行环境。
 * <p>
 * <ul>
 *     <li>Docker 可用性检测</li>
 *     <li>沙箱启用/禁用控制</li>
 *     <li>命令沙箱判断（破坏性命令强制沙箱）</li>
 *     <li>Docker 命令构建（只读挂载 + seccomp + 内存限制）</li>
 * </ul>
 *
 */
@Service
public class SandboxManager {

    private static final Logger log = LoggerFactory.getLogger(SandboxManager.class);

    private final SandboxConfig config;
    private final CommandBlacklistService commandBlacklistService;
    private final DockerRuntimeService dockerRuntime;
    private volatile Boolean dockerAvailable = null;
    private volatile boolean dockerUnavailableWarned = false;

    @Autowired
    public SandboxManager(SandboxConfig config, CommandBlacklistService commandBlacklistService,
                          DockerRuntimeService dockerRuntime) {
        this.config = config;
        this.commandBlacklistService = commandBlacklistService;
        this.dockerRuntime = dockerRuntime;
    }

    /** Test-only compatibility constructor. */
    SandboxManager(SandboxConfig config, CommandBlacklistService commandBlacklistService) {
        this(config, commandBlacklistService, null);
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

        dockerAvailable = dockerRuntime != null && dockerRuntime.isAvailable();

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

        String cmdLower = command.toLowerCase().trim();
        String firstToken = cmdLower.split("\\s+")[0];

        // 检查 sudo 包装
        if (firstToken.equals("sudo") && cmdLower.length() > 5) {
            String[] parts = cmdLower.substring(5).trim().split("\\s+");
            if (parts.length > 0) {
                firstToken = parts[0];
            }
        }

        return isDestructiveOrNetwork(firstToken);
    }

    /**
     * 字符串重载：供 BashTool 直接使用裸命令字符串判断是否需要沙箱。
     * 除了原有的 destructive/network 检测外，还将 HIGH_RISK_ASK 级别命令路由到沙箱。
     */
    public boolean shouldUseSandbox(String command) {
        if (!isSandboxingEnabled()) return false;
        if (command == null || command.isBlank()) return false;

        // 原有逻辑：destructive + network 命令
        String cmdLower = command.toLowerCase().trim();
        String firstToken = cmdLower.split("\\s+")[0];
        if (firstToken.equals("sudo") && cmdLower.length() > 5) {
            String[] parts = cmdLower.substring(5).trim().split("\\s+");
            if (parts.length > 0) {
                firstToken = parts[0];
            }
        }
        if (isDestructiveOrNetwork(firstToken)) return true;

        // 新增：HIGH_RISK_ASK 级别命令也进沙箱
        if (commandBlacklistService != null) {
            CommandBlacklistService.BlockResult blockResult = commandBlacklistService.checkCommand(command);
            return blockResult.level() == CommandBlacklistService.BlockLevel.HIGH_RISK_ASK;
        }
        return false;
    }

    /**
     * 判断首 token 是否为破坏性或网络命令。
     */
    private boolean isDestructiveOrNetwork(String firstToken) {
        // 破坏性命令列表
        String[] destructiveCommands = {
                "rm", "rmdir", "mv", "chmod", "chown", "mkfs",
                "dd", "format", "fdisk", "parted"
        };

        // 网络命令列表
        String[] networkCommands = {
                "curl", "wget", "nc", "ncat", "telnet"
        };

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

    public SandboxInvocation prepareInvocation(String command, Path workingDir,
                                               Map<String, String> envVars,
                                               String runId, String toolUseId) {
        String safeRun = sanitize(runId);
        String safeTool = sanitize(toolUseId);
        String containerName = "zhikun-" + safeRun + "-" + safeTool + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        List<String> dockerCommand = buildDockerCommand(command, workingDir, envVars, containerName);
        return new SandboxInvocation(dockerCommand, containerName,
                deadlineNanos -> dockerRuntime != null
                        && dockerRuntime.ensureRemoved(containerName, deadlineNanos));
    }

    private List<String> buildDockerCommand(String command, Path workingDir,
                                            Map<String, String> envVars, String containerName) {
        List<String> dockerCmd = new ArrayList<>();
        dockerCmd.add("docker");
        dockerCmd.add("run");
        dockerCmd.add("--rm");
        dockerCmd.add("--name");
        dockerCmd.add(containerName);

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
        dockerCmd.add(command);
        return List.copyOf(dockerCmd);
    }

    private static String sanitize(String value) {
        String result = value == null ? "none" : value.toLowerCase().replaceAll("[^a-z0-9_.-]", "-");
        return result.substring(0, Math.min(24, result.length()));
    }

    /**
     * 重置 Docker 可用性缓存（用于测试或重新检测）。
     */
    public void resetDockerAvailability() {
        dockerAvailable = null;
    }

    /**
     * 暴露超时配置（供外部如 BashTool 构建错误消息用）。
     */
    public int getTimeoutSeconds() {
        return config.getTimeoutSeconds();
    }

    /**
     * 沙箱执行结果。
     */
    public record SandboxInvocation(List<String> command, String containerName,
                                    ManagedProcessRunner.TerminationHook cleanup) { }
}
