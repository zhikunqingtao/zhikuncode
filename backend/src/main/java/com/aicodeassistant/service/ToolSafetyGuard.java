package com.aicodeassistant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 工具安全守卫 (§9.3) — 多层纵深防护。
 * <p>
 * 职责: "调用参数是否安全?" (与权限系统正交)
 * <ul>
 *   <li>路径安全: realpath 规范化 + 符号链接检测 + 沙箱边界 + 黑名单</li>
 *   <li>命令安全: 危险命令模式匹配</li>
 *   <li>环境安全: 敏感环境变量清理</li>
 * </ul>
 */
@Component
public class ToolSafetyGuard {

    private static final Logger log = LoggerFactory.getLogger(ToolSafetyGuard.class);

    // ============ 路径安全 ============

    /** 禁止访问的敏感路径黑名单 */
    private static final Set<String> BLOCKED_PATHS = Set.of(
            "/etc/shadow", "/etc/passwd", "/etc/sudoers",
            "~/.ssh/id_rsa", "~/.ssh/id_ed25519", "~/.ssh/id_ecdsa",
            "~/.ssh/config", "~/.ssh/known_hosts",
            "~/.aws/credentials", "~/.aws/config",
            "~/.config/ai-code-assistant/keychain",
            "~/.gnupg", "~/.kube/config",
            "/proc", "/sys"
    );

    /**
     * 路径安全验证 — 多层防护链 (§9.3)。
     * <p>
     * 验证顺序:
     * <ol>
     *   <li>realpath() 规范化 — 解析 ../ 和 ./ 为绝对路径</li>
     *   <li>符号链接检测 — 检查路径中是否包含符号链接逃逸</li>
     *   <li>沙箱边界检查 — 确保规范化后的路径在 workspaceRoot 内</li>
     *   <li>黑名单检查 — 阻止访问敏感系统文件</li>
     * </ol>
     *
     * @param rawPath       原始路径
     * @param workspaceRoot 工作目录根路径（沙箱边界）
     * @return 规范化后的安全路径
     * @throws SecurityException 路径不安全时抛出
     */
    public Path validatePath(String rawPath, Path workspaceRoot) throws SecurityException {
        // 1. realpath() 规范化
        Path normalized;
        try {
            normalized = Path.of(rawPath).toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException e) {
            // 文件不存在时使用 normalize() 替代 (新文件创建场景)
            normalized = Path.of(rawPath).toAbsolutePath().normalize();
        }

        // 2. 符号链接检测
        Path current = normalized.getRoot();
        if (current != null) {
            for (Path component : normalized) {
                current = current.resolve(component);
                if (Files.isSymbolicLink(current)) {
                    try {
                        Path target = Files.readSymbolicLink(current);
                        Path resolvedTarget = current.getParent().resolve(target).normalize();
                        if (!resolvedTarget.startsWith(workspaceRoot)) {
                            throw new SecurityException(
                                    "符号链接逃逸: " + current + " → " + resolvedTarget
                                            + " (超出工作目录 " + workspaceRoot + ")");
                        }
                    } catch (IOException e) {
                        log.warn("无法读取符号链接: {}", current);
                    }
                }
            }
        }

        // 3. 沙箱边界检查
        if (!normalized.startsWith(workspaceRoot)) {
            throw new SecurityException(
                    "路径越界: " + normalized + " 不在工作目录 " + workspaceRoot + " 内");
        }

        // 4. 挂载点边界检查 — 防止跨文件系统访问 (§11.3.1)
        if (isMountPoint(normalized) && !normalized.equals(workspaceRoot)) {
            throw new SecurityException(
                    "路径跨越挂载点边界: " + normalized + " (可能是外部文件系统挂载)");
        }

        // 5. 黑名单检查
        String absolutePath = normalized.toString();
        String home = System.getProperty("user.home");
        for (String blocked : BLOCKED_PATHS) {
            String expandedBlocked = blocked.replace("~", home);
            if (absolutePath.equals(expandedBlocked)
                    || absolutePath.startsWith(expandedBlocked + File.separator)) {
                throw new SecurityException("禁止访问敏感路径: " + blocked);
            }
        }

        return normalized;
    }

    /**
     * 检测路径是否为挂载点 — 跨平台实现 (§11.3.1)。
     * <p>
     * Linux: 解析 /proc/mounts 匹配挂载点路径。
     * macOS/通用: 比较路径与其父目录的 FileStore，不同则为挂载点。
     *
     * @param path 要检测的路径
     * @return true 如果路径是挂载点
     */
    boolean isMountPoint(Path path) {
        try {
            Path procMounts = Path.of("/proc/mounts");
            if (Files.exists(procMounts)) {
                // Linux: 解析 /proc/mounts
                String absolutePath = path.toAbsolutePath().toString();
                try (BufferedReader reader = new BufferedReader(new FileReader(procMounts.toFile()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 2 && parts[1].equals(absolutePath)) {
                            return true;
                        }
                    }
                }
                return false;
            } else {
                // macOS / 通用: 比较 FileStore
                if (!Files.exists(path) || path.getParent() == null) {
                    return false;
                }
                FileStore pathStore = Files.getFileStore(path);
                FileStore parentStore = Files.getFileStore(path.getParent());
                return !pathStore.equals(parentStore);
            }
        } catch (IOException e) {
            log.debug("挂载点检测失败 (非致命): {}", path);
            return false;
        }
    }

    // ============ Bash 命令安全 ============

    /**
     * 危险命令模式 — 使用正则匹配。
     */
    private static final List<Pattern> DANGEROUS_PATTERNS = List.of(
            Pattern.compile("rm\\s+-[rf]*\\s+/"),           // rm -rf /
            Pattern.compile("mkfs\\."),                      // mkfs.*
            Pattern.compile("dd\\s+if="),                    // dd if=
            Pattern.compile(":\\(\\)\\{\\s*:|:&\\s*\\};:"),  // Fork bomb
            Pattern.compile("chmod\\s+-R\\s+777\\s+/"),      // chmod -R 777 /
            Pattern.compile("curl\\s+.*\\|\\s*(ba)?sh"),     // curl | bash
            Pattern.compile("wget\\s+.*\\|\\s*(ba)?sh"),     // wget | sh
            Pattern.compile("python[23]?\\s+-c\\s+['\"].*import\\s+os"), // python -c 'import os'
            Pattern.compile("eval\\s*\\$\\("),               // eval $(...)
            Pattern.compile(">\\s*/dev/sd[a-z]"),            // > /dev/sda
            Pattern.compile("nc\\s+-[lp]"),                  // netcat 监听
            Pattern.compile("nohup\\s+.*&\\s*$")             // nohup 后台持久进程
    );

    /**
     * Bash 命令安全验证 — 检查命令是否包含危险模式。
     *
     * @param command 要执行的 bash 命令
     * @throws SecurityException 包含危险模式时抛出
     */
    public void validateBashCommand(String command) throws SecurityException {
        if (command == null || command.isBlank()) return;

        for (Pattern pattern : DANGEROUS_PATTERNS) {
            if (pattern.matcher(command).find()) {
                throw new SecurityException(
                        "危险命令被阻止: 匹配模式 '" + pattern.pattern() + "' — " + command);
            }
        }
    }

    // ============ 环境安全 ============

    /** 需要从子进程环境中清除的敏感变量 */
    private static final Set<String> SENSITIVE_ENV_VARS = Set.of(
            "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN",
            "GITHUB_TOKEN", "GH_TOKEN",
            "NPM_TOKEN", "DOCKER_PASSWORD",
            "DATABASE_PASSWORD", "DB_PASSWORD",
            "PRIVATE_KEY", "SECRET_KEY"
    );

    /**
     * 获取需要从子进程环境中清除的敏感变量列表。
     */
    public Set<String> getSensitiveEnvVars() {
        return SENSITIVE_ENV_VARS;
    }

    /**
     * 构建安全的进程环境 — 清除敏感变量。
     *
     * @param processBuilder 进程构建器
     */
    public void sanitizeProcessEnvironment(ProcessBuilder processBuilder) {
        var env = processBuilder.environment();
        SENSITIVE_ENV_VARS.forEach(env::remove);
    }
}
