package com.aicodeassistant.tool.bash;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Shell 状态管理器 — 跨命令仅持久化工作目录。
 * <p>
 * 每次 Bash 调用都会由子进程继承后端进程的环境。这里不再使用 {@code export -p}
 * 持久化完整环境：完整快照既可能包含凭据，也会因 Shell 序列化差异造成授权指纹漂移。
 * 产品对外约定 Shell 环境不跨调用持久化，因此只保存 CWD，授权分析使用规范化后的实际继承环境。
 */
@Component
public class ShellStateManager {

    private static final Logger log = LoggerFactory.getLogger(ShellStateManager.class);

    private static final Path SHELL_STATE_DIR = Path.of(
            System.getProperty("java.io.tmpdir"), "ai-code-shells");
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
            PosixFilePermissions.fromString("rw-------");

    public ShellStateManager() {
        try {
            Files.createDirectories(SHELL_STATE_DIR);
            setPosixPermissionsIfSupported(SHELL_STATE_DIR, DIRECTORY_PERMISSIONS);
            deleteLegacyEnvironmentSnapshots();
        } catch (IOException e) {
            log.warn("Failed to create shell state directory: {}", SHELL_STATE_DIR, e);
        }
    }

    /** 获取 CWD 跟踪文件路径。 */
    public Path getCwdTrackingPath(String sessionId) {
        return SHELL_STATE_DIR.resolve(sessionId + ".cwd");
    }

    /**
     * 读取跟踪的工作目录 — 如果 CWD 已不存在则回退到 originalCwd。
     */
    public String getTrackedCwd(String sessionId, String originalCwd) {
        Path cwdFile = getCwdTrackingPath(sessionId);
        if (Files.exists(cwdFile)) {
            try {
                String tracked = Files.readString(cwdFile).trim();
                if (!tracked.isEmpty() && Files.isDirectory(Path.of(tracked))) {
                    return tracked;
                }
            } catch (IOException ignored) {
                // fall through to originalCwd
            }
        }
        return originalCwd;
    }

    /**
     * 将用户命令包装为带 CWD 保存的复合脚本。
     * <p>
     * 使用 heredoc + source 模式（而非 eval），避免二次展开风险。
     */
    public String wrapCommand(String userCommand, String sessionId) {
        String cwdFile = getCwdTrackingPath(sessionId).toString();

        // 动态选择 heredoc 终止符 (防御极端边缘情况)
        String heredocDelimiter = "__ZHIKUN_EOF__";
        if (userCommand.contains(heredocDelimiter)) {
            heredocDelimiter = "__ZHIKUN_EOF_" + System.nanoTime() + "__";
        }

        return "umask 077\n"
                + "shopt -u extglob 2>/dev/null || true\n"
                + "__zhikun_cmd=$(mktemp)\n"
                + "__zhikun_cwd=$(mktemp '" + cwdFile + ".XXXXXX')\n"
                + "trap 'rm -f \"$__zhikun_cmd\" \"$__zhikun_cwd\"' EXIT\n"
                + "cat > \"$__zhikun_cmd\" <<'" + heredocDelimiter + "'\n"
                + userCommand + "\n"
                + heredocDelimiter + "\n"
                + "source \"$__zhikun_cmd\"\n"
                + "__zhikun_exit=$?\n"
                + "rm -f \"$__zhikun_cmd\"\n"
                + "pwd > \"$__zhikun_cwd\"\n"
                + "chmod 600 \"$__zhikun_cwd\"\n"
                + "mv -f \"$__zhikun_cwd\" '" + cwdFile + "'\n"
                + "exit $__zhikun_exit";
    }

    /** 确认包装脚本已经完成本次 CWD 状态更新。 */
    public void updateStateFromSnapshot(String sessionId) {
        // CWD 已由包装脚本原子替换；Shell 环境按产品约定不跨调用保存。
        log.debug("Shell state updated for session: {}", sessionId);
    }

    /**
     * 重置 CWD 到原始工作目录。
     */
    public void resetCwd(String sessionId, String originalCwd) {
        try {
            writePrivateFileAtomically(getCwdTrackingPath(sessionId), originalCwd);
        } catch (IOException e) {
            log.warn("Failed to reset CWD for session {}: {}", sessionId, e.getMessage());
        }
    }

    /**
     * 解析工作目录 — 优先使用跟踪目录，回退到原始目录。
     */
    public String resolveWorkingDirectory(String sessionId, String originalCwd) {
        return getTrackedCwd(sessionId, originalCwd);
    }

    /**
     * 返回用于授权指纹的规范化继承环境事实。
     * <p>
     * 返回值只允许在内存中参与哈希，不得记录日志或持久化。PATH 会保留空段代表当前目录的语义，
     * 并去除语义相同的重复段；其余变量保持精确值，使真实环境变化仍会使既有 Bash 授权失效。
     */
    public Map<String, String> authorizationEnvironmentFacts(List<String> inheritedNames) {
        Set<String> names = new LinkedHashSet<>(inheritedNames == null ? List.of() : inheritedNames);
        names.add("PATH");
        Map<String, String> facts = new TreeMap<>();
        Map<String, String> processEnvironment = System.getenv();
        for (String name : names) {
            String value = processEnvironment.get(name);
            if ("PATH".equals(name) && value != null) value = normalizePathForAuthorization(value);
            facts.put(name, value == null ? "<undefined>" : value);
        }
        return Map.copyOf(facts);
    }

    static Path stateDirectory() {
        return SHELL_STATE_DIR;
    }

    static String normalizePathForAuthorization(String value) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String segment : value.split(java.util.regex.Pattern.quote(File.pathSeparator), -1)) {
            // PATH 的空段与显式 "." 都表示当前工作目录，不能当作无意义空白删除。
            if (segment.isEmpty()) {
                normalized.add(".");
                continue;
            }
            try {
                String normalizedSegment = Path.of(segment).normalize().toString();
                normalized.add(normalizedSegment.isEmpty() ? "." : normalizedSegment);
            } catch (RuntimeException invalidPath) {
                // 无法解析的段仍保留原值，避免规范化把不同环境错误合并为同一授权事实。
                normalized.add(segment);
            }
        }
        return String.join(File.pathSeparator, normalized);
    }

    private void deleteLegacyEnvironmentSnapshots() throws IOException {
        int deleted = 0;
        try (var files = Files.newDirectoryStream(SHELL_STATE_DIR, "*.env")) {
            for (Path file : files) {
                if (Files.deleteIfExists(file)) deleted++;
            }
        }
        if (deleted > 0) {
            log.info("Deleted {} legacy shell environment snapshot(s)", deleted);
        }
    }

    private static void writePrivateFileAtomically(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        setPosixPermissionsIfSupported(target.getParent(), DIRECTORY_PERMISSIONS);
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            setPosixPermissionsIfSupported(temporary, FILE_PERMISSIONS);
            Files.writeString(temporary, content);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            setPosixPermissionsIfSupported(target, FILE_PERMISSIONS);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void setPosixPermissionsIfSupported(Path path, Set<PosixFilePermission> permissions)
            throws IOException {
        if (Files.getFileStore(path).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(path, permissions);
        }
    }
}
