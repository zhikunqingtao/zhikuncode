package com.aicodeassistant.tool.process;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A process launched in its own Linux session. Group membership survives the
 * original shell, unlike descendants(). This is lifecycle ownership, not a
 * security sandbox: a program can deliberately escape using setsid/setpgid.
 */
public final class OwnedProcess extends Process {
    private final Process delegate;
    private final ProcIdentity leader;
    private final Map<Long, ProcessHandle> known = new LinkedHashMap<>();
    private boolean groupRetired;
    private boolean terminated;

    private OwnedProcess(Process delegate, ProcIdentity leader) {
        this.delegate = delegate;
        this.leader = leader;
    }

    /** Retains the builder's environment, directory and output redirections. */
    public static OwnedProcess start(ProcessBuilder builder) throws IOException {
        if (!System.getProperty("os.name").startsWith("Linux")) {
            var owned = new OwnedProcess(builder.start(), null);
            try {
                owned.getOutputStream().close();
                return owned;
            } catch (IOException failure) {
                terminateTree(owned, Duration.ZERO);
                throw failure;
            }
        }
        Path setsid = Files.isExecutable(Path.of("/usr/bin/setsid"))
                ? Path.of("/usr/bin/setsid") : Path.of("/bin/setsid");
        if (!Files.isExecutable(setsid) || !Files.isExecutable(Path.of("/bin/bash"))) {
            throw new IOException("PROCESS_GROUP_UNAVAILABLE: setsid and bash are required on Linux");
        }
        if (builder.redirectInput() != ProcessBuilder.Redirect.PIPE) {
            throw new IOException("Owned process requires piped stdin");
        }
        List<String> original = List.copyOf(builder.command());
        var argv = new ArrayList<>(List.of(setsid.toString(), "/bin/bash", "-c",
                "IFS= read -r admission; [ \"$admission\" = start ] || exit 125; exec \"$@\"",
                "zhikun-owned"));
        argv.addAll(original);
        Process process;
        try {
            builder.command(argv);
            process = builder.start();
        } finally {
            builder.command(original);
        }
        OwnedProcess owned = null;
        try {
            // User code cannot execute until we have verified and retained its identity.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            ProcIdentity identity;
            do {
                identity = readIdentity(process.pid());
                if (identity != null && identity.group == process.pid()
                        && identity.session == process.pid()) break;
                if (!process.isAlive() || System.nanoTime() >= deadline) {
                    throw new IOException("PROCESS_GROUP_UNAVAILABLE");
                }
                Thread.sleep(5);
            } while (true);
            owned = new OwnedProcess(process, identity);
            process.getOutputStream().write("start\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            process.getOutputStream().close();
            return owned;
        } catch (IOException | InterruptedException | RuntimeException failure) {
            // write(start) may succeed before close fails; clean the retained scope even then.
            try { process.getOutputStream().close(); } catch (IOException ignored) { }
            if (owned != null) terminateTree(owned, Duration.ZERO);
            else {
                process.destroyForcibly();
                try { process.waitFor(2, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IOException("PROCESS_GROUP_UNAVAILABLE", failure);
        }
    }

    /** Root-first permits uvicorn to close its browser before forced tree cleanup. */
    public synchronized boolean terminate(long deadlineNanos, long graceMillis, boolean gracefulRootFirst) {
        if (terminated) return true;
        boolean interrupted = Thread.interrupted();
        try {
            long graceDeadline = Math.min(deadlineNanos,
                    System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(graceMillis));
            boolean rootSignalled = false;
            boolean inspected = false;
            do {
                List<ProcessHandle> live;
                try {
                    live = liveMembers();
                    inspected = true;
                } catch (IOException | RuntimeException unavailable) {
                    // Never turn an incomplete inspection into successful cleanup.
                    live = known.values().stream().filter(ProcessHandle::isAlive).toList();
                    inspected = false;
                }
                if (gracefulRootFirst && !rootSignalled) {
                    delegate.toHandle().destroy();
                    rootSignalled = true;
                }
                if (inspected && live.isEmpty() && !delegate.isAlive()) {
                    terminated = true;
                    return true;
                }
                boolean force = System.nanoTime() >= graceDeadline;
                if (!gracefulRootFirst || force) {
                    for (ProcessHandle member : live.reversed()) {
                        if (force) member.destroyForcibly(); else member.destroy();
                    }
                }
                if (force) delegate.toHandle().destroyForcibly();
                else if (!gracefulRootFirst) delegate.toHandle().destroy();
                if (System.nanoTime() >= deadlineNanos) break;
                try { Thread.sleep(10); }
                catch (InterruptedException cancelled) { interrupted = true; }
            } while (true);
            return false;
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** Legacy callers still get a retained snapshot and whole-snapshot confirmation. */
    public static boolean terminateTree(Process process, Duration grace) {
        if (process == null) return true;
        OwnedProcess owned = process instanceof OwnedProcess p ? p : new OwnedProcess(process, null);
        return owned.terminate(System.nanoTime() + grace.toNanos() + TimeUnit.SECONDS.toNanos(2),
                grace.toMillis(), false);
    }

    private List<ProcessHandle> liveMembers() throws IOException {
        // Playwright launches Chromium in a separate session. Retain real
        // descendants before signalling Python/Node, not just group members.
        rememberDescendants(delegate.toHandle());
        if (leader != null && !groupRetired) {
            ProcIdentity currentLeader = readIdentity(delegate.pid());
            if (currentLeader != null && currentLeader.startTicks != leader.startTicks) {
                // The original PID/session number has been reused. Never adopt the new group.
                groupRetired = true;
            } else {
                var discovered = new LinkedHashMap<Long, ProcessHandle>();
                try (var entries = Files.newDirectoryStream(Path.of("/proc"), "[0-9]*")) {
                    for (Path entry : entries) {
                        long pid = Long.parseLong(entry.getFileName().toString());
                        ProcIdentity identity = readIdentity(pid);
                        if (identity == null || identity.group != delegate.pid()
                                || identity.session != delegate.pid() || identity.zombie()) continue;
                        ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
                        ProcIdentity after = readIdentity(pid);
                        if (handle != null && after != null && identity.group == after.group
                                && identity.session == after.session && identity.startTicks == after.startTicks) {
                            discovered.put(pid, handle);
                        }
                    }
                }
                currentLeader = readIdentity(delegate.pid());
                if (currentLeader == null || currentLeader.startTicks == leader.startTicks) {
                    discovered.values().forEach(this::remember);
                    if (discovered.isEmpty()) groupRetired = true;
                } else {
                    groupRetired = true;
                }
            }
        }
        // The original shell may already have exited, while its Node driver
        // remains in the owned group with detached browser descendants.
        for (ProcessHandle member : List.copyOf(known.values())) {
            rememberDescendants(member);
        }
        var live = new ArrayList<ProcessHandle>();
        for (ProcessHandle handle : known.values()) {
            // ProcessHandle checks process identity when signalling; never signal a raw saved PGID.
            if (!handle.isAlive()) continue;
            if (System.getProperty("os.name").startsWith("Linux")) {
                ProcIdentity identity = readIdentity(handle.pid());
                if (identity == null || identity.zombie()) continue;
            }
            live.add(handle);
        }
        return live;
    }

    private void rememberDescendants(ProcessHandle parent) {
        // descendants() enumerates by PID, even for a stale handle. isAlive()
        // checks the retained identity; admit the snapshot only if it survives
        // both checks, so a reused parent's children never enter our ownership.
        if (!parent.isAlive()) return;
        List<ProcessHandle> snapshot = parent.descendants().toList();
        if (parent.isAlive()) snapshot.forEach(this::remember);
    }

    private void remember(ProcessHandle handle) {
        known.compute(handle.pid(), (pid, old) -> old == null || !old.isAlive() ? handle : old);
    }

    private static ProcIdentity readIdentity(long pid) throws IOException {
        String value;
        try { value = Files.readString(Path.of("/proc", Long.toString(pid), "stat")); }
        catch (NoSuchFileException disappeared) { return null; }
        // comm can contain spaces and parentheses; fields following its final ')' are numeric/state.
        String[] fields = value.substring(value.lastIndexOf(')') + 2).trim().split("\\s+");
        return new ProcIdentity(Long.parseLong(fields[2]), Long.parseLong(fields[3]),
                Long.parseLong(fields[19]), fields[0]);
    }

    private record ProcIdentity(long group, long session, long startTicks, String state) {
        boolean zombie() { return state.equals("Z") || state.equals("X"); }
    }

    @Override public OutputStream getOutputStream() { return delegate.getOutputStream(); }
    @Override public InputStream getInputStream() { return delegate.getInputStream(); }
    @Override public InputStream getErrorStream() { return delegate.getErrorStream(); }
    @Override public int waitFor() throws InterruptedException {
        while (!waitFor(1, TimeUnit.DAYS)) { /* retain portable descendant snapshots while waiting */ }
        return delegate.exitValue();
    }
    @Override public boolean waitFor(long time, TimeUnit unit) throws InterruptedException {
        if (leader != null) return delegate.waitFor(time, unit);
        // Other platforms keep ProcessHandle best-effort tracking, without a new
        // setsid dependency. This is not the Linux group's reparenting guarantee.
        long deadline = System.nanoTime() + unit.toNanos(time);
        do {
            synchronized (this) {
                try { rememberDescendants(delegate.toHandle()); }
                catch (RuntimeException unavailable) { /* platform capability is exposed by caller */ }
            }
            long left = Math.max(0, deadline - System.nanoTime());
            if (delegate.waitFor(Math.min(left, TimeUnit.MILLISECONDS.toNanos(20)), TimeUnit.NANOSECONDS)) return true;
        } while (System.nanoTime() < deadline);
        return !delegate.isAlive();
    }
    @Override public int exitValue() { return delegate.exitValue(); }
    @Override public void destroy() { delegate.destroy(); }
    @Override public Process destroyForcibly() { delegate.destroyForcibly(); return this; }
    @Override public boolean isAlive() { return delegate.isAlive(); }
    @Override public long pid() { return delegate.pid(); }
    @Override public ProcessHandle toHandle() { return delegate.toHandle(); }
}
