package com.aicodeassistant.tool.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Git Worktree 管理器 — 为隔离模式代理提供独立工作目录。
 * <p>
 * 对照源码 createAgentWorktree() / removeAgentWorktree()。
 * Worktree 生命周期: 创建→代理执行→检查变更→合并/丢弃→清理。
 *
 * @see <a href="SPEC §4.1.1d.3">WorktreeManager</a>
 */
@Component
public class WorktreeManager {

    private static final Logger log = LoggerFactory.getLogger(WorktreeManager.class);
    private static final String WORKTREE_PREFIX = ".claude-agent-";
    private final Map<Path, String> activeWorktrees = new ConcurrentHashMap<>();

    /**
     * 创建 Git Worktree — 在临时目录中创建独立工作副本。
     * <p>
     * 流程:
     * 1. 生成唯一分支名: agent-{agentId}-{timestamp}
     * 2. git worktree add -b {branch} {path} HEAD
     * 3. 注册到活跃 worktree 映射
     *
     * @param agentId 代理唯一标识
     * @return worktree 路径
     */
    public Path createWorktree(String agentId) {
        String branchName = "agent-" + agentId + "-" + System.currentTimeMillis();
        Path worktreePath = Path.of(System.getProperty("java.io.tmpdir"),
                WORKTREE_PREFIX + agentId);

        ProcessBuilder pb = new ProcessBuilder(
                "git", "worktree", "add", "-b", branchName,
                worktreePath.toString(), "HEAD");
        // v1.49.0 修正 (F3-04): 替换 inheritIO() 为输出捕获
        pb.redirectErrorStream(true);

        try {
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            int exitCode = p.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException(
                        "git worktree add failed (exit " + exitCode + "): " + output);
            }
            log.debug("Worktree created: {}", output.trim());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Failed to create worktree", e);
        }

        activeWorktrees.put(worktreePath, branchName);
        return worktreePath;
    }

    /**
     * 检查 Worktree 是否有未提交变更。
     */
    public boolean hasChanges(Path worktreePath) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "-C", worktreePath.toString(), "status", "--porcelain");
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            return !output.isBlank();
        } catch (Exception e) {
            log.warn("Failed to check worktree changes: {}", worktreePath, e);
            return false;
        }
    }

    /**
     * 将 Worktree 变更合并回主分支。
     */
    public void mergeBack(Path worktreePath) {
        String branchName = activeWorktrees.get(worktreePath);
        if (branchName == null) return;

        try {
            // git add + commit in worktree
            exec("git", "-C", worktreePath.toString(), "add", "-A");
            exec("git", "-C", worktreePath.toString(), "commit",
                    "-m", "Agent work: " + branchName);
            // v1.49.0 修正 (F5-05): 显式指定主仓库路径进行 merge
            String mainRepoPath = System.getProperty("user.dir");
            // 获取原始分支名 (merge 目标)
            String originalBranch = execCapture("git", "-C", mainRepoPath,
                    "rev-parse", "--abbrev-ref", "HEAD");
            // 确保 HEAD 在主仓库的原始分支上
            exec("git", "-C", mainRepoPath, "checkout", originalBranch.trim());
            // merge agent 分支到主仓库原始分支
            exec("git", "-C", mainRepoPath, "merge", branchName, "--no-edit");
        } catch (Exception e) {
            log.warn("Worktree merge failed, changes preserved in {}", worktreePath, e);
        }
    }

    /**
     * 移除 Worktree 并清理临时分支。
     */
    public void removeWorktree(Path worktreePath) {
        String branchName = activeWorktrees.remove(worktreePath);
        try {
            exec("git", "worktree", "remove", "--force", worktreePath.toString());
            if (branchName != null) {
                exec("git", "branch", "-D", branchName);
            }
        } catch (Exception e) {
            log.warn("Worktree cleanup failed: {}", worktreePath, e);
        }
    }

    /**
     * 获取活跃 worktree 数量。
     */
    public int getActiveCount() {
        return activeWorktrees.size();
    }

    // v1.49.0 修正 (F3-04): 替换 inheritIO 为输出捕获 + 日志记录
    private void exec(String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes());
        int exitCode = p.waitFor();
        if (exitCode != 0) {
            throw new IOException("Command failed (exit %d): %s → %s".formatted(
                    exitCode, String.join(" ", cmd), output));
        }
    }

    // v1.49.0 新增: 捕获命令输出并返回
    private String execCapture(String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes());
        int exitCode = p.waitFor();
        if (exitCode != 0) {
            throw new IOException("Command failed (exit %d): %s → %s".formatted(
                    exitCode, String.join(" ", cmd), output));
        }
        return output;
    }
}
