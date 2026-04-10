package com.aicodeassistant.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/**
 * 统一路径安全验证服务 — 对齐 Claude Code pathInAllowedWorkingPath() + DANGEROUS_FILES + BLOCKED_DEVICE_PATHS。
 * <p>
 * 供 FileReadTool、FileEditTool、BashTool 共用，确保安全策略一致。
 *
 * @see <a href="Claude Code filesystem.ts">Claude Code 路径权限检查</a>
 */
@Service
public class PathSecurityService {

    private static final Logger log = LoggerFactory.getLogger(PathSecurityService.class);

    /** 危险文件 — 对齐 Claude Code filesystem.ts DANGEROUS_FILES */
    private static final Set<String> DANGEROUS_FILES = Set.of(
        ".gitconfig", ".gitmodules", ".bashrc", ".bash_profile",
        ".zshrc", ".zprofile", ".profile", ".env", ".env.local",
        ".ripgreprc", ".mcp.json", ".claude.json"
    );

    /** 危险目录 — 对齐 Claude Code filesystem.ts DANGEROUS_DIRECTORIES */
    private static final Set<String> DANGEROUS_DIRECTORIES = Set.of(
        ".git", ".vscode", ".idea", ".claude", ".ssh", ".gnupg", ".aws"
    );

    /** 设备文件黑名单 — 对齐 Claude Code FileReadTool.ts BLOCKED_DEVICE_PATHS */
    private static final Set<String> BLOCKED_DEVICE_PATHS = Set.of(
        "/dev/zero", "/dev/random", "/dev/urandom", "/dev/full",
        "/dev/stdin", "/dev/tty", "/dev/console",
        "/dev/stdout", "/dev/stderr",
        "/dev/fd/0", "/dev/fd/1", "/dev/fd/2"
    );

    /**
     * 验证读取路径安全性 — 对齐 Claude Code checkReadPermissionForTool()。
     *
     * @param filePath         原始文件路径
     * @param workingDirectory 项目工作目录
     * @return 验证结果: allowed=true 或错误消息
     */
    public PathCheckResult checkReadPermission(String filePath, String workingDirectory) {
        Path resolved = resolvePath(filePath, workingDirectory);
        String resolvedStr = resolved.toString();

        // 1. 设备文件检查 — 对齐 Claude Code BLOCKED_DEVICE_PATHS
        if (BLOCKED_DEVICE_PATHS.contains(resolvedStr)) {
            return PathCheckResult.denied("Cannot read device file: " + resolved);
        }

        // 2. /proc 特殊文件检查
        if (resolvedStr.startsWith("/proc/") &&
            (resolvedStr.endsWith("/fd/0") || resolvedStr.endsWith("/fd/1") || resolvedStr.endsWith("/fd/2")
             || resolvedStr.endsWith("/environ"))) {
            return PathCheckResult.denied("Cannot read process special file: " + resolved);
        }

        // 3. 项目边界检查 — 对齐 Claude Code pathInAllowedWorkingPath()
        Path projectRoot = Path.of(workingDirectory).toAbsolutePath().normalize();
        if (!resolved.startsWith(projectRoot)) {
            if (!isAllowedExternalPath(resolved)) {
                return PathCheckResult.denied(
                    "Access denied: path '" + filePath + "' is outside project boundary. " +
                    "Allowed: " + projectRoot);
            }
        }

        // 4. 危险文件警告 — 对齐 Claude Code DANGEROUS_FILES
        if (resolved.getFileName() != null) {
            String fileName = resolved.getFileName().toString().toLowerCase();
            if (DANGEROUS_FILES.contains(fileName)) {
                return PathCheckResult.needsConfirmation(
                    "Reading sensitive file: " + fileName);
            }
        }

        return PathCheckResult.allowed();
    }

    /**
     * 验证写入路径安全性 — 比读取更严格。
     * 对齐 Claude Code FileEditTool.ts checkPermissions() + validateInputForSettingsFileEdit()
     */
    public PathCheckResult checkWritePermission(String filePath, String workingDirectory) {
        PathCheckResult readCheck = checkReadPermission(filePath, workingDirectory);
        if (!readCheck.isAllowed() && !readCheck.needsConfirmation()) {
            return readCheck;
        }

        Path resolved = resolvePath(filePath, workingDirectory);

        // 5. 危险目录写入检查 — 对齐 Claude Code DANGEROUS_DIRECTORIES
        for (String dangerDir : DANGEROUS_DIRECTORIES) {
            if (containsPathComponent(resolved, dangerDir)) {
                return PathCheckResult.needsConfirmation(
                    "Writing to protected directory: " + dangerDir);
            }
        }

        return readCheck;
    }

    /**
     * 解析路径 — 对齐 Claude Code safeResolvePath()。
     * 处理相对路径、符号链接、../ 遍历。
     */
    public Path resolvePath(String filePath, String workingDirectory) {
        Path path = Path.of(filePath);
        Path resolved;
        if (path.isAbsolute()) {
            resolved = path;
        } else {
            resolved = Path.of(workingDirectory).resolve(filePath);
        }
        resolved = resolved.toAbsolutePath().normalize();

        // 符号链接解析 — 对齐 Claude Code safeResolvePath()
        try {
            resolved = resolved.toRealPath();
        } catch (IOException e) {
            // 文件不存在时 toRealPath 会失败，使用 normalize 结果
        }
        return resolved;
    }

    /**
     * 检查路径是否为允许的外部路径。
     * 临时目录等公共区域可以读取。
     */
    private boolean isAllowedExternalPath(Path path) {
        String pathStr = path.toString();
        return pathStr.startsWith("/tmp") ||
               pathStr.startsWith(System.getProperty("java.io.tmpdir"));
    }

    /**
     * 检查路径中是否包含指定目录组件（case-insensitive）。
     * 对齐 Claude Code normalizeCaseForComparison()
     */
    private boolean containsPathComponent(Path path, String component) {
        for (Path part : path) {
            if (part.toString().equalsIgnoreCase(component)) return true;
        }
        return false;
    }

    /** 路径检查结果 */
    public record PathCheckResult(boolean isAllowed, boolean needsConfirmation, String message) {
        public static PathCheckResult allowed() {
            return new PathCheckResult(true, false, null);
        }
        public static PathCheckResult denied(String msg) {
            return new PathCheckResult(false, false, msg);
        }
        public static PathCheckResult needsConfirmation(String msg) {
            return new PathCheckResult(true, true, msg);
        }
    }
}
