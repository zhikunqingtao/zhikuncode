package com.aicodeassistant.tool.bash;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Shell 状态管理器 — 跨命令持久化环境变量和工作目录。
 * <p>
 * 设计思路 (对照源码 ShellProvider + bashProvider):
 * 源码在每次执行前 source 一个 env snapshot 文件，执行后 export 到同一文件。
 * Java 端同理: 将用户命令包装在一个复合脚本中，实现状态捕获与恢复。
 *
 * @see <a href="SPEC §3.2.3">BashTool Shell 状态持久化</a>
 */
@Component
public class ShellStateManager {

    private static final Logger log = LoggerFactory.getLogger(ShellStateManager.class);

    private static final Path SHELL_STATE_DIR = Path.of(
            System.getProperty("java.io.tmpdir"), "ai-code-shells");

    public ShellStateManager() {
        try {
            Files.createDirectories(SHELL_STATE_DIR);
        } catch (IOException e) {
            log.warn("Failed to create shell state directory: {}", SHELL_STATE_DIR, e);
        }
    }

    /** 获取会话的环境快照文件路径。 */
    public Path getSnapshotPath(String sessionId) {
        return SHELL_STATE_DIR.resolve(sessionId + ".env");
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
     * 将用户命令包装为带状态恢复/保存的复合脚本。
     * <p>
     * 使用 heredoc + source 模式（而非 eval），避免二次展开风险。
     */
    public String wrapCommand(String userCommand, String sessionId) {
        String snapshotFile = getSnapshotPath(sessionId).toString();
        String cwdFile = getCwdTrackingPath(sessionId).toString();

        // 动态选择 heredoc 终止符 (防御极端边缘情况)
        String heredocDelimiter = "__CLAUDE_EOF__";
        if (userCommand.contains(heredocDelimiter)) {
            heredocDelimiter = "__CLAUDE_EOF_" + System.nanoTime() + "__";
        }

        return "source '" + snapshotFile + "' 2>/dev/null || true\n"
                + "shopt -u extglob 2>/dev/null || true\n"
                + "__claude_cmd=$(mktemp)\n"
                + "trap 'rm -f \"$__claude_cmd\"' EXIT\n"
                + "cat > \"$__claude_cmd\" <<'" + heredocDelimiter + "'\n"
                + userCommand + "\n"
                + heredocDelimiter + "\n"
                + "source \"$__claude_cmd\"\n"
                + "__claude_exit=$?\n"
                + "rm -f \"$__claude_cmd\"\n"
                + "pwd > '" + cwdFile + "'\n"
                + "export -p > '" + snapshotFile + "'\n"
                + "exit $__claude_exit";
    }

    /**
     * 解析快照文件更新状态。
     */
    public void updateStateFromSnapshot(String sessionId) {
        // CWD 更新由快照文件自动完成 (pwd > cwdFile)
        // 环境变量更新在下次 wrapCommand 时自动 source
        log.debug("Shell state updated for session: {}", sessionId);
    }

    /**
     * 重置 CWD 到原始工作目录。
     */
    public void resetCwd(String sessionId, String originalCwd) {
        try {
            Files.writeString(getCwdTrackingPath(sessionId), originalCwd);
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
}
