package com.aicodeassistant.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 统一敏感路径注册中心 — 集中管理所有受保护路径。
 * <p>
 * 四级保护体系：
 * <ul>
 *   <li>{@link ProtectionLevel#FORBIDDEN} — 绝不允许（读写均禁止）</li>
 *   <li>{@link ProtectionLevel#PROTECTED} — 写操作需确认</li>
 *   <li>{@link ProtectionLevel#AUDIT} — 记录访问日志</li>
 *   <li>{@link ProtectionLevel#ALLOWED} — 允许访问</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(
        name = "security.enhanced-blacklist.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class SensitivePathRegistry {

    private static final Logger log = LoggerFactory.getLogger(SensitivePathRegistry.class);

    /**
     * 路径保护级别。
     */
    public enum ProtectionLevel {
        FORBIDDEN,
        PROTECTED,
        AUDIT,
        ALLOWED
    }

    /**
     * 操作类型。
     */
    public enum OperationType {
        READ,
        WRITE,
        DELETE
    }

    /**
     * 路径检查结果。
     */
    public record PathCheckResult(ProtectionLevel level, String path, String reason) {
        public boolean blocked() {
            return level == ProtectionLevel.FORBIDDEN;
        }
        public boolean needsConfirmation() {
            return level == ProtectionLevel.PROTECTED;
        }
        public static PathCheckResult allowed() {
            return new PathCheckResult(ProtectionLevel.ALLOWED, null, null);
        }
        public static PathCheckResult forbidden(String path, String reason) {
            return new PathCheckResult(ProtectionLevel.FORBIDDEN, path, reason);
        }
        public static PathCheckResult protect(String path, String reason) {
            return new PathCheckResult(ProtectionLevel.PROTECTED, path, reason);
        }
        public static PathCheckResult audit(String path, String reason) {
            return new PathCheckResult(ProtectionLevel.AUDIT, path, reason);
        }
    }

    private record PathRule(String pathPattern, ProtectionLevel readLevel, ProtectionLevel writeLevel, String description) {}

    private static final String HOME = System.getProperty("user.home", "/root");

    // ===== 内置保护路径规则 =====

    private static final List<PathRule> PATH_RULES = new ArrayList<>();

    static {
        // ── FORBIDDEN（读写均禁止）──

        // SSH 密钥
        for (String p : List.of(
                "~/.ssh/id_rsa", "~/.ssh/id_ed25519", "~/.ssh/id_ecdsa",
                "~/.ssh/id_dsa", "~/.ssh/config", "~/.ssh/known_hosts",
                "~/.ssh/authorized_keys")) {
            PATH_RULES.add(new PathRule(p, ProtectionLevel.FORBIDDEN, ProtectionLevel.FORBIDDEN, "SSH key/config"));
        }

        // 云凭证
        for (String p : List.of(
                "~/.aws/credentials", "~/.aws/config",
                "~/.config/gcloud/credentials.db",
                "~/.azure/accessTokens.json",
                "~/.kube/config", "~/.docker/config.json")) {
            PATH_RULES.add(new PathRule(p, ProtectionLevel.FORBIDDEN, ProtectionLevel.FORBIDDEN, "Cloud credentials"));
        }

        // GPG 密钥目录
        PATH_RULES.add(new PathRule("~/.gnupg/", ProtectionLevel.FORBIDDEN, ProtectionLevel.FORBIDDEN, "GPG keyring"));

        // 包管理 Token
        for (String p : List.of("~/.npmrc", "~/.yarnrc", "~/.pypirc", "~/.git-credentials")) {
            PATH_RULES.add(new PathRule(p, ProtectionLevel.FORBIDDEN, ProtectionLevel.FORBIDDEN, "Package manager token"));
        }

        // 数据库凭证
        for (String p : List.of("~/.pgpass", "~/.my.cnf", "~/.netrc")) {
            PATH_RULES.add(new PathRule(p, ProtectionLevel.FORBIDDEN, ProtectionLevel.FORBIDDEN, "Database credentials"));
        }

        // 运行时数据
        for (String p : List.of("/proc/*/environ", "/proc/*/mem")) {
            PATH_RULES.add(new PathRule(p, ProtectionLevel.FORBIDDEN, ProtectionLevel.FORBIDDEN, "Process runtime data"));
        }
        for (String p : List.of("/dev/sd*", "/dev/nvme*")) {
            PATH_RULES.add(new PathRule(p, ProtectionLevel.FORBIDDEN, ProtectionLevel.FORBIDDEN, "Block device"));
        }

        // ── 系统配置（读允许，写禁止）──
        for (String p : List.of("/etc/shadow", "/etc/passwd", "/etc/sudoers", "/etc/ssh/sshd_config")) {
            PATH_RULES.add(new PathRule(p, ProtectionLevel.AUDIT, ProtectionLevel.FORBIDDEN, "System configuration"));
        }

        // ── PROTECTED（写操作需确认）──

        // Shell 配置
        for (String p : List.of("~/.bashrc", "~/.zshrc", "~/.profile", "~/.bash_profile", "~/.zprofile")) {
            PATH_RULES.add(new PathRule(p, ProtectionLevel.ALLOWED, ProtectionLevel.PROTECTED, "Shell configuration"));
        }

        // 项目敏感文件（相对路径匹配）
        for (String p : List.of(".env", ".env.local", ".env.production", ".mcp.json")) {
            PATH_RULES.add(new PathRule(p, ProtectionLevel.ALLOWED, ProtectionLevel.PROTECTED, "Project sensitive file"));
        }

        // .git 目录
        PATH_RULES.add(new PathRule(".git/", ProtectionLevel.ALLOWED, ProtectionLevel.PROTECTED, "Git repository internals"));
    }

    private final SecurityAuditLogger auditLogger;

    public SensitivePathRegistry(SecurityAuditLogger auditLogger) {
        this.auditLogger = auditLogger;
    }

    // ===== 核心检查方法 =====

    /**
     * 检查路径和操作是否受保护。
     *
     * @param path      目标路径
     * @param operation 操作类型
     * @param cwd       当前工作目录（用于相对路径解析）
     * @return 路径检查结果
     */
    public PathCheckResult checkPath(String path, OperationType operation, Path cwd) {
        if (path == null || path.isBlank()) {
            return PathCheckResult.allowed();
        }

        // 展开 ~ 和 $HOME
        String expanded = expandPath(path);

        // 解析为绝对路径
        String absolutePath;
        try {
            Path p = Path.of(expanded);
            if (p.isAbsolute()) {
                absolutePath = p.normalize().toString();
            } else if (cwd != null) {
                absolutePath = cwd.resolve(expanded).normalize().toString();
            } else {
                absolutePath = expanded;
            }
        } catch (Exception e) {
            absolutePath = expanded;
        }

        for (PathRule rule : PATH_RULES) {
            if (matchesRule(absolutePath, path, rule)) {
                ProtectionLevel level = (operation == OperationType.READ)
                        ? rule.readLevel()
                        : rule.writeLevel();

                if (level == ProtectionLevel.ALLOWED) continue;

                PathCheckResult result;
                if (level == ProtectionLevel.FORBIDDEN) {
                    result = PathCheckResult.forbidden(path, rule.description() + ": " + path);
                } else if (level == ProtectionLevel.PROTECTED) {
                    result = PathCheckResult.protect(path, rule.description() + ": " + path);
                } else {
                    result = PathCheckResult.audit(path, rule.description() + ": " + path);
                }

                if (level == ProtectionLevel.FORBIDDEN || level == ProtectionLevel.PROTECTED) {
                    auditLogger.logPathAccess(path, result);
                }

                return result;
            }
        }

        return PathCheckResult.allowed();
    }

    /**
     * 从 Bash 命令中提取路径并检查。
     * 返回第一个被拦截路径的原因字符串，或 null 表示安全。
     *
     * @param command Bash 命令字符串
     * @return 拒绝原因或 null
     */
    public String checkCommandPaths(String command) {
        if (command == null || command.isBlank()) return null;

        List<String> paths = extractPathsFromCommand(command);
        for (String path : paths) {
            PathCheckResult result = checkPath(path, OperationType.READ, null);
            if (result.blocked()) {
                return result.reason();
            }
        }
        return null;
    }

    // ===== 辅助方法 =====

    private String expandPath(String path) {
        return path.replace("~", HOME).replace("$HOME", HOME);
    }

    private boolean matchesRule(String absolutePath, String rawPath, PathRule rule) {
        String ruleExpanded = expandPath(rule.pathPattern());

        // 通配符匹配
        if (ruleExpanded.contains("*")) {
            String regex = ruleExpanded.replace(".", "\\.")
                    .replace("*", "[^/]*");
            if (absolutePath.matches(regex) || rawPath.matches(regex)) {
                return true;
            }
            // 也尝试前缀匹配（对于目录通配）
            String prefix = ruleExpanded.substring(0, ruleExpanded.indexOf('*'));
            return absolutePath.startsWith(prefix) || expandPath(rawPath).startsWith(prefix);
        }

        // 目录匹配（以 / 结尾的规则）
        if (ruleExpanded.endsWith("/")) {
            return absolutePath.startsWith(ruleExpanded)
                    || expandPath(rawPath).startsWith(ruleExpanded)
                    || absolutePath.contains("/" + rule.pathPattern().replace("~/", "").replace("/", "") + "/");
        }

        // 精确匹配
        if (absolutePath.equals(ruleExpanded) || expandPath(rawPath).equals(ruleExpanded)) {
            return true;
        }

        // 相对路径匹配（项目文件如 .env）
        if (!rule.pathPattern().startsWith("/") && !rule.pathPattern().startsWith("~")) {
            String fileName = rawPath;
            int lastSlash = rawPath.lastIndexOf('/');
            if (lastSlash >= 0) {
                fileName = rawPath.substring(lastSlash + 1);
            }
            // .git/ 规则匹配路径中包含 .git/ 的情况
            if (rule.pathPattern().endsWith("/")) {
                String dirName = rule.pathPattern().substring(0, rule.pathPattern().length() - 1);
                return rawPath.contains("/" + dirName + "/") || rawPath.startsWith(dirName + "/");
            }
            return fileName.equals(rule.pathPattern());
        }

        return false;
    }

    /** 从命令中提取可能的路径参数 */
    private static final Pattern PATH_ARG_PATTERN = Pattern.compile(
            "(?:^|\\s)([~/.$][^\\s|;&><]+|/[^\\s|;&><]+)");

    private List<String> extractPathsFromCommand(String command) {
        List<String> paths = new ArrayList<>();
        Matcher m = PATH_ARG_PATTERN.matcher(command);
        while (m.find()) {
            String path = m.group(1).trim();
            if (!path.isEmpty() && !path.startsWith("-")) {
                paths.add(path);
            }
        }
        return paths;
    }
}
