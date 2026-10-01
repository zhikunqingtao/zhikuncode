package com.aicodeassistant.service;

import com.aicodeassistant.authorization.WorkspaceIdentityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/**
 * Git 服务 — 获取 Git 仓库状态。
 * <p>
 * 用于系统提示词中注入当前 Git 状态信息。
 *
 */
@Service
public class GitService {

    private static final Logger log = LoggerFactory.getLogger(GitService.class);
    private final WorkspaceIdentityService workspaceIdentities;

    public GitService() {
        this(new WorkspaceIdentityService());
    }

    @Autowired
    public GitService(WorkspaceIdentityService workspaceIdentities) {
        this.workspaceIdentities = workspaceIdentities;
    }

    /**
     * 获取当前工作目录的 Git 状态摘要。
     *
     * @return Git 状态描述，如 "main (clean)" 或 "feature-branch (+3/-1)"
     */
    public String getGitStatus() {
        return getGitStatus(Path.of(System.getProperty("user.dir")));
    }

    /**
     * 获取指定目录的 Git 状态摘要。
     *
     * @param workingDir 工作目录
     * @return Git 状态描述
     */
    public String getGitStatus(Path workingDir) {
        try {
            String branch = execGit(workingDir, "rev-parse", "--abbrev-ref", "HEAD");
            if (branch == null || branch.isBlank()) {
                return "(not a git repository)";
            }

            String status = execGit(workingDir, "status", "--porcelain");
            if (status == null || status.isBlank()) {
                return branch + " (clean)";
            }

            int added = 0, modified = 0, deleted = 0;
            for (String line : status.split("\n")) {
                if (line.length() >= 2) {
                    char index = line.charAt(0);
                    char worktree = line.charAt(1);
                    if (index == 'A' || worktree == 'A') added++;
                    if (index == 'M' || worktree == 'M') modified++;
                    if (index == 'D' || worktree == 'D') deleted++;
                }
            }

            StringBuilder sb = new StringBuilder(branch);
            sb.append(" (");
            if (added > 0) sb.append("+").append(added);
            if (modified > 0) sb.append("~").append(modified);
            if (deleted > 0) sb.append("-").append(deleted);
            if (added == 0 && modified == 0 && deleted == 0) sb.append("staged");
            sb.append(")");
            return sb.toString();

        } catch (Exception e) {
            log.debug("Failed to get git status: {}", e.getMessage());
            return "(unknown)";
        }
    }

    /** 检查指定目录本身是否为 Git worktree 根。不会向父目录搜索。 */
    public boolean isGitRepository(Path dir) {
        return findRepositoryRoot(dir).isPresent();
    }

    /**
     * Returns {@code dir} only when it is itself a validated worktree root.
     * Deliberately never searches ancestors of the selected Project.
     */
    public Optional<Path> findRepositoryRoot(Path dir) {
        if (dir == null) return Optional.empty();
        try {
            Path canonicalDir = dir.toAbsolutePath().normalize().toRealPath();
            if (!Files.isDirectory(canonicalDir)) return Optional.empty();
            if (!Files.exists(canonicalDir.resolve(".git"),
                    LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
            // The shared validator also requires Git's --show-toplevel to be
            // this exact canonical directory and validates worktree metadata.
            return workspaceIdentities.isValidatedGitRepositoryRoot(canonicalDir)
                    ? Optional.of(canonicalDir) : Optional.empty();
        } catch (Exception unavailable) {
            return Optional.empty();
        }
    }

    /** True only when the authorized directory is exactly a Git worktree root. */
    public boolean isGitRepositoryRoot(Path dir) {
        if (dir == null) return false;
        try {
            Path canonicalDir = dir.toAbsolutePath().normalize().toRealPath();
            return findRepositoryRoot(canonicalDir)
                    .filter(canonicalDir::equals)
                    .isPresent();
        } catch (Exception unavailable) {
            return false;
        }
    }

    /**
     * 执行 Git 命令并返回输出（公开方法，供 Command 调用）。
     *
     * @param workingDir 工作目录
     * @param args       Git 命令参数
     * @return 命令输出，失败返回 null
     */
    public String execGitPublic(Path workingDir, String... args) {
        return execGit(workingDir, args);
    }

    /**
     * Reads stdout as machine-readable text without trimming or normalizing CR/LF/NUL characters.
     * Discards stderr so successful Git warnings cannot become part of a filename.
     * Shares the text API's deadline, complete-output requirement and null-on-failure contract.
     */
    public String execGitRaw(Path workingDir, String... args) {
        return execGit(workingDir, true, args);
    }

    private String execGit(Path workingDir, String... args) {
        return execGit(workingDir, false, args);
    }

    private String execGit(Path workingDir, boolean preserveOutput, String... args) {
        Process process = null;
        ProcessHandle root = null;
        Thread readerThread = null;
        var descendants = new LinkedHashMap<Long, ProcessHandle>();
        boolean succeeded = false;
        boolean interrupted = false;
        try {
            String[] command = new String[args.length + 1];
            command[0] = "git";
            System.arraycopy(args, 0, command, 1, args.length);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.directory(workingDir.toFile());
            pb.redirectErrorStream(!preserveOutput);
            if (preserveOutput) pb.redirectError(ProcessBuilder.Redirect.DISCARD);

            process = pb.start();
            root = process.toHandle();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            Process started = process;
            var output = new FutureTask<>(() -> readOutput(started, preserveOutput));
            readerThread = Thread.ofVirtual().name("git-output-" + process.pid()).start(output);
            process.getOutputStream().close();

            // Drain concurrently: waiting first deadlocks once Git fills its output pipe.
            while (true) {
                rememberDescendants(root, descendants);
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return null;
                if (process.waitFor(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(20)),
                        TimeUnit.NANOSECONDS)) break;
            }
            if (process.exitValue() != 0) return null;
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) return null;
            // A hook child may keep stdout open after Git exits. EOF shares the deadline.
            String result = output.get(remaining, TimeUnit.NANOSECONDS);
            succeeded = true;
            return result;
        } catch (InterruptedException e) {
            interrupted = true;
            return null;
        } catch (Exception e) {
            log.debug("Git execution unavailable: {}", e.getClass().getSimpleName());
            return null;
        } finally {
            if (process != null && !succeeded) {
                interrupted |= Thread.interrupted();
                try {
                    interrupted |= stopFailedCommand(root, descendants, readerThread);
                } catch (RuntimeException unavailable) {
                    if (readerThread != null) readerThread.interrupt();
                    log.debug("Git cleanup unavailable: {}", unavailable.getClass().getSimpleName());
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private String readOutput(Process process, boolean preserveOutput) throws IOException {
        // This thread owns stdout. Closing it from the waiting thread can block on its read lock.
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            var output = new StringBuilder();
            if (preserveOutput) {
                char[] buffer = new char[8192];
                int count;
                while ((count = reader.read(buffer)) != -1) output.append(buffer, 0, count);
                return output.toString();
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (!output.isEmpty()) output.append("\n");
                output.append(line);
            }
            return output.toString().trim();
        }
    }

    private void rememberDescendants(ProcessHandle parent, Map<Long, ProcessHandle> known) {
        if (parent == null) return;
        try {
            // Do not adopt descendants from a PID that has been reused after the parent exited.
            if (!parent.isAlive()) return;
            try (var children = parent.descendants()) {
                var snapshot = children.toList();
                if (parent.isAlive()) snapshot.forEach(child -> known.put(child.pid(), child));
            }
        } catch (RuntimeException unavailable) {
            log.debug("Git descendant enumeration unavailable: {}", unavailable.getClass().getSimpleName());
        }
    }

    /** Best-effort cleanup of this command's known handles; never an unbounded stream close or join. */
    private boolean stopFailedCommand(ProcessHandle root, Map<Long, ProcessHandle> descendants, Thread reader) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        rememberDescendants(root, descendants);
        for (var child : java.util.List.copyOf(descendants.values())) rememberDescendants(child, descendants);
        var owned = new java.util.ArrayList<>(descendants.values());
        if (root != null) owned.add(root);
        for (var handle : owned) {
            try {
                if (handle.isAlive()) handle.destroyForcibly();
            } catch (RuntimeException unavailable) {
                log.debug("Git process cleanup unavailable: {}", unavailable.getClass().getSimpleName());
            }
        }
        if (reader != null) reader.interrupt();
        boolean interrupted = false;
        while (System.nanoTime() < deadline) {
            boolean alive = owned.stream().anyMatch(ProcessHandle::isAlive);
            if (!alive && (reader == null || !reader.isAlive())) return interrupted;
            try {
                TimeUnit.NANOSECONDS.sleep(Math.min(TimeUnit.MILLISECONDS.toNanos(10),
                        Math.max(0, deadline - System.nanoTime())));
            } catch (InterruptedException cancelled) {
                interrupted = true;
            }
        }
        log.debug("Git process or output cleanup was not confirmed within its deadline");
        return interrupted;
    }
}
