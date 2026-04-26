package com.aicodeassistant.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一路径安全验证服务
 * <p>
 * 供 FileReadTool、FileEditTool、PermissionPipeline 共用，确保安全策略一致。
 * 硬编码敏感路径黑名单，不可通过配置修改（安全设计）。
 *
 * @see PathValidator
 */
@Service
public class PathSecurityService {

    private static final Logger log = LoggerFactory.getLogger(PathSecurityService.class);

    // ===== Layer 1: 硬编码设备路径阻止 =====
    private static final Set<String> BLOCKED_DEVICE_PATHS = Set.of(
        "/dev/zero", "/dev/random", "/dev/urandom", "/dev/full",
        "/dev/stdin", "/dev/tty", "/dev/console",
        "/dev/stdout", "/dev/stderr",
        "/dev/fd/0", "/dev/fd/1", "/dev/fd/2",
        "/proc/self/fd/0", "/proc/self/fd/1", "/proc/self/fd/2"
    );

    // ===== Layer 2: 危险文件黑名单 =====
    private static final Set<String> DANGEROUS_FILES = Set.of(
        ".gitconfig", ".gitmodules",
        ".bashrc", ".bash_profile", ".bash_login", ".bash_logout",
        ".zshrc", ".zprofile", ".zshenv", ".zlogin",
        ".profile", ".login", ".ripgreprc",
        ".env", ".env.local",
        ".mcp.json", ".zhikun.json",
        ".npmrc", ".yarnrc",
        "id_rsa", "id_ed25519", "id_ecdsa",
        "known_hosts", "authorized_keys",
        ".pgpass", ".my.cnf",
        ".netrc", ".curlrc",
        "credentials", "token.json"
    );

    // ===== Layer 2: 危险目录黑名单 =====
    private static final Set<String> DANGEROUS_DIRECTORIES = Set.of(
        ".git", ".vscode", ".idea", ".zhikun",
        ".ssh", ".gnupg", ".aws",
        ".config", ".local",
        ".kube", ".docker",
        "node_modules"
    );

    // ===== Layer 4: 危险删除目标路径 =====
    private static final Set<String> DANGEROUS_REMOVAL_TARGETS;
    static {
        Set<String> targets = new HashSet<>(Set.of(
            "/", "/*", "/etc", "/usr", "/var", "/bin", "/sbin",
            "/boot", "/lib", "/lib64", "/opt", "/root",
            "/System", "/Applications",
            "C:\\", "C:\\Windows", "C:\\Program Files"
        ));
        String home = System.getProperty("user.home");
        if (home != null) { targets.add(home); targets.add(home + "/*"); }
        DANGEROUS_REMOVAL_TARGETS = Collections.unmodifiableSet(targets);
    }

    // ===== Layer 7: 安全环境变量白名单 =====
    private static final Set<String> SAFE_ENV_VARS = Set.of(
        "PATH", "HOME", "USER", "SHELL", "LANG", "LC_ALL",
        "TERM", "EDITOR", "VISUAL", "PAGER",
        "JAVA_HOME", "MAVEN_HOME", "GRADLE_HOME",
        "NODE_PATH", "NPM_CONFIG_PREFIX",
        "PYTHON_PATH", "VIRTUAL_ENV", "CONDA_PREFIX",
        "GIT_AUTHOR_NAME", "GIT_AUTHOR_EMAIL",
        "GIT_COMMITTER_NAME", "GIT_COMMITTER_EMAIL",
        "GOPATH", "GOROOT", "CARGO_HOME", "RUSTUP_HOME",
        "XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_CACHE_HOME",
        "TMPDIR", "TMP", "TEMP",
        "DISPLAY", "WAYLAND_DISPLAY", "COLORTERM", "TERM_PROGRAM"
    );

    // ==================== 读取权限检查 ====================

    /**
     * 验证读取路径安全性
     */
    public PathCheckResult checkReadPermission(String filePath, String workingDirectory) {
        Path resolved = resolvePath(filePath, workingDirectory);
        String resolvedStr = resolved.toString();

        // 1. 设备文件检查 — Layer 1
        if (BLOCKED_DEVICE_PATHS.contains(resolvedStr)) {
            return PathCheckResult.denied("Cannot read device file: " + resolved);
        }

        // 2. /proc 特殊文件检查
        if (resolvedStr.startsWith("/proc/") &&
            (resolvedStr.endsWith("/fd/0") || resolvedStr.endsWith("/fd/1") || resolvedStr.endsWith("/fd/2")
             || resolvedStr.endsWith("/environ"))) {
            return PathCheckResult.denied("Cannot read process special file: " + resolved);
        }

        // 2.5 UNC 路径防护 — Layer 6
        if (filePath.startsWith("//") || filePath.startsWith("\\\\")) {
            return PathCheckResult.denied("UNC path access denied (NTLM credential leak prevention): " + filePath);
        }

        // 3. 项目边界检查
        Path projectRoot = Path.of(workingDirectory).toAbsolutePath().normalize();
        if (!resolved.startsWith(projectRoot)) {
            if (!isAllowedExternalPath(resolved)) {
                return PathCheckResult.denied(
                    "Access denied: path '" + filePath + "' is outside project boundary. " +
                    "Allowed: " + projectRoot);
            }
        }

        // 4. 危险文件警告 — Layer 2
        if (resolved.getFileName() != null) {
            String fileName = resolved.getFileName().toString().toLowerCase();
            if (DANGEROUS_FILES.contains(fileName)) {
                return PathCheckResult.needsConfirmation(
                    "Reading sensitive file: " + fileName);
            }
        }

        return PathCheckResult.allowed();
    }

    // ==================== 写入权限检查 ====================

    /**
     * 验证写入路径安全性 — 比读取更严格。
     */
    public PathCheckResult checkWritePermission(String filePath, String workingDirectory) {
        PathCheckResult readCheck = checkReadPermission(filePath, workingDirectory);
        if (!readCheck.isAllowed() && !readCheck.needsConfirmation()) {
            return readCheck;
        }

        Path resolved = resolvePath(filePath, workingDirectory);

        // 5. 危险目录写入检查 — Layer 2
        for (String dangerDir : DANGEROUS_DIRECTORIES) {
            if (containsPathComponent(resolved, dangerDir)) {
                return PathCheckResult.needsConfirmation(
                    "Writing to protected directory: " + dangerDir);
            }
        }

        // 5.5 符号链接写入检查 — Layer 3
        try {
            if (Files.isSymbolicLink(resolved)) {
                Path realPath = resolved.toRealPath();
                String realStr = realPath.toString();
                if (BLOCKED_DEVICE_PATHS.contains(realStr)) {
                    return PathCheckResult.denied("Symlink targets device file: " + filePath + " -> " + realPath);
                }
                if (realPath.getFileName() != null && DANGEROUS_FILES.contains(realPath.getFileName().toString())) {
                    return PathCheckResult.needsConfirmation("Symlink targets sensitive file: " + filePath + " -> " + realPath);
                }
            }
        } catch (IOException e) {
            // 文件不存在，允许继续
        }

        // 5.6 Windows 路径绕过检测 — Layer 5
        PathCheckResult winCheck = checkWindowsBypass(filePath);
        if (winCheck != null) return winCheck;

        return readCheck;
    }

    // ==================== Layer 4: 危险删除检测 ====================

    /**
     * 检测 Bash 命令中的危险删除操作。
     *
     * @param command Bash 命令字符串
     * @return null=安全, 非 null=拒绝原因
     */
    public String checkDangerousRemoval(String command) {
        if (command == null) return null;
        Matcher m = Pattern.compile(
            "\\b(rm|rmdir)\\s+(?:-[a-zA-Z]{0,10}\\s+){0,5}(\\S+)").matcher(command);
        while (m.find()) {
            String target = m.group(2);
            if (target == null) continue;
            String resolved = resolvePathVariables(target);
            String normTarget = normalizePath(resolved);
            for (String dangerous : DANGEROUS_REMOVAL_TARGETS) {
                if (normTarget.equals(normalizePath(dangerous)))
                    return "Dangerous removal denied: " + command + " (target: " + normTarget + ")";
            }
            if ("*".equals(target) || ".".equals(target) || "..".equals(target))
                return "Wildcard removal denied: " + command;
        }
        return null;
    }

    // ==================== Layer 7: 环境变量检查 ====================

    /**
     * 检测 Bash 命令中的环境变量访问。
     *
     * @param command Bash 命令字符串
     * @return null=安全, "ASK:…"=需用户确认, 其他=硬拒绝
     */
    public String checkEnvVarAccess(String command) {
        if (command == null) return null;
        if (command.matches("^\\s*(env|printenv)\\s*$"))
            return "ASK: Bulk environment export may leak sensitive information";
        Matcher em = Pattern.compile("\\$\\{?(\\w+)\\}?").matcher(command);
        while (em.find()) {
            String var = em.group(1);
            if (!SAFE_ENV_VARS.contains(var)) {
                log.warn("Non-whitelisted env var access: {}", var);
                return "ASK: Command references non-whitelisted env var: $" + var;
            }
        }
        return null;
    }

    // ==================== Layer 5: Windows 路径绕过检测 ====================

    private PathCheckResult checkWindowsBypass(String rawPath) {
        if (!isWindows()) return null;
        // NTFS Alternate Data Streams
        if (rawPath.matches(".*:[^/\\\\].*"))
            return PathCheckResult.denied("NTFS ADS path detected: " + rawPath);
        // 8.3 短文件名
        if (rawPath.matches(".*~\\d.*"))
            return PathCheckResult.denied("8.3 short filename detected: " + rawPath);
        // DOS 设备名
        Path p = Path.of(rawPath);
        if (p.getFileName() != null) {
            String upper = p.getFileName().toString().replaceAll("\\.[^.]*$", "").toUpperCase();
            if (Set.of("CON","PRN","AUX","NUL","COM1","COM2","COM3","COM4",
                    "COM5","COM6","COM7","COM8","COM9","LPT1","LPT2","LPT3",
                    "LPT4","LPT5","LPT6","LPT7","LPT8","LPT9").contains(upper))
                return PathCheckResult.denied("DOS device name detected: " + rawPath);
        }
        return null;
    }

    // ==================== 路径解析 ====================

    /**
     * 解析路径
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

        try {
            resolved = resolved.toRealPath();
        } catch (IOException e) {
            // 文件不存在时 toRealPath 会失败，使用 normalize 结果
        }
        return resolved;
    }

    private boolean isAllowedExternalPath(Path path) {
        String pathStr = path.toString();
        return pathStr.startsWith("/tmp") ||
               pathStr.startsWith(System.getProperty("java.io.tmpdir"));
    }

    private boolean containsPathComponent(Path path, String component) {
        for (Path part : path) {
            if (part.toString().equalsIgnoreCase(component)) return true;
        }
        return false;
    }

    // ==================== 私有工具方法 ====================

    private String normalizePath(String path) {
        if (path == null) return "";
        try {
            return Path.of(path).normalize().toString().replace('\\', '/');
        } catch (InvalidPathException e) {
            return path.replace('\\', '/');
        }
    }

    private String resolvePathVariables(String path) {
        return path.replace("~", System.getProperty("user.home", "/root"))
                   .replace("$HOME", System.getProperty("user.home", "/root"));
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    // ==================== Layer 8: 敏感系统文件读取检测 ====================

    /** 敏感系统文件黑名单 — 即使 BYPASS 模式也不允许通过 Bash 读取 */
    private static final Set<String> SENSITIVE_SYSTEM_FILES = Set.of(
        "/etc/shadow", "/etc/passwd", "/etc/sudoers", "/etc/sudoers.d",
        "/etc/master.passwd", "/etc/security/passwd"
    );

    /** 敏感用户文件前缀（~ 会在运行时展开） */
    private static final List<String> SENSITIVE_USER_PATHS = List.of(
        "~/.ssh/id_rsa", "~/.ssh/id_ed25519", "~/.ssh/id_ecdsa",
        "~/.ssh/id_dsa", "~/.ssh/config",
        "~/.aws/credentials", "~/.aws/config",
        "~/.gnupg/", "~/.kube/config",
        "~/.config/ai-code-assistant/keychain"
    );

    /** 敏感系统目录前缀 */
    private static final List<String> SENSITIVE_SYSTEM_DIRS = List.of(
        "/proc/", "/sys/"
    );

    /** 安全的 /proc 路径白名单 */
    private static final Set<String> SAFE_PROC_PATHS = Set.of(
        "/proc/self/cwd", "/proc/self/exe", "/proc/version",
        "/proc/cpuinfo", "/proc/meminfo", "/proc/loadavg",
        "/proc/uptime", "/proc/filesystems"
    );

    /** 读取类命令 */
    private static final Set<String> READ_COMMANDS = Set.of(
        "cat", "less", "more", "head", "tail", "grep", "egrep", "fgrep",
        "strings", "xxd", "od", "file", "stat", "wc", "awk", "sed",
        "tac", "nl", "sort", "uniq", "cut", "paste", "tr", "fold",
        "hexdump", "base64"
    );

    /**
     * 检测 Bash 命令中是否存在对敏感系统文件的读取操作。
     * <p>
     * 此检查应在 BYPASS 模式之前执行，确保不可绕过。
     *
     * @param command Bash 命令字符串
     * @return null=安全, 非 null=拒绝原因
     */
    public String checkSensitiveFileRead(String command) {
        if (command == null || command.isBlank()) return null;

        String home = System.getProperty("user.home", "/root");

        // 按管道和分号拆分子命令
        String[] subCommands = command.split("[|;&]");
        for (String sub : subCommands) {
            String trimmed = sub.trim();
            if (trimmed.isEmpty()) continue;

            String[] tokens = trimmed.split("\\s+");
            if (tokens.length == 0) continue;

            String cmd = tokens[0];
            // 去除路径前缀（如 /usr/bin/cat）
            int lastSlash = cmd.lastIndexOf('/');
            if (lastSlash >= 0) cmd = cmd.substring(lastSlash + 1);

            if (!READ_COMMANDS.contains(cmd)) continue;

            // 检查该子命令中的每个参数
            for (int i = 1; i < tokens.length; i++) {
                String arg = tokens[i];
                // 跳过选项参数
                if (arg.startsWith("-")) continue;

                // 展开 ~ 和 $HOME
                String expanded = arg.replace("~", home).replace("$HOME", home);

                // 检查敏感系统文件（精确匹配）
                for (String sensitive : SENSITIVE_SYSTEM_FILES) {
                    if (expanded.equals(sensitive)) {
                        return "Sensitive file access denied: " + arg + " (matches blocked path: " + sensitive + ")";
                    }
                }

                // 检查敏感用户文件（前缀匹配）
                for (String userPath : SENSITIVE_USER_PATHS) {
                    String expandedUserPath = userPath.replace("~", home);
                    if (expanded.equals(expandedUserPath) || expanded.startsWith(expandedUserPath)) {
                        return "Sensitive file access denied: " + arg + " (matches blocked path: " + userPath + ")";
                    }
                }

                // 检查敏感系统目录（前缀匹配，排除安全白名单）
                for (String dir : SENSITIVE_SYSTEM_DIRS) {
                    if (expanded.startsWith(dir) || expanded.equals(dir.substring(0, dir.length() - 1))) {
                        // 检查白名单
                        boolean isSafe = SAFE_PROC_PATHS.stream().anyMatch(expanded::equals);
                        if (!isSafe) {
                            return "Sensitive directory access denied: " + arg + " (within restricted area: " + dir + ")";
                        }
                    }
                }
            }
        }
        return null;
    }

    /** 获取安全环境变量白名单（供外部查询） */
    public Set<String> getSafeEnvVars() {
        return SAFE_ENV_VARS;
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
