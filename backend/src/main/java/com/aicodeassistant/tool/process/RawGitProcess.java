package com.aicodeassistant.tool.process;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * RawGit's foreground process and retained scope have different lifetimes.
 * Linux keeps OwnedProcess's session ownership. On macOS a parked shell pins
 * the group until all ordinary hook descendants have exited, including those
 * already reparented before the first Java observation. Deliberate setpgid or
 * setsid escape is outside this lifecycle boundary, as it is on Linux.
 */
abstract class RawGitProcess extends Process {
    abstract OwnedProcess.ScopeSnapshot observeScope();
    abstract boolean terminate(long deadline, long graceMillis, boolean rootFirst);

    static RawGitProcess start(ProcessBuilder builder) throws IOException {
        String platform = System.getProperty("os.name");
        if (platform.startsWith("Mac")) return Mac.start(builder);
        // Other platforms retain their existing OwnedProcess behavior. This
        // local macOS repair does not add new platform requirements to RawGit.
        try { return new Existing(OwnedProcess.start(builder)); }
        catch (OwnedProcess.LaunchFailure failure) {
            throw new LaunchFailure(new Existing(failure.retainedProcess()), failure);
        }
    }

    static final class LaunchFailure extends IOException {
        private final RawGitProcess retainedProcess;
        LaunchFailure(RawGitProcess process, Throwable cause) {
            super("PROCESS_LAUNCH_CLEANUP_UNCONFIRMED", cause);
            retainedProcess = process;
        }
        RawGitProcess retainedProcess() { return retainedProcess; }
    }

    private static final class Existing extends RawGitProcess {
        private final OwnedProcess process;
        private Existing(OwnedProcess process) { this.process = process; }
        @Override OwnedProcess.ScopeSnapshot observeScope() { return process.observeScope(); }
        @Override boolean terminate(long deadline, long grace, boolean rootFirst) {
            return process.terminate(deadline, grace, rootFirst);
        }
        @Override public InputStream getInputStream() { return process.getInputStream(); }
        @Override public InputStream getErrorStream() { return process.getErrorStream(); }
        @Override public OutputStream getOutputStream() { return process.getOutputStream(); }
        @Override public int waitFor() throws InterruptedException { return process.waitFor(); }
        @Override public boolean waitFor(long time, TimeUnit unit) throws InterruptedException {
            return process.waitFor(time, unit);
        }
        @Override public int exitValue() { return process.exitValue(); }
        @Override public boolean isAlive() { return process.isAlive(); }
        @Override public long pid() { return process.pid(); }
        @Override public ProcessHandle toHandle() { return process.toHandle(); }
        @Override public void destroy() { process.destroy(); }
        @Override public Process destroyForcibly() { process.destroyForcibly(); return this; }
    }

    private static final class Mac extends RawGitProcess {
        private static final long INSPECTION_NANOS = TimeUnit.SECONDS.toNanos(2);
        private static final int MAX_PS_BYTES = 1024 * 1024;
        // -p suppresses BASH_ENV, imported functions and shell options for our
        // wrappers without changing the environment passed to the actual Git.
        private static final String LAUNCH = """
                set -m
                script=$1
                shift
                /bin/bash -p -c "$script" zhikun-git-scope "$@" <&0 &
                child=$!
                exec </dev/null >/dev/null 2>&1
                wait "$child"
                """;
        private static final String SUPERVISE = """
                set +m
                control=$1
                shift
                printf '%s\\n' "$$" > "$control/anchor"
                IFS= read -r admission && [ "$admission" = prepare ] || exit 125
                /bin/bash -p -c 'IFS= read -r admission && [ "$admission" = run ] || exit 125; exec "$@" </dev/null' zhikun-git-command "$@" <&0 &
                child=$!
                printf '%s\\n' "$child" > "$control/foreground"
                wait "$child"
                result=$?
                exec >/dev/null 2>&1
                printf '%s\\n' "$result" > "$control/exit"
                IFS= read -r release && [ "$release" = release ] || exit 125
                """;

        private final Process launcher;
        private final Path control;
        private final Map<Long, ProcessHandle> known = new LinkedHashMap<>();
        // Inspection waits for a virtual reader; a monitor would pin its caller's carrier.
        private final ReentrantLock scopeLock = new ReentrantLock();
        private ProcessHandle anchor;
        private ProcessHandle foreground;
        private volatile Integer exit;
        private volatile boolean protocolFailed;
        private boolean admitted;
        private boolean startupAborted;
        private boolean cancellationClaimed;
        private boolean releasing;
        private boolean retired;
        private boolean anchorLost;

        private Mac(Process launcher, Path control) {
            this.launcher = launcher;
            this.control = control;
        }

        static Mac start(ProcessBuilder builder) throws IOException {
            if (!Files.isExecutable(Path.of("/bin/bash")) || !Files.isExecutable(Path.of("/bin/ps")))
                throw new IOException("RAW_GIT_PROCESS_SCOPE_UNAVAILABLE: bash and ps are required");
            if (builder.redirectInput() != ProcessBuilder.Redirect.PIPE)
                throw new IOException("Raw Git requires piped admission");
            Path control = Files.createTempDirectory("zhikun-raw-git-");
            List<String> original = List.copyOf(builder.command());
            var command = new ArrayList<>(List.of("/bin/bash", "-p", "-c", LAUNCH,
                    "zhikun-git-launch", SUPERVISE, control.toString()));
            command.addAll(original);
            Mac process = null;
            try {
                builder.command(command);
                process = new Mac(builder.start(), control);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                process.anchor = process.awaitIdentity("anchor", deadline);
                process.requireMember(process.anchor, deadline);
                process.send("prepare");
                process.foreground = process.awaitIdentity("foreground", deadline);
                process.requireMember(process.foreground, deadline);
                process.known.put(process.foreground.pid(), process.foreground);
                // A failed write may already have delivered admission. Retain the
                // entire scope on any subsequent failure, never just the launcher.
                process.admitted = true;
                process.send("run");
                return process;
            } catch (IOException | InterruptedException | RuntimeException failure) {
                boolean interrupted = Thread.interrupted();
                boolean stopped = process == null;
                if (process != null) {
                    if (!process.admitted) process.abortStartup();
                    stopped = process.terminate(System.nanoTime() + INSPECTION_NANOS, 0, false);
                } else deleteControl(control);
                if (interrupted || failure instanceof InterruptedException) Thread.currentThread().interrupt();
                if (!stopped) throw new LaunchFailure(process, failure);
                throw new IOException("RAW_GIT_PROCESS_SCOPE_UNAVAILABLE", failure);
            } finally { builder.command(original); }
        }

        private ProcessHandle awaitIdentity(String name, long deadline) throws IOException, InterruptedException {
            while (System.nanoTime() < deadline) {
                String record = record(name);
                if (record != null) {
                    try {
                        long pid = Long.parseLong(record);
                        ProcessHandle handle = ProcessHandle.of(pid).orElseThrow(() ->
                                new IOException("Raw Git " + name + " exited before admission"));
                        if (pid <= 1 || !handle.isAlive() || handle.info().startInstant().isEmpty())
                            throw new IOException("Raw Git " + name + " identity is unavailable");
                        return handle;
                    } catch (NumberFormatException invalid) {
                        throw new IOException("Invalid Raw Git identity", invalid);
                    }
                }
                if (!launcher.isAlive()) throw new IOException("Raw Git supervisor exited before admission");
                Thread.sleep(5);
            }
            throw new IOException("Raw Git admission deadline");
        }

        private void requireMember(ProcessHandle member, long deadline) throws IOException, InterruptedException {
            Group group = group(deadline);
            if (!member.isAlive() || group.members.stream().noneMatch(row -> row.pid == member.pid()))
                throw new IOException("Raw Git process group identity is unavailable");
        }

        private void send(String message) throws IOException {
            launcher.getOutputStream().write((message + "\n").getBytes(StandardCharsets.US_ASCII));
            launcher.getOutputStream().flush();
        }

        private String record(String name) throws IOException {
            Path file = control.resolve(name);
            if (!Files.exists(file)) return null;
            byte[] bytes;
            try (InputStream input = Files.newInputStream(file)) { bytes = input.readNBytes(65); }
            if (bytes.length > 64) throw new IOException("Oversized Raw Git control record");
            String value = new String(bytes, StandardCharsets.US_ASCII);
            // A regular file can be seen between open(O_TRUNC) and printf's write.
            if (!value.endsWith("\n")) return null;
            if (!value.matches("[0-9]+\n")) throw new IOException("Invalid Raw Git control record");
            return value.substring(0, value.length() - 1);
        }

        private Integer foregroundExit() {
            if (exit != null) return exit;
            try {
                String record = record("exit");
                if (record != null) {
                    int value = Integer.parseInt(record);
                    if (value > 255) throw new IOException("Invalid Raw Git exit code");
                    exit = value;
                }
            } catch (IOException | RuntimeException unavailable) { protocolFailed = true; }
            return exit;
        }

        private void abortStartup() {
            startupAborted = true;
            try { launcher.getOutputStream().close(); } catch (IOException ignored) { }
        }

        @Override OwnedProcess.ScopeSnapshot observeScope() {
            scopeLock.lock();
            try {
                if (retired) return new OwnedProcess.ScopeSnapshot(0, true);
                return observe(System.nanoTime() + INSPECTION_NANOS);
            } catch (IOException | RuntimeException unavailable) { return new OwnedProcess.ScopeSnapshot(0, false); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return new OwnedProcess.ScopeSnapshot(0, false);
            }
            finally { scopeLock.unlock(); }
        }

        private OwnedProcess.ScopeSnapshot observe(long deadline) throws IOException, InterruptedException {
            if (startupAborted && !admitted) {
                // Both wrapper waits completed normally after EOF at an admission
                // gate. No user command was ever allowed to start.
                if (launcher.waitFor(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
                        && launcher.exitValue() == 125 && (anchor == null || !anchor.isAlive())
                        && (foreground == null || !foreground.isAlive())) return retire();
                return new OwnedProcess.ScopeSnapshot(1, false);
            }
            if (releasing) {
                if (launcher.waitFor(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
                        && !anchor.isAlive()) return retire();
                return new OwnedProcess.ScopeSnapshot(1, false);
            }
            Group group = group(deadline);
            int writers = (int) group.members.stream().filter(row -> row.pid != anchor.pid() && !row.zombie()).count();
            if (writers != 0) return new OwnedProcess.ScopeSnapshot(writers, true);
            if (foregroundExit() == null || protocolFailed || foreground == null || foreground.isAlive())
                return new OwnedProcess.ScopeSnapshot(0, false);
            // The retained, verified anchor is now the only group member able to
            // run. It launches nothing after Git exits. Release just this helper.
            releasing = true;
            send("release");
            launcher.getOutputStream().close();
            return observe(deadline);
        }

        private OwnedProcess.ScopeSnapshot retire() {
            retired = true;
            deleteControl(control);
            return new OwnedProcess.ScopeSnapshot(0, true);
        }

        /** A full process-table observation, made outside the owned group. */
        private Group group(long deadline) throws IOException, InterruptedException {
            if (anchorLost || anchor == null || !anchor.isAlive()) {
                anchorLost = true;
                throw new IOException("Raw Git group anchor is unavailable");
            }
            Process ps = new ProcessBuilder("/bin/ps", "-axo", "pid=,pgid=,stat=")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            CompletableFuture<byte[]> output = new CompletableFuture<>();
            try {
                ps.getOutputStream().close();
                Thread.ofVirtual().name("raw-git-group-inspection").start(() -> {
                    try { output.complete(ps.getInputStream().readNBytes(MAX_PS_BYTES + 1)); }
                    catch (Throwable failure) { output.completeExceptionally(failure); }
                });
                if (!ps.waitFor(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
                        || ps.exitValue() != 0) throw new IOException("Raw Git process group inspection failed");
                byte[] bytes;
                try { bytes = output.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
                catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
                    throw new IOException("Raw Git process group inspection incomplete", failure);
                }
                if (bytes.length > MAX_PS_BYTES) throw new IOException("Raw Git process table was truncated");
                var members = new ArrayList<Member>();
                for (String line : new String(bytes, StandardCharsets.US_ASCII).split("\n")) {
                    if (line.isBlank()) continue;
                    String[] values = line.strip().split("\\s+");
                    if (values.length != 3) throw new IOException("Invalid Raw Git process table");
                    long pid = Long.parseLong(values[0]);
                    long group = Long.parseLong(values[1]);
                    if (group == anchor.pid()) members.add(new Member(pid, values[2]));
                }
                if (!anchor.isAlive() || members.stream().noneMatch(row -> row.pid == anchor.pid() && !row.zombie())) {
                    anchorLost = true;
                    throw new IOException("Raw Git group anchor changed during inspection");
                }
                return new Group(members);
            } finally {
                if (ps.isAlive()) ps.destroyForcibly();
                try { ps.getInputStream().close(); } catch (IOException ignored) { }
            }
        }

        private List<ProcessHandle> cancellationMembers(Group observed, long deadline)
                throws IOException, InterruptedException {
            var candidates = new LinkedHashMap<Long, ProcessHandle>();
            for (Member member : observed.members) {
                if (member.pid == anchor.pid() || member.zombie()) continue;
                ProcessHandle handle = known.get(member.pid);
                if (handle == null || !handle.isAlive()) handle = ProcessHandle.of(member.pid).orElse(null);
                if (handle != null && handle.isAlive()) candidates.put(member.pid, handle);
            }
            // A PID in ps may have been reused before ProcessHandle.of(). Capture
            // identities first, then require group membership while those exact
            // handles survive a second observation. Do not infer identity from
            // wall-clock timestamps or their platform-dependent precision.
            Group verified = group(deadline);
            var members = new ArrayList<ProcessHandle>();
            for (ProcessHandle handle : candidates.values()) {
                if (handle.isAlive() && verified.members.stream().anyMatch(member ->
                        member.pid == handle.pid() && !member.zombie())) {
                    known.put(handle.pid(), handle);
                    members.add(handle);
                }
            }
            return members;
        }

        @Override boolean terminate(long deadline, long graceMillis, boolean rootFirst) {
            scopeLock.lock();
            boolean interrupted = Thread.interrupted();
            try {
                if (retired) return true;
                if (!admitted) {
                    abortStartup();
                    return observe(deadline).allExited();
                }
                if (!cancellationClaimed) {
                    // The anchor is intentionally alive after normal Git exit.
                    // Its liveness can never authorize killing a successful hook.
                    if (foreground == null || !foreground.isAlive()) return observe(deadline).allExited();
                    Group beforeCancellation = group(deadline);
                    // ProcessHandle can still regard an unreaped zombie as alive.
                    // Such a Git has already exited naturally; preserve its hook.
                    if (beforeCancellation.members.stream().noneMatch(member ->
                            member.pid == foreground.pid() && !member.zombie()))
                        return observe(deadline).allExited();
                    cancellationClaimed = true;
                }
                long forceAfter = Math.min(deadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(graceMillis));
                do {
                    Group group = group(deadline);
                    boolean force = System.nanoTime() >= forceAfter;
                    for (ProcessHandle handle : cancellationMembers(group, deadline)) {
                        if (force) handle.destroyForcibly(); else handle.destroy();
                    }
                    if (observe(deadline).allExited()) return true;
                    if (System.nanoTime() >= deadline) break;
                    Thread.sleep(10);
                } while (true);
            } catch (IOException | RuntimeException unavailable) { return false; }
            catch (InterruptedException cancelled) { interrupted = true; }
            finally {
                scopeLock.unlock();
                if (interrupted) Thread.currentThread().interrupt();
            }
            return false;
        }

        @Override public InputStream getInputStream() { return launcher.getInputStream(); }
        @Override public InputStream getErrorStream() { return launcher.getErrorStream(); }
        @Override public OutputStream getOutputStream() { return launcher.getOutputStream(); }
        @Override public int waitFor() throws InterruptedException {
            while (!waitFor(1, TimeUnit.DAYS)) { /* wait for Git, not the retained anchor */ }
            return exitValue();
        }
        @Override public boolean waitFor(long time, TimeUnit unit) throws InterruptedException {
            long deadline = System.nanoTime() + unit.toNanos(time);
            do {
                if (foregroundExit() != null) return true;
                if (System.nanoTime() >= deadline) return false;
                TimeUnit.NANOSECONDS.sleep(Math.max(0,
                        Math.min(TimeUnit.MILLISECONDS.toNanos(5), deadline - System.nanoTime())));
            } while (true);
        }
        @Override public int exitValue() {
            Integer value = foregroundExit();
            if (value != null) return value;
            if (!isAlive()) return -1; // unavailable protocol is never a successful scope proof
            throw new IllegalThreadStateException("Raw Git is still running");
        }
        @Override public boolean isAlive() {
            if (foregroundExit() != null) return false;
            return foreground != null ? foreground.isAlive() : launcher.isAlive();
        }
        @Override public long pid() { return foreground != null ? foreground.pid() : launcher.pid(); }
        @Override public ProcessHandle toHandle() { return foreground != null ? foreground : launcher.toHandle(); }
        @Override public void destroy() { terminate(System.nanoTime() + INSPECTION_NANOS, 1000, false); }
        @Override public Process destroyForcibly() { terminate(System.nanoTime() + INSPECTION_NANOS, 0, false); return this; }

        private record Member(long pid, String state) { boolean zombie() { return state.startsWith("Z"); } }
        private record Group(List<Member> members) { }
    }

    private static void deleteControl(Path directory) {
        try {
            for (String name : List.of("anchor", "foreground", "exit")) Files.deleteIfExists(directory.resolve(name));
            Files.deleteIfExists(directory);
        } catch (IOException ignored) { /* a retained temporary record does not change process exit proof */ }
    }
}
