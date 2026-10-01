package com.aicodeassistant.tool.agent;

import com.aicodeassistant.authorization.AuthorizationSubjectResolver;
import com.aicodeassistant.service.GitService;
import com.aicodeassistant.tool.ToolUseContext;
import com.aicodeassistant.tool.process.ManagedProcessRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Bound Git snapshots; execution, delivery and cleanup are distinct operations. */
@Component
public class WorktreeManager {
    private static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration WRITE_TIMEOUT = Duration.ofSeconds(120);
    private final AuthorizationSubjectResolver subjects;
    private final GitService git;
    private final ManagedProcessRunner processes;
    private final Map<Path, Entry> worktrees = new ConcurrentHashMap<>();
    // Serializes this manager only, not external Git or other tools writing the checkout.
    private final Map<Path, ReentrantLock> targetLocks = new ConcurrentHashMap<>();
    private final Map<Path, String> unsettledOperations = new ConcurrentHashMap<>();
    private final Map<String, List<Runnable>> settledCallbacks = new ConcurrentHashMap<>();

    public WorktreeManager(AuthorizationSubjectResolver subjects, GitService git,
                           ManagedProcessRunner processes) {
        this.subjects = subjects;
        this.git = git;
        this.processes = processes;
    }

    public record ManagedWorktree(Path path, Path workingDirectory, String branch, String warning) { }
    public record DeliveryResult(boolean success, boolean targetMayHaveChanged,
                                 String summary, String cleanupWarning) { }
    public enum PendingDelivery { NONE, PENDING, UNKNOWN }

    private static final class Entry {
        // Delivery waits for virtual Git readers; a monitor would pin their carrier on Java 21.
        final ReentrantLock stateLock = new ReentrantLock();
        final Path root;
        final Path path;
        final Path cwd;
        final String targetRef;
        final String baseline;
        final String agentRef;
        final String sessionId;
        final String runId;
        final boolean manual;
        volatile boolean workerActive;
        volatile boolean attempted;
        volatile boolean delivered;
        volatile boolean targetTouched;
        volatile boolean directoryRemoved;
        volatile String confirmedTip;
        volatile String phase = "creating";
        volatile String detail = "";
        Entry(Path root, Path path, Path cwd, String targetRef, String baseline,
              String agentRef, ToolUseContext context, boolean manual) {
            this.root = root; this.path = path; this.cwd = cwd;
            this.targetRef = targetRef; this.baseline = baseline; this.agentRef = agentRef;
            this.sessionId = context.sessionId(); this.runId = context.currentRunId();
            this.manual = manual;
            this.workerActive = !manual;
        }
    }

    private static final class Operation {
        final Path targetRoot;
        final String owner = "worktree-git-" + UUID.randomUUID();
        final long deadline = System.nanoTime() + OPERATION_TIMEOUT.toNanos();
        int step;
        Operation(Path targetRoot) { this.targetRoot = targetRoot; }
        Duration remaining(Duration maximum) {
            long left = deadline - System.nanoTime();
            if (left <= 0) throw new IllegalStateException("Git operation deadline exceeded");
            return Duration.ofNanos(Math.min(left, maximum.toNanos()));
        }
    }

    public ManagedWorktree createWorktree(String agentId, ToolUseContext context, boolean manual) {
        Path root = authorizedRoot(context);
        Path cwd = realPath(Path.of(context.workingDirectory()));
        if (!cwd.startsWith(root)) throw new IllegalArgumentException("Execution directory is outside the authorized project");
        Operation op = new Operation(root);
        ReentrantLock lock = acquire(root, op);
        Entry entry = null;
        try {
            ensureSettled(root);
            String targetRef = branch(root, op);
            String baseline = commit(root, op, "HEAD");
            ensureNoGitOperation(root, op);
            boolean parentDirty = !status(root, op).isEmpty();
            String suffix = UUID.randomUUID().toString();
            String safeId = agentId == null ? "agent" : agentId.replaceAll("[^A-Za-z0-9_-]", "-");
            if (safeId.length() > 40) safeId = safeId.substring(0, 40);
            if (safeId.isBlank()) safeId = "agent";
            String branchName = "agent-" + safeId + "-" + suffix;
            Path path = realPath(Path.of(System.getProperty("java.io.tmpdir")))
                    .resolve(".zhikun-agent-" + suffix);
            Path execution = path.resolve(root.relativize(cwd));
            entry = new Entry(root, path, execution, targetRef, baseline,
                    "refs/heads/" + branchName, context, manual);
            // Register before starting Git: failure may leave a branch or partially-created directory.
            worktrees.put(path, entry);
            command(root, op, root, OPERATION_TIMEOUT, "worktree", "add", "-b", branchName,
                    path.toString(), baseline);
            validateAgent(entry, op);
            if (!Files.isDirectory(execution)) throw new IllegalStateException("Execution subdirectory is absent from the committed snapshot");
            entry.phase = manual ? "idle" : "executing";
            String warning = parentDirty
                    ? "Snapshot uses committed HEAD only; parent staged, unstaged and untracked content was not copied."
                    : "Snapshot uses the captured committed HEAD.";
            return new ManagedWorktree(path, execution, branchName, warning);
        } catch (RuntimeException failure) {
            if (entry != null) {
                entry.workerActive = false;
                entry.phase = "retained";
                entry.detail = failure.getMessage() == null ? "Creation failed without an error message" : failure.getMessage();
                throw new IllegalStateException("Worktree creation failed. " + location(entry) + " " + entry.detail, failure);
            }
            throw failure;
        } finally {
            lock.unlock();
        }
    }

    public void setWorkerActive(Path path, boolean active) {
        Entry entry = worktrees.get(key(path));
        if (entry == null && !active) return; // Successful cleanup may already have removed the record.
        if (entry == null) throw new IllegalArgumentException("Unknown managed worktree: " + path);
        entry.stateLock.lock();
        try { entry.workerActive = active; }
        finally { entry.stateLock.unlock(); }
    }

    /** No delivery or deletion: callers retain abnormal outcomes and their actual resources. */
    public String retain(Path path, String reason) {
        Entry entry = requireEntry(path);
        entry.stateLock.lock();
        try {
            entry.phase = "retained";
            entry.detail = reason == null ? "Execution did not establish safe delivery" : reason;
            return "Delivery: not confirmed. Cleanup: retained. " + location(entry) + " " + entry.detail;
        } finally { entry.stateLock.unlock(); }
    }

    /** Read-only inspection also works while a normal background service owns the directory. */
    public PendingDelivery inspectPendingDelivery(Path path) {
        Entry entry = worktrees.get(key(path));
        if (entry == null) return PendingDelivery.UNKNOWN;
        Operation op = new Operation(entry.root);
        ReentrantLock lock = null;
        try {
            lock = acquire(entry.root, op);
            ensureSettled(entry.root);
            validateTarget(entry, op);
            String tip = validateAgent(entry, op);
            if (!status(entry.path, op).isEmpty()) return PendingDelivery.PENDING;
            return isAncestor(entry.root, op, tip, "HEAD") ? PendingDelivery.NONE : PendingDelivery.PENDING;
        } catch (RuntimeException unknown) {
            return PendingDelivery.UNKNOWN;
        } finally {
            if (lock != null) lock.unlock();
        }
    }

    /** Only the executor can supply the completed-worker, closed-Run and stopped-background proof. */
    public DeliveryResult finishWorktree(Path path, boolean workerStopped) {
        Entry entry = requireEntry(path);
        Operation op = new Operation(entry.root);
        ReentrantLock lock = acquire(entry.root, op);
        try {
            entry.stateLock.lock();
            try {
                if (!workerStopped) return failure(entry, "Worker/resource termination is not confirmed");
                entry.workerActive = false;
                if (entry.attempted) {
                    return new DeliveryResult(entry.delivered, entry.targetTouched,
                            "No repeated delivery attempted. " + location(entry) + " " + entry.detail,
                            entry.delivered ? entry.detail : "");
                }
                entry.attempted = true;
                ensureSettled(entry.root);
                validateTarget(entry, op);
                ensureNoGitOperation(entry.root, op);
                boolean targetDirty = !status(entry.root, op).isEmpty();
                String originalTarget = commit(entry.root, op, "HEAD");
                String initialTip = validateAgent(entry, op);
                ensureNoGitOperation(entry.path, op);
                boolean agentDirty = !status(entry.path, op).isEmpty();
                if (!agentDirty && isAncestor(entry.root, op, initialTip, "HEAD")) {
                    // No target write is needed; unrelated parent edits do not make execution fail.
                    entry.confirmedTip = initialTip;
                    entry.delivered = true;
                    entry.phase = "delivered";
                    return cleanup(entry, op);
                }
                if (targetDirty) return failure(entry, "Target has uncommitted changes");
                if (agentDirty) {
                    command(entry.root, op, entry.path, WRITE_TIMEOUT, "add", "-A");
                    var staged = execute(entry.root, op, entry.path, READ_TIMEOUT,
                            "diff", "--cached", "--quiet", "--exit-code", "--ignore-submodules=none");
                    if (staged.exitCode() == 1) {
                        command(entry.root, op, entry.path, WRITE_TIMEOUT,
                                "commit", "-m", "Agent work: " + shortBranch(entry));
                    } else if (staged.exitCode() != 0) {
                        throw failedCommand(staged);
                    }
                }
                String tip = validateAgent(entry, op);
                if (!status(entry.path, op).isEmpty()) {
                    return failure(entry, "Worktree still has uncommitted content (including possible submodule content); no recursive commit performed");
                }
                entry.confirmedTip = tip;
                validateTarget(entry, op);
                ensureNoGitOperation(entry.root, op);
                if (!commit(entry.root, op, "HEAD").equals(originalTarget)
                        || !status(entry.root, op).isEmpty()) {
                    return failure(entry, "Target changed while preparing delivery");
                }
                if (!isAncestor(entry.root, op, tip, "HEAD")) {
                    entry.phase = "merging";
                    entry.targetTouched = true;
                    var merge = execute(entry.root, op, entry.root, WRITE_TIMEOUT,
                            "merge", "--ff", "--commit", "--no-squash", "--no-edit", "--no-stat", tip);
                    if (merge.exitCode() != 0) {
                        String recovery = abortOwnedMerge(entry, op, originalTarget, tip);
                        return failure(entry, "Merge failed. " + recovery + " " + diagnostic(merge));
                    }
                    validateTarget(entry, op);
                    ensureNoGitOperation(entry.root, op);
                    if (!isAncestor(entry.root, op, tip, "HEAD") || !status(entry.root, op).isEmpty()) {
                        return failure(entry, "Merge result could not be confirmed clean and integrated; inspect the target");
                    }
                }
                entry.delivered = true;
                entry.phase = "delivered";
                return cleanup(entry, op);
            } finally { entry.stateLock.unlock(); }
        } catch (RuntimeException failure) {
            return failure(entry, failure.getMessage());
        } finally {
            lock.unlock();
        }
    }

    /** Safe explicit cleanup, never an implicit request to merge or discard changes. */
    public DeliveryResult removeWorktree(Path path, ToolUseContext context) {
        Path root = authorizedRoot(context);
        Entry entry = requireEntry(path);
        if (!entry.root.equals(root)) throw new IllegalArgumentException("Worktree belongs to a different project");
        Operation op = new Operation(root);
        ReentrantLock lock = acquire(root, op);
        try {
            entry.stateLock.lock();
            try {
                if (entry.workerActive) return failure(entry, "Worktree has an active worker");
                ensureSettled(root);
                validateTarget(entry, op);
                Map<String, String> manualSessionRuns = null;
                if (!entry.directoryRemoved) {
                    if (entry.manual) manualSessionRuns = ensureManualSessionsIdle(entry, context);
                    String tip = validateAgent(entry, op);
                    ensureNoGitOperation(entry.path, op);
                    if (!status(entry.path, op).isEmpty() || !isAncestor(root, op, tip, "HEAD")) {
                        return failure(entry, "Worktree has uncommitted or undelivered content; explicit remove does not discard it");
                    }
                    entry.confirmedTip = tip;
                }
                entry.delivered = true;
                Map<String, String> expectedRuns = manualSessionRuns;
                return cleanup(entry, op, expectedRuns == null ? null : () -> {
                    if (!expectedRuns.equals(ensureManualSessionsIdle(entry, context))) {
                        throw new IllegalStateException("Related-session Run mapping changed during cleanup; manual worktree retained");
                    }
                });
            } finally { entry.stateLock.unlock(); }
        } catch (RuntimeException failure) {
            return failure(entry, failure.getMessage());
        } finally {
            lock.unlock();
        }
    }

    public String listWorktrees(ToolUseContext context) {
        Path root = authorizedRoot(context);
        Operation op = new Operation(root);
        ReentrantLock lock = acquire(root, op);
        try {
            ensureSettled(root);
            String listing = command(root, op, root, READ_TIMEOUT, "worktree", "list");
            long count = worktrees.values().stream().filter(e -> e.root.equals(root)).count();
            StringBuilder result = new StringBuilder("Git Worktrees:\n").append(listing)
                    .append("\nManaged worktrees in this project: ").append(count);
            worktrees.values().stream().filter(e -> e.root.equals(root)).forEach(e ->
                    result.append("\n").append(e.path).append(" — ").append(e.phase)
                            .append(e.detail.isBlank() ? "" : ": " + e.detail));
            return result.toString();
        } finally {
            lock.unlock();
        }
    }

    /** Revoke parent cache confidence again after an uncertain Git operation really stops. */
    public void whenTargetSettled(Path path, Runnable invalidator) {
        Entry entry = worktrees.get(key(path));
        if (entry == null) { invalidator.run(); return; }
        ReentrantLock lock = acquire(entry.root, new Operation(entry.root));
        String owner;
        try {
            owner = unsettledOperations.get(entry.root);
            if (owner == null) { invalidator.run(); return; }
            settledCallbacks.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(invalidator);
        } finally { lock.unlock(); }
        Thread.ofVirtual().name("worktree-git-settled").start(() -> {
            try {
                while (owner.equals(unsettledOperations.get(entry.root))) {
                    if (processes.currentGitOperation(owner).allTerminated()
                            && lock.tryLock(1, TimeUnit.SECONDS)) {
                        try { ensureSettled(entry.root); }
                        finally { lock.unlock(); }
                    }
                    if (owner.equals(unsettledOperations.get(entry.root))) Thread.sleep(1000);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
    }

    public int getActiveCount() { return worktrees.size(); }

    private Map<String, String> ensureManualSessionsIdle(Entry entry, ToolUseContext context) {
        String callerSession = context.sessionId();
        if (entry.sessionId == null || entry.sessionId.isBlank()
                || callerSession == null || callerSession.isBlank()
                || entry.runId == null || entry.runId.isBlank()
                || context.currentRunId() == null || context.currentRunId().isBlank()) {
            throw new IllegalStateException("Manual worktree session/Run identity is unavailable; occupancy cannot be confirmed");
        }
        // This is deliberately a conservative session check, not a precise cwd index.
        // SubAgent entries use their executor's child-resource proof instead.
        List<String> sessions = entry.sessionId.equals(callerSession)
                ? List.of(entry.sessionId) : List.of(entry.sessionId, callerSession);
        // Also inspect the saved creator/caller Run IDs: a retained process must
        // not disappear from this decision merely because the session changes Run.
        List<String> knownRuns = entry.runId.equals(context.currentRunId())
                ? List.of(entry.runId) : List.of(entry.runId, context.currentRunId());
        for (String run : knownRuns) {
            ManagedProcessRunner.CancelSummary foreground;
            try {
                foreground = processes.currentRunTermination(run);
            } catch (RuntimeException unavailable) {
                throw new IllegalStateException("Related-Run foreground occupancy could not be checked; manual worktree retained", unavailable);
            }
            if (foreground == null || !foreground.allTerminated()) {
                throw new IllegalStateException("Related-Run foreground work is active or unconfirmed; manual worktree occupancy cannot be excluded");
            }
        }
        Map<String, String> observedRuns = new LinkedHashMap<>();
        for (String session : sessions) {
            ManagedProcessRunner.SessionForegroundSnapshot foreground;
            try {
                foreground = processes.currentSessionForeground(session);
            } catch (RuntimeException unavailable) {
                throw new IllegalStateException("Related-session foreground occupancy could not be checked; manual worktree retained", unavailable);
            }
            if (foreground == null || foreground.occupancy() == null || !foreground.occupancy().allTerminated()) {
                throw new IllegalStateException("Related-session foreground work is active or unconfirmed; manual worktree occupancy cannot be excluded");
            }
            observedRuns.put(session, foreground.runId());
            ManagedProcessRunner.CancelSummary background;
            try {
                background = processes.currentSessionBackground(session);
            } catch (RuntimeException unavailable) {
                throw new IllegalStateException("Related-session background occupancy could not be checked; manual worktree retained", unavailable);
            }
            if (background == null || !background.allTerminated()) {
                throw new IllegalStateException("Related-session background work is active or unconfirmed; manual worktree occupancy cannot be excluded");
            }
        }
        return observedRuns;
    }

    private DeliveryResult cleanup(Entry entry, Operation op) {
        return cleanup(entry, op, null);
    }

    private DeliveryResult cleanup(Entry entry, Operation op, Runnable beforeDirectoryRemoval) {
        try {
            ensureSettled(entry.root);
            validateTarget(entry, op);
            if (entry.workerActive || entry.confirmedTip == null
                    || !isAncestor(entry.root, op, entry.confirmedTip, "HEAD")) {
                throw new IllegalStateException("Cleanup prerequisites no longer hold");
            }
            if (!entry.directoryRemoved) {
                if (!validateAgent(entry, op).equals(entry.confirmedTip) || !status(entry.path, op).isEmpty()) {
                    throw new IllegalStateException("Worktree changed after delivery");
                }
                ensureNoGitOperation(entry.path, op);
                // Recheck after Git validation. This observes related sessions; it
                // does not serialize unrelated tools or arbitrary external writers.
                if (beforeDirectoryRemoval != null) beforeDirectoryRemoval.run();
                command(entry.root, op, entry.root, WRITE_TIMEOUT, "worktree", "remove", "--", entry.path.toString());
                entry.directoryRemoved = true;
            }
            if (!commit(entry.root, op, entry.agentRef).equals(entry.confirmedTip)) {
                throw new IllegalStateException("Agent branch changed after delivery");
            }
            command(entry.root, op, entry.root, READ_TIMEOUT, "branch", "-d", "--", shortBranch(entry));
            entry.phase = "removed";
            entry.detail = "Delivery confirmed; worktree and agent branch removed.";
            worktrees.remove(entry.path, entry);
            return new DeliveryResult(true, entry.targetTouched, entry.detail, "");
        } catch (RuntimeException warning) {
            entry.phase = "cleanup-warning";
            entry.detail = warning.getMessage() == null ? "Cleanup failed without an error message" : warning.getMessage();
            return new DeliveryResult(true, entry.targetTouched,
                    "Delivery confirmed. Cleanup incomplete; do not rerun the task or merge again. "
                            + location(entry) + " " + entry.detail, entry.detail);
        }
    }

    private String abortOwnedMerge(Entry entry, Operation op, String originalTarget, String tip) {
        try {
            ensureSettled(entry.root);
            if (!branch(entry.root, op).equals(entry.targetRef)
                    || !commit(entry.root, op, "HEAD").equals(originalTarget)) return "Target identity changed; no abort attempted.";
            Path gitDir = gitDirectory(entry.root, op);
            Path mergeHead = gitDir.resolve("MERGE_HEAD");
            if (!Files.isRegularFile(mergeHead) || !Files.readString(mergeHead).strip().equals(tip)) {
                return "No attributable merge found; target left for inspection.";
            }
            for (String other : List.of("CHERRY_PICK_HEAD", "REVERT_HEAD", "rebase-merge", "rebase-apply", "sequencer")) {
                if (Files.exists(gitDir.resolve(other))) return "Other Git operation detected; no abort attempted.";
            }
            String currentStatus = status(entry.root, op);
            String state = recoveryFingerprint(entry.root, op);
            if (hasUntracked(currentStatus) || !currentStatus.equals(status(entry.root, op))
                    || !state.equals(recoveryFingerprint(entry.root, op))) {
                return "Concurrent or untracked changes detected; no abort attempted.";
            }
            if (!branch(entry.root, op).equals(entry.targetRef)
                    || !commit(entry.root, op, "HEAD").equals(originalTarget)
                    || !Files.readString(mergeHead).strip().equals(tip)) return "Merge ownership changed; no abort attempted.";
            command(entry.root, op, entry.root, WRITE_TIMEOUT, "merge", "--abort");
            ensureNoGitOperation(entry.root, op);
            if (!commit(entry.root, op, "HEAD").equals(originalTarget) || !status(entry.root, op).isEmpty()) {
                return "Abort returned but original clean target was not confirmed.";
            }
            return "The attributable merge was aborted; original clean target confirmed.";
        } catch (Exception failure) {
            return "Merge recovery was not confirmed: " + failure.getMessage();
        }
    }

    private String recoveryFingerprint(Path root, Operation op) {
        return status(root, op) + "\0INDEX\0"
                + command(root, op, root, READ_TIMEOUT, "diff", "--cached", "--ignore-submodules=none", "--no-ext-diff", "--no-textconv", "--binary")
                + "\0WORKTREE\0"
                + command(root, op, root, READ_TIMEOUT, "diff", "--ignore-submodules=none", "--no-ext-diff", "--no-textconv", "--binary");
    }

    private void validateTarget(Entry entry, Operation op) {
        if (!git.isGitRepositoryRoot(entry.root) || !branch(entry.root, op).equals(entry.targetRef)
                || !isAncestor(entry.root, op, entry.baseline, "HEAD")) {
            throw new IllegalStateException("Original target branch/repository/history no longer matches");
        }
    }

    private String validateAgent(Entry entry, Operation op) {
        if (!git.isGitRepositoryRoot(entry.path) || !branch(entry.path, op).equals(entry.agentRef)) {
            throw new IllegalStateException("Managed worktree identity or agent branch changed");
        }
        String tip = commit(entry.path, op, "HEAD");
        if (!commit(entry.root, op, entry.agentRef).equals(tip)
                || !isAncestor(entry.root, op, entry.baseline, tip)) {
            throw new IllegalStateException("Agent history does not descend from its captured baseline");
        }
        return tip;
    }

    private String branch(Path cwd, Operation op) {
        String ref = gitLine(command(cwd, op, cwd, READ_TIMEOUT, "symbolic-ref", "--quiet", "HEAD"));
        if (!ref.startsWith("refs/heads/") || ref.indexOf('\n') >= 0) {
            throw new IllegalStateException("A named target/agent branch is required");
        }
        return ref;
    }

    private String commit(Path cwd, Operation op, String ref) {
        String sha = command(cwd, op, cwd, READ_TIMEOUT, "rev-parse", "--verify", ref + "^{commit}").strip();
        if (!sha.matches("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")) throw new IllegalStateException("Invalid commit identity");
        return sha;
    }

    private boolean isAncestor(Path root, Operation op, String ancestor, String descendant) {
        var result = execute(root, op, root, READ_TIMEOUT, "merge-base", "--is-ancestor", ancestor, descendant);
        if (result.exitCode() == 0) return true;
        if (result.exitCode() == 1) return false;
        throw failedCommand(result);
    }

    private String status(Path cwd, Operation op) {
        String value = command(cwd, op, cwd, READ_TIMEOUT, "status", "--porcelain=v1", "-z",
                "--untracked-files=all", "--ignore-submodules=none");
        int start = 0;
        while (start < value.length()) {
            int end = value.indexOf('\0', start);
            if (end < 0 || end - start < 4 || value.charAt(start + 2) != ' '
                    || " MTADRCU?!".indexOf(value.charAt(start)) < 0
                    || " MTADRCU?!".indexOf(value.charAt(start + 1)) < 0) {
                throw new IllegalStateException("Incomplete or invalid Git status record");
            }
            boolean twoPaths = "RC".indexOf(value.charAt(start)) >= 0
                    || "RC".indexOf(value.charAt(start + 1)) >= 0;
            start = end + 1;
            if (twoPaths) {
                end = value.indexOf('\0', start);
                if (end <= start) throw new IllegalStateException("Incomplete Git rename/copy record");
                start = end + 1;
            }
        }
        return value;
    }

    private static boolean hasUntracked(String status) {
        int start = 0;
        while (start < status.length()) {
            if (status.startsWith("?? ", start)) return true;
            boolean twoPaths = "RC".indexOf(status.charAt(start)) >= 0
                    || "RC".indexOf(status.charAt(start + 1)) >= 0;
            start = status.indexOf('\0', start) + 1;
            if (twoPaths) start = status.indexOf('\0', start) + 1;
        }
        return false;
    }

    /** Remove only Git's record newline; Unicode whitespace can be part of a legal ref/path. */
    private static String gitLine(String output) {
        if (!output.endsWith("\n")) throw new IllegalStateException("Incomplete Git scalar record");
        return output.substring(0, output.length() - 1);
    }

    private Path gitDirectory(Path cwd, Operation op) {
        return Path.of(gitLine(command(cwd, op, cwd, READ_TIMEOUT, "rev-parse", "--absolute-git-dir")));
    }

    private void ensureNoGitOperation(Path cwd, Operation op) {
        Path dir = gitDirectory(cwd, op);
        for (String marker : List.of("MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "REBASE_HEAD",
                "rebase-merge", "rebase-apply", "sequencer")) {
            if (Files.exists(dir.resolve(marker))) throw new IllegalStateException("Existing Git operation: " + marker);
        }
    }

    private String command(Path ownerRoot, Operation op, Path cwd, Duration timeout, String... args) {
        var result = execute(ownerRoot, op, cwd, timeout, args);
        if (result.exitCode() != 0) throw failedCommand(result);
        return result.stdout();
    }

    private ManagedProcessRunner.Result execute(Path ownerRoot, Operation op, Path cwd, Duration timeout, String... args) {
        List<String> command = new ArrayList<>();
        command.add("git"); command.addAll(List.of(args));
        try {
            var result = processes.runRawGit(new ManagedProcessRunner.Request(command, cwd, op.remaining(timeout),
                    op.owner, "git-" + (++op.step), null, ManagedProcessRunner.Ownership.SERVICE));
            if (!result.terminationConfirmed()) {
                unsettledOperations.put(op.targetRoot, op.owner);
                throw new IllegalStateException("Git termination is unconfirmed; target automatic operations are blocked (" + op.owner + ")");
            }
            if (result.timedOut() || result.cancelled() || result.stdoutTruncated() || result.stderrTruncated()) {
                throw new IllegalStateException("Git output or command completion is incomplete; resources retained. " + diagnostic(result));
            }
            return result;
        } catch (IOException | InterruptedException | RuntimeException failure) {
            // A transport/setup exception does not prove that its already-started scope stopped.
            try {
                if (!processes.currentGitOperation(op.owner).allTerminated()) {
                    unsettledOperations.put(op.targetRoot, op.owner);
                }
            } catch (RuntimeException inspectionFailure) {
                unsettledOperations.put(op.targetRoot, op.owner);
                if (inspectionFailure != failure) failure.addSuppressed(inspectionFailure);
            }
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("Git execution failed; state not assumed clean", failure);
        }
    }

    private void ensureSettled(Path root) {
        String owner = unsettledOperations.get(root);
        if (owner != null) {
            if (!processes.currentGitOperation(owner).allTerminated()) {
                throw new IllegalStateException("Earlier Git operation has not stopped; automatic target changes remain blocked: " + owner);
            }
            List<Runnable> callbacks = settledCallbacks.get(owner);
            if (callbacks != null) for (Runnable callback : callbacks) callback.run();
            settledCallbacks.remove(owner);
            unsettledOperations.remove(root, owner);
        }
    }

    private ReentrantLock acquire(Path root, Operation op) {
        ReentrantLock lock = targetLocks.computeIfAbsent(root, ignored -> new ReentrantLock());
        try {
            if (!lock.tryLock(op.remaining(OPERATION_TIMEOUT).toNanos(), TimeUnit.NANOSECONDS)) {
                throw new IllegalStateException("Timed out waiting for target Git operation");
            }
            return lock;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for target Git operation", interrupted);
        }
    }

    private Path authorizedRoot(ToolUseContext context) {
        if (context == null) throw new IllegalArgumentException("A persisted authorization context is required");
        Path root = realPath(subjects.resolve(context.currentRunId()).authorizationRoot());
        return git.findRepositoryRoot(root).filter(root::equals)
                .orElseThrow(() -> new IllegalArgumentException("Authorized project itself must be a validated Git worktree root; no parent fallback"));
    }

    private Entry requireEntry(Path path) {
        Entry entry = worktrees.get(key(path));
        if (entry == null) throw new IllegalArgumentException("Unknown or already removed managed worktree: " + path);
        return entry;
    }

    private static Path realPath(Path path) {
        try { return path.toRealPath(); }
        catch (IOException failure) { throw new IllegalArgumentException("Path cannot be resolved: " + path, failure); }
    }

    private static Path key(Path path) {
        try { return path.toRealPath(); }
        catch (IOException missing) { return path.toAbsolutePath().normalize(); }
    }

    private static String shortBranch(Entry entry) { return entry.agentRef.substring("refs/heads/".length()); }
    private static String location(Entry entry) {
        return (entry.directoryRemoved ? "Worktree directory already removed. "
                : Files.isDirectory(entry.path) ? "Worktree retained at: " + entry.path + ". "
                : "Worktree directory is unavailable at: " + entry.path + "; Git cleanup is not confirmed. ")
                + "Agent branch: " + shortBranch(entry) + ". Target: " + entry.root + " " + entry.targetRef + ".";
    }
    private static String diagnostic(ManagedProcessRunner.Result result) {
        String value = "Git exit " + result.exitCode() + ": " + result.stderr();
        return value.length() > 2000 ? value.substring(0, 2000) : value;
    }
    private static IllegalStateException failedCommand(ManagedProcessRunner.Result result) {
        return new IllegalStateException(diagnostic(result));
    }
    private static DeliveryResult failure(Entry entry, String reason) {
        entry.phase = "retained";
        entry.detail = reason == null ? "Delivery could not be confirmed" : reason;
        return new DeliveryResult(false, entry.targetTouched,
                "Delivery: not confirmed. Cleanup: retained. " + location(entry) + " " + entry.detail, "");
    }
}
